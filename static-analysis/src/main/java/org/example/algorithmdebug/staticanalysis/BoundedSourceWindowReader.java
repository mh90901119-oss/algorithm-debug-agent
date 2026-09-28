package org.example.algorithmdebug.staticanalysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.investigation.SourceQueryBudget;
import org.example.algorithmdebug.contracts.investigation.SourceQueryErrorCode;
import org.example.algorithmdebug.contracts.investigation.SourceQueryResult.SourceWindow;

/** 只在注册模块根内读取 Java 源码的 UTF-8 有界窗口。 */
public final class BoundedSourceWindowReader {
    private static final int DRAIN_BUFFER_CHARACTERS = 4_096;
    private static final int PREFIX_LOOKAHEAD_CHARACTERS = 1;
    private static final String JAVA_SOURCE_SUFFIX = ".java";
    private static final String SHA_256 = "SHA-256";

    /**
     * 读取 SourceAnchor 覆盖的源码行，并同时计算完整文件 SHA-256。
     *
     * <p>拒绝绝对路径、非 Java 文件、符号链接、模块根逃逸和非法 UTF-8。</p>
     */
    public Result read(Path moduleRoot, SourceAnchor anchor, SourceQueryBudget budget) {
        if (moduleRoot == null || anchor == null || budget == null) {
            throw new IllegalArgumentException("Source window inputs must not be null");
        }
        Path source = resolveSafeSource(moduleRoot, anchor.sourceRelativePath());
        try {
            return readWindow(source, anchor, budget);
        } catch (SourceQueryException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new SourceQueryException(
                    SourceQueryErrorCode.SOURCE_QUERY_SOURCE_UNAVAILABLE,
                    "Source file cannot be read as strict UTF-8", failure);
        }
    }

    private static Path resolveSafeSource(Path moduleRoot, String relativePath) {
        if (!relativePath.endsWith(JAVA_SOURCE_SUFFIX)) {
            throw pathFailure("Source Query only permits Java source files");
        }
        try {
            Path requested = Path.of(relativePath);
            if (requested.isAbsolute()) {
                throw pathFailure("Absolute source paths are forbidden");
            }
            Path realRoot = moduleRoot.toRealPath();
            Path candidate = realRoot.resolve(requested).normalize();
            if (!candidate.startsWith(realRoot)) {
                throw pathFailure("Source path escapes the registered module root");
            }
            Path current = realRoot;
            for (Path component : requested.normalize()) {
                current = current.resolve(component);
                if (Files.isSymbolicLink(current)) {
                    throw pathFailure("Symbolic links are forbidden in Source Query paths");
                }
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new SourceQueryException(
                        SourceQueryErrorCode.SOURCE_QUERY_SOURCE_UNAVAILABLE,
                        "Source file is not available");
            }
            Path realSource = candidate.toRealPath();
            if (!realSource.startsWith(realRoot)) {
                throw pathFailure("Resolved source path escapes the registered module root");
            }
            return realSource;
        } catch (InvalidPathException failure) {
            throw new SourceQueryException(
                    SourceQueryErrorCode.SOURCE_QUERY_PATH_OUTSIDE_WORKSPACE,
                    "Source path is invalid", failure);
        } catch (IOException failure) {
            throw new SourceQueryException(
                    SourceQueryErrorCode.SOURCE_QUERY_SOURCE_UNAVAILABLE,
                    "Source path cannot be resolved", failure);
        }
    }

    private static Result readWindow(
            Path source,
            SourceAnchor anchor,
            SourceQueryBudget budget) throws IOException {
        int requestedEnd = anchor.endLine();
        long boundedEnd = Math.min(
                (long) requestedEnd,
                (long) anchor.startLine() + budget.maxSourceLines() - 1L);
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        SelectedLines selection;
        MessageDigest digest = sha256Digest();
        try (InputStream raw = Files.newInputStream(
                        source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                DigestInputStream digesting = new DigestInputStream(raw, digest);
                BoundedLineScanner scanner = new BoundedLineScanner(new BufferedReader(
                        new InputStreamReader(digesting, decoder)))) {
            selection = selectLines(
                    scanner, anchor.startLine(), (int) boundedEnd,
                    requestedEnd, budget.maxResponseBytes());
            scanner.drain();
        }
        if (selection.lines().isEmpty()) {
            throw new SourceQueryException(
                    SourceQueryErrorCode.SOURCE_QUERY_SOURCE_UNAVAILABLE,
                    "Source anchor starts beyond the available source file");
        }

        TextPrefix prefix = boundedText(selection.lines(), budget.maxResponseBytes());
        boolean lineLimited = boundedEnd < requestedEnd;
        boolean truncated = lineLimited || selection.contentTruncated() || prefix.truncated();
        List<String> limitations = new ArrayList<>(2);
        if (truncated) {
            limitations.add(SourceQueryErrorCode.SOURCE_QUERY_BUDGET_EXCEEDED.name());
        }
        if (!selection.reachedBoundedEnd() && !selection.contentTruncated()) {
            limitations.add(SourceQueryErrorCode.SOURCE_QUERY_SOURCE_UNAVAILABLE.name());
        }
        int toLine = anchor.startLine() + prefix.linesRepresented() - 1;
        return new Result(
                new SourceWindow(
                        anchor, anchor.startLine(), toLine,
                        HexFormat.of().formatHex(digest.digest()), prefix.text()),
                truncated,
                limitations);
    }

    private static SelectedLines selectLines(
            BoundedLineScanner scanner,
            int startLine,
            int boundedEnd,
            int requestedEnd,
            int maxBytes) throws IOException {
        List<String> selected = new ArrayList<>(Math.max(1, boundedEnd - startLine + 1));
        int storedUnits = 0;
        boolean reachedRequestedEnd = false;
        boolean reachedBoundedEnd = false;
        boolean contentTruncated = false;
        for (int lineNumber = 1; lineNumber <= boundedEnd; lineNumber++) {
            boolean retain = lineNumber >= startLine;
            int separatorUnits = retain && !selected.isEmpty() ? 1 : 0;
            int availableUnits = retain
                    ? Math.max(0, maxBytes + PREFIX_LOOKAHEAD_CHARACTERS
                            - storedUnits - separatorUnits)
                    : 0;
            ScannedLine line = scanner.readLine(availableUnits);
            if (!line.exists()) {
                break;
            }
            if (retain) {
                if (storedUnits + separatorUnits
                        > maxBytes + PREFIX_LOOKAHEAD_CHARACTERS) {
                    contentTruncated = true;
                    break;
                }
                selected.add(line.value());
                storedUnits += separatorUnits + line.value().length();
                contentTruncated = line.truncated();
            }
            if (lineNumber >= requestedEnd) {
                reachedRequestedEnd = true;
                reachedBoundedEnd = true;
                break;
            }
            if (lineNumber >= boundedEnd) {
                reachedBoundedEnd = true;
            }
            if (contentTruncated) {
                break;
            }
        }
        return new SelectedLines(
                List.copyOf(selected), reachedRequestedEnd,
                reachedBoundedEnd, contentTruncated);
    }

    private static TextPrefix boundedText(List<String> lines, int maxBytes) {
        StringBuilder text = new StringBuilder();
        int usedBytes = 0;
        int linesRepresented = 0;
        boolean truncated = false;
        for (String line : lines) {
            if (linesRepresented > 0) {
                if (usedBytes == maxBytes) {
                    truncated = true;
                    break;
                }
                text.append('\n');
                usedBytes++;
            }
            boolean represented = line.isEmpty();
            for (int offset = 0; offset < line.length();) {
                int codePoint = line.codePointAt(offset);
                String value = new String(Character.toChars(codePoint));
                int bytes = value.getBytes(StandardCharsets.UTF_8).length;
                if (usedBytes + bytes > maxBytes) {
                    truncated = true;
                    break;
                }
                text.append(value);
                usedBytes += bytes;
                represented = true;
                offset += Character.charCount(codePoint);
            }
            if (!represented && text.isEmpty()) {
                throw new SourceQueryException(
                        SourceQueryErrorCode.SOURCE_QUERY_BUDGET_EXCEEDED,
                        "Source response byte budget cannot represent the first code point");
            }
            linesRepresented++;
            if (truncated) {
                break;
            }
        }
        if (text.isEmpty()) {
            throw new SourceQueryException(
                    SourceQueryErrorCode.SOURCE_QUERY_SOURCE_UNAVAILABLE,
                    "Source window contains no representable text");
        }
        return new TextPrefix(text.toString(), linesRepresented, truncated);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK is missing SHA-256", failure);
        }
    }

    private static SourceQueryException pathFailure(String message) {
        return new SourceQueryException(
                SourceQueryErrorCode.SOURCE_QUERY_PATH_OUTSIDE_WORKSPACE, message);
    }

    private record TextPrefix(String text, int linesRepresented, boolean truncated) {
    }

    private record SelectedLines(
            List<String> lines,
            boolean reachedRequestedEnd,
            boolean reachedBoundedEnd,
            boolean contentTruncated) {
    }

    private record ScannedLine(boolean exists, String value, boolean truncated) {
        private static ScannedLine endOfFile() {
            return new ScannedLine(false, "", false);
        }
    }

    /** 逐字符丢弃超预算行后缀，避免 {@link BufferedReader#readLine()} 分配无界字符串。 */
    private static final class BoundedLineScanner implements AutoCloseable {
        private static final int NO_PENDING_CHARACTER = Integer.MIN_VALUE;

        private final BufferedReader reader;
        private int pendingCharacter = NO_PENDING_CHARACTER;

        private BoundedLineScanner(BufferedReader reader) {
            this.reader = reader;
        }

        private ScannedLine readLine(int maximumCharacters) throws IOException {
            StringBuilder value = new StringBuilder(
                    Math.min(maximumCharacters, DRAIN_BUFFER_CHARACTERS));
            boolean observed = false;
            boolean truncated = false;
            int character = nextCharacter();
            if (character == -1) {
                return ScannedLine.endOfFile();
            }
            observed = true;
            while (character != -1 && character != '\n' && character != '\r') {
                if (value.length() < maximumCharacters) {
                    value.append((char) character);
                } else {
                    truncated = true;
                }
                character = reader.read();
            }
            if (character == '\r') {
                int next = reader.read();
                if (next != '\n') {
                    pendingCharacter = next;
                }
            }
            return new ScannedLine(observed, value.toString(), truncated);
        }

        private int nextCharacter() throws IOException {
            if (pendingCharacter == NO_PENDING_CHARACTER) {
                return reader.read();
            }
            int value = pendingCharacter;
            pendingCharacter = NO_PENDING_CHARACTER;
            return value;
        }

        private void drain() throws IOException {
            if (pendingCharacter != NO_PENDING_CHARACTER) {
                pendingCharacter = NO_PENDING_CHARACTER;
            }
            char[] buffer = new char[DRAIN_BUFFER_CHARACTERS];
            while (reader.read(buffer) != -1) {
                // 必须消费到 EOF，确保哈希覆盖完整文件并验证窗口后的 UTF-8。
            }
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    /** 有界源码窗口及其降级信息。 */
    public record Result(SourceWindow window, boolean truncated, List<String> limitations) {
        /** 防止调用方修改 limitation。 */
        public Result {
            if (window == null || limitations == null) {
                throw new IllegalArgumentException("Source window result must not contain null");
            }
            limitations = List.copyOf(limitations);
        }
    }
}
