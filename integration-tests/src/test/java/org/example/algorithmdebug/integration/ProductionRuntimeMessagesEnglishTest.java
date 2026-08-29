package org.example.algorithmdebug.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductionRuntimeMessagesEnglishTest {

    @Test
    void productionJavaStringLiteralsMustNotContainHanCharacters() throws IOException {
        Path repositoryRoot = locateRepositoryRoot();
        List<Violation> violations = new ArrayList<>();
        try (var files = Files.walk(repositoryRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(ProductionRuntimeMessagesEnglishTest::isProductionJavaSource)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> scan(repositoryRoot, path, violations));
        }

        Map<String, Violation> uniqueByLiteral = new LinkedHashMap<>();
        violations.forEach(violation -> uniqueByLiteral.putIfAbsent(violation.literal(), violation));
        List<Violation> unique = List.copyOf(uniqueByLiteral.values());
        writeViolationReport(repositoryRoot, unique);
        String detail = unique.stream()
                .limit(100)
                .map(Violation::toString)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
        assertTrue(unique.isEmpty(), () -> "Production Java runtime strings contain Han characters ("
                + unique.size() + " unique literals):" + System.lineSeparator() + detail);
    }

    private static boolean isProductionJavaSource(Path path) {
        String portable = path.toString().replace('\\', '/');
        return portable.contains("/src/main/java/") && !portable.contains("/target/");
    }

    private static void scan(Path root, Path path, List<Violation> violations) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            SourceState state = SourceState.CODE;
            int line = 1;
            int literalLine = 1;
            StringBuilder literal = new StringBuilder();
            for (int index = 0; index < source.length(); index++) {
                char current = source.charAt(index);
                char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
                char third = index + 2 < source.length() ? source.charAt(index + 2) : '\0';
                if (current == '\n') {
                    line++;
                    if (state == SourceState.LINE_COMMENT) {
                        state = SourceState.CODE;
                    }
                    continue;
                }
                switch (state) {
                    case CODE -> {
                        if (current == '/' && next == '/') {
                            state = SourceState.LINE_COMMENT;
                            index++;
                        } else if (current == '/' && next == '*') {
                            state = SourceState.BLOCK_COMMENT;
                            index++;
                        } else if (current == '\'' ) {
                            state = SourceState.CHARACTER;
                        } else if (current == '"' && next == '"' && third == '"') {
                            state = SourceState.TEXT_BLOCK;
                            literalLine = line;
                            literal.setLength(0);
                            index += 2;
                        } else if (current == '"') {
                            state = SourceState.STRING;
                            literalLine = line;
                            literal.setLength(0);
                        }
                    }
                    case LINE_COMMENT -> { }
                    case BLOCK_COMMENT -> {
                        if (current == '*' && next == '/') {
                            state = SourceState.CODE;
                            index++;
                        }
                    }
                    case CHARACTER -> {
                        if (current == '\\') {
                            index++;
                        } else if (current == '\'') {
                            state = SourceState.CODE;
                        }
                    }
                    case STRING -> {
                        if (current == '\\') {
                            literal.append(current);
                            if (index + 1 < source.length()) {
                                literal.append(source.charAt(index + 1));
                            }
                            index++;
                        } else if (current == '"') {
                            addViolation(root, path, literalLine, literal, violations);
                            state = SourceState.CODE;
                        } else {
                            literal.append(current);
                        }
                    }
                    case TEXT_BLOCK -> {
                        if (current == '"' && next == '"' && third == '"') {
                            addViolation(root, path, literalLine, literal, violations);
                            state = SourceState.CODE;
                            index += 2;
                        } else {
                            literal.append(current);
                        }
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to scan production Java source: " + path, failure);
        }
    }

    private static boolean isHan(char value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }

    private static void addViolation(
            Path root, Path path, int line, StringBuilder literal, List<Violation> violations) {
        if (literal.chars().anyMatch(value -> isHan((char) value))) {
            violations.add(new Violation(root.relativize(path), line, literal.toString()));
        }
    }

    private static void writeViolationReport(Path repositoryRoot, List<Violation> violations)
            throws IOException {
        Path report = repositoryRoot.resolve(
                "integration-tests/target/runtime-message-violations.tsv");
        if (violations.isEmpty()) {
            Files.deleteIfExists(report);
            return;
        }
        Files.createDirectories(report.getParent());
        StringBuilder content = new StringBuilder();
        for (Violation violation : violations) {
            content.append(violation.path().toString().replace('\\', '/')).append('\t')
                    .append(violation.line()).append('\t')
                    .append(Base64.getEncoder().encodeToString(
                            violation.literal().getBytes(StandardCharsets.UTF_8)))
                    .append(System.lineSeparator());
        }
        Files.writeString(report, content, StandardCharsets.UTF_8);
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("ada-contracts"))
                    && Files.isDirectory(current.resolve("integration-tests"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Algorithm Debug Agent repository root was not found");
    }

    private enum SourceState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        CHARACTER,
        STRING,
        TEXT_BLOCK
    }

    private record Violation(Path path, int line, String literal) {
        @Override
        public String toString() {
            return path.toString().replace('\\', '/') + ":" + line + " -> \"" + literal + "\"";
        }
    }
}
