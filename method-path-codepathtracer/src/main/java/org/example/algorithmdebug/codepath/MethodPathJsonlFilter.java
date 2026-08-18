package org.example.algorithmdebug.codepath;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;

/** 把包级 CodePath 原始 JSONL 流式过滤成计划方法范围内的有界 JSONL。 */
public final class MethodPathJsonlFilter {

    private static final int MAX_LINE_CHARS = 1_048_576;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    /**
     * 逐行校验并过滤，不在内存中保存完整 Trace；最终文件通过同目录临时文件原子发布。
     */
    public MethodPathFilterResult filter(
            Path rawTrace, Path filteredTrace, CodePathCollectionPlan plan)
            throws CodePathAdapterException {
        Path raw = checkedInput(rawTrace);
        Path output = checkedOutput(filteredTrace, raw);
        Map<String, Set<String>> selected = new HashMap<>();
        plan.selectors().forEach(selector -> selected.computeIfAbsent(
                selector.className() + "#" + selector.methodName(), ignored -> new java.util.HashSet<>())
                .add(selector.descriptor()));
        long deadline = System.nanoTime() + plan.budget().timeoutMillis() * 1_000_000L;
        Path temporary = null;
        long rawEvents = 0;
        long retained = 0;
        long exactDescriptorMatches = 0;
        long degradedClassMethodMatches = 0;
        long filteredBytes = 0;
        String truncation = null;
        try {
            long rawBytes = Files.size(raw);
            if (rawBytes > plan.budget().maxBytes()) {
                throw new CodePathAdapterException(
                        "CODEPATH_RAW_LIMIT_BREACH", "Raw Trace 超过计划执行期硬字节上限", null);
            }
            Files.createDirectories(output.getParent());
            temporary = Files.createTempFile(output.getParent(), ".method-path-", ".tmp");
            try (BoundedLineReader reader = new BoundedLineReader(raw, MAX_LINE_CHARS);
                    BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (System.nanoTime() > deadline) {
                        truncation = first(truncation, "filter timeoutMillis exceeded");
                        break;
                    }
                    if (line.isBlank()) {
                        throw new IOException("JSONL contains blank line");
                    }
                    MethodPathEvent event = mapper.readValue(line, MethodPathEvent.class);
                    rawEvents++;
                    Set<String> descriptors = selected.get(event.className() + "#" + event.methodName());
                    boolean degraded = descriptors != null && event.descriptor() == null;
                    boolean exact = descriptors != null && event.descriptor() != null
                            && descriptors.contains(event.descriptor());
                    boolean matches = degraded || exact;
                    if (!matches || truncation != null) {
                        continue;
                    }
                    if (event.depth() > plan.budget().maxCallDepth()) {
                        truncation = "maxCallDepth exceeded at eventId=" + event.eventId();
                        continue;
                    }
                    if (retained >= plan.budget().maxEvents()) {
                        truncation = "maxEvents exceeded";
                        continue;
                    }
                    String canonical = mapper.writeValueAsString(event);
                    long encodedBytes = canonical.getBytes(StandardCharsets.UTF_8).length + 1L;
                    if (filteredBytes + encodedBytes > plan.budget().maxBytes()) {
                        truncation = "maxBytes exceeded";
                        continue;
                    }
                    writer.write(canonical);
                    writer.write('\n');
                    retained++;
                    if (exact) {
                        exactDescriptorMatches++;
                    } else {
                        degradedClassMethodMatches++;
                    }
                    filteredBytes += encodedBytes;
                }
            }
            publish(temporary, output);
            temporary = null;
            return new MethodPathFilterResult(
                    rawEvents, retained, Files.size(raw), Files.size(output), sha256(output),
                    exactDescriptorMatches, degradedClassMethodMatches,
                    truncation != null, Optional.ofNullable(truncation));
        } catch (CodePathAdapterException failure) {
            throw failure;
        } catch (LineTooLargeException failure) {
            throw new CodePathAdapterException(
                    "CODEPATH_TRACE_LINE_TOO_LARGE", "CodePath JSONL 单行超过 1 MiB", failure);
        } catch (IOException | RuntimeException failure) {
            throw new CodePathAdapterException(
                    "CODEPATH_TRACE_INVALID", "无法安全过滤 CodePath JSONL", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件清理由上层 Collection 目录回收再次兜底。
                }
            }
        }
    }

    private static Path checkedInput(Path value) throws CodePathAdapterException {
        if (value == null) {
            throw new CodePathAdapterException("CODEPATH_TRACE_INVALID", "rawTrace 不能为空", null);
        }
        Path path = value.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new CodePathAdapterException(
                    "CODEPATH_TRACE_INVALID", "rawTrace 必须是非符号链接普通文件", null);
        }
        return path;
    }

    private static Path checkedOutput(Path value, Path raw) throws CodePathAdapterException {
        if (value == null) {
            throw new CodePathAdapterException("CODEPATH_OUTPUT_INVALID", "filteredTrace 不能为空", null);
        }
        Path path = value.toAbsolutePath().normalize();
        if (path.equals(raw) || path.getParent() == null || Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new CodePathAdapterException(
                    "CODEPATH_OUTPUT_INVALID", "filteredTrace 必须是新的独立文件", null);
        }
        return path;
    }

    private static void publish(Path temporary, Path output) throws IOException {
        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(temporary, output);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
    }

    private static String first(String current, String candidate) {
        return current == null ? candidate : current;
    }

    /** 在形成 String 前执行字节上限检查，避免 readLine 对无换行攻击输入进行无界分配。 */
    private static final class BoundedLineReader implements AutoCloseable {
        private final BufferedInputStream input;
        private final int maxBytes;

        private BoundedLineReader(Path path, int maxBytes) throws IOException {
            this.input = new BufferedInputStream(Files.newInputStream(path), 16 * 1024);
            this.maxBytes = maxBytes;
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8 * 1024));
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') {
                    break;
                }
                if (bytes.size() >= maxBytes) {
                    throw new LineTooLargeException();
                }
                bytes.write(value);
            }
            if (value == -1 && bytes.size() == 0) {
                return null;
            }
            byte[] encoded = bytes.toByteArray();
            int length = encoded.length;
            if (length > 0 && encoded[length - 1] == '\r') {
                length--;
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(encoded, 0, length)).toString();
            } catch (java.nio.charset.CharacterCodingException failure) {
                throw new IOException("JSONL 不是合法 UTF-8", failure);
            }
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    private static final class LineTooLargeException extends IOException {
        private LineTooLargeException() {
            super("JSONL line exceeds bounded reader limit");
        }
    }
}
