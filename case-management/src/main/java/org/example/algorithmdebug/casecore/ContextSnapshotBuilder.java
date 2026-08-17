package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.BuildSnapshot;
import org.example.algorithmdebug.contracts.ContextSnapshot;
import org.example.algorithmdebug.contracts.InputSnapshot;
import org.example.algorithmdebug.contracts.InputSnapshotStatus;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/** 对固定 allowlist 执行流式 Hash，生成不冒充完整 classpath 的有界 Context Snapshot。 */
public final class ContextSnapshotBuilder {

    private static final int DEFAULT_MAX_FILES = 20_000;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final long DEFAULT_MAX_FILE_BYTES = 16L * 1024 * 1024;

    private final int maxFiles;
    private final long maxTotalBytes;
    private final long maxFileBytes;
    private final Duration timeout;
    private final LongSupplier nanoTime;

    /** 使用设计规定的文件数、字节数和 10 秒预算。 */
    public ContextSnapshotBuilder() {
        this(DEFAULT_MAX_FILES, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_FILE_BYTES,
                Duration.ofSeconds(10), System::nanoTime);
    }

    /**
     * 创建可注入预算和单调时钟的 Builder。
     *
     * @param maxFiles 最多参与 Hash 的 Java 文件数，零表示立即降级
     * @param maxTotalBytes Java 文件总字节预算
     * @param maxFileBytes 单 Java 文件或输入文件字节预算
     * @param timeout 扫描耗时预算
     * @param nanoTime 单调时钟
     */
    public ContextSnapshotBuilder(
            int maxFiles,
            long maxTotalBytes,
            long maxFileBytes,
            Duration timeout,
            LongSupplier nanoTime) {
        if (maxFiles < 0 || maxTotalBytes <= 0 || maxFileBytes <= 0
                || timeout == null || timeout.isZero() || timeout.isNegative() || nanoTime == null) {
            throw new IllegalArgumentException("Context Snapshot 预算非法");
        }
        this.maxFiles = maxFiles;
        this.maxTotalBytes = maxTotalBytes;
        this.maxFileBytes = maxFileBytes;
        this.timeout = timeout;
        this.nanoTime = nanoTime;
    }

    /**
     * 读取固定范围并生成 Context Snapshot；预算缺口通过 INCOMPLETE 和 warnings 表达。
     *
     * @param request 已校验请求
     * @return 有界不可变快照
     */
    public ContextSnapshot build(ContextSnapshotRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        long started = nanoTime.getAsLong();
        List<String> warnings = new ArrayList<>();
        SourceSnapshot source = sourceSnapshot(request.moduleRoot(), started, warnings);
        InputSnapshot input = inputSnapshot(request.input(), warnings);
        String pomHash = hashRequiredFile(request.moduleRoot().resolve("pom.xml"), "pom.xml");
        BuildSnapshot build = new BuildSnapshot(
                pomHash, request.javaVersion(), request.adapterId(), request.adapterVersion());
        SnapshotCompleteness completeness = source.completeness() == SnapshotCompleteness.COMPLETE
                && input.status() != InputSnapshotStatus.UNRESOLVED
                ? SnapshotCompleteness.COMPLETE : SnapshotCompleteness.INCOMPLETE;
        List<String> boundedWarnings = warnings.stream().limit(20).toList();
        String fingerprint = fingerprint(request, source, input, build, completeness);
        return new ContextSnapshot(
                SchemaVersions.CONTEXT_SNAPSHOT,
                request.caseId(), request.contextId(), request.projectId(), request.targetTest(),
                request.repositoryRevision(), source, input, build, completeness,
                fingerprint, boundedWarnings, request.createdAt());
    }

    private SourceSnapshot sourceSnapshot(Path moduleRoot, long started, List<String> warnings) {
        List<Path> candidates = new ArrayList<>();
        boolean complete = true;
        for (Path relativeRoot : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
            Path root = moduleRoot.resolve(relativeRoot).normalize();
            if (!root.startsWith(moduleRoot) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                Iterator<Path> iterator = paths.iterator();
                while (iterator.hasNext()) {
                    if (elapsed(started)) {
                        addWarning(warnings, "CONTEXT_SCAN_TIMEOUT");
                        complete = false;
                        break;
                    }
                    Path candidate = iterator.next();
                    if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                            || !candidate.getFileName().toString().endsWith(".java")) {
                        continue;
                    }
                    if (candidates.size() >= maxFiles) {
                        addWarning(warnings, "CONTEXT_SOURCE_FILE_LIMIT");
                        complete = false;
                        break;
                    }
                    candidates.add(candidate);
                }
            } catch (IOException | SecurityException failure) {
                addWarning(warnings, "CONTEXT_SOURCE_SCAN_FAILED");
                complete = false;
            }
            if (!complete) {
                break;
            }
        }
        candidates.sort(Comparator.comparing(path -> portable(moduleRoot.relativize(path))));
        MessageDigest aggregate = digest();
        long totalBytes = 0;
        int fileCount = 0;
        for (Path candidate : candidates) {
            if (elapsed(started)) {
                addWarning(warnings, "CONTEXT_SCAN_TIMEOUT");
                complete = false;
                break;
            }
            try {
                long size = Files.size(candidate);
                if (size > maxFileBytes) {
                    addWarning(warnings, "CONTEXT_SOURCE_FILE_TOO_LARGE");
                    complete = false;
                    break;
                }
                if (totalBytes + size > maxTotalBytes) {
                    addWarning(warnings, "CONTEXT_SOURCE_TOTAL_LIMIT");
                    complete = false;
                    break;
                }
                FileTime modified = Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS);
                String contentHash = hashFile(candidate);
                if (size != Files.size(candidate)
                        || !modified.equals(Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS))) {
                    addWarning(warnings, "CONTEXT_SOURCE_CHANGED_DURING_SCAN");
                    complete = false;
                    break;
                }
                update(aggregate, portable(moduleRoot.relativize(candidate)));
                update(aggregate, Long.toString(size));
                update(aggregate, contentHash);
                totalBytes += size;
                fileCount++;
            } catch (IOException | SecurityException failure) {
                addWarning(warnings, "CONTEXT_SOURCE_READ_FAILED");
                complete = false;
                break;
            }
        }
        return new SourceSnapshot(
                HexFormat.of().formatHex(aggregate.digest()), fileCount, totalBytes,
                complete ? SnapshotCompleteness.COMPLETE : SnapshotCompleteness.INCOMPLETE);
    }

    private InputSnapshot inputSnapshot(ContextInputProbe probe, List<String> warnings) {
        if (probe.status() != InputSnapshotStatus.PRESENT) {
            if (probe.status() == InputSnapshotStatus.UNRESOLVED) {
                addWarning(warnings, "CONTEXT_INPUT_UNRESOLVED");
            }
            return new InputSnapshot(
                    probe.status(), probe.relativePath(), "", 0, probe.diagnostic());
        }
        Path path = probe.path().orElseThrow();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return new InputSnapshot(
                    InputSnapshotStatus.MISSING, probe.relativePath(), "", 0,
                    "input disappeared before snapshot");
        }
        try {
            long size = Files.size(path);
            if (size > maxFileBytes) {
                addWarning(warnings, "CONTEXT_INPUT_TOO_LARGE");
                return new InputSnapshot(
                        InputSnapshotStatus.UNRESOLVED, probe.relativePath(), "", 0,
                        "input exceeds snapshot budget");
            }
            return new InputSnapshot(
                    InputSnapshotStatus.PRESENT, probe.relativePath(), hashFile(path), size, "");
        } catch (IOException | SecurityException failure) {
            addWarning(warnings, "CONTEXT_INPUT_READ_FAILED");
            return new InputSnapshot(
                    InputSnapshotStatus.UNRESOLVED, probe.relativePath(), "", 0,
                    "input could not be read");
        }
    }

    private String hashRequiredFile(Path file, String name) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("CONTEXT_SNAPSHOT_FAILED", name + " 不存在");
        }
        try {
            if (Files.size(file) > maxFileBytes) {
                throw new WorkspaceException("CONTEXT_SNAPSHOT_FAILED", name + " 超过大小预算");
            }
            return hashFile(file);
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException("CONTEXT_SNAPSHOT_FAILED", "无法读取 " + name, failure);
        }
    }

    private String fingerprint(
            ContextSnapshotRequest request,
            SourceSnapshot source,
            InputSnapshot input,
            BuildSnapshot build,
            SnapshotCompleteness completeness) {
        MessageDigest value = digest();
        for (String item : List.of(
                SchemaVersions.CONTEXT_SNAPSHOT,
                request.projectId().value(),
                request.targetTest().selector(),
                request.repositoryRevision(),
                source.sha256(),
                source.completeness().name(),
                input.status().name(),
                input.relativePath(),
                input.sha256(),
                Long.toString(input.sizeBytes()),
                build.pomSha256(),
                build.javaVersion(),
                build.adapterId(),
                build.adapterVersion(),
                completeness.name())) {
            update(value, item);
        }
        return HexFormat.of().formatHex(value.digest());
    }

    private boolean elapsed(long started) {
        return nanoTime.getAsLong() - started > timeout.toNanos();
    }

    private static String hashFile(Path file) throws IOException {
        MessageDigest value = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) {
                    value.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(value.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", failure);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (warnings.size() < 20 && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
