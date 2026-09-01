package org.example.algorithmdebug.staticanalysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.example.algorithmdebug.contracts.AlgorithmInputPathKind;
import org.example.algorithmdebug.contracts.TargetTest;

/** 使用 JDK Compiler Tree API 定位目标 UT 第一层的单一算法输入路径字面量。 */
public final class JavaTestAlgorithmInputLocator {

    /**
     * 定位目标测试方法第一层直接声明的 {@code String} 或 {@code java.lang.String} 字面量。
     * 不遍历条件、循环、lambda、匿名类或辅助方法。
     */
    public AlgorithmInputLocation locate(Path moduleRoot, TargetTest targetTest) {
        if (moduleRoot == null || targetTest == null) {
            throw new IllegalArgumentException("moduleRoot and targetTest are required");
        }
        Path module = moduleRoot.toAbsolutePath().normalize();
        Path source = module.resolve("src/test/java")
                .resolve(targetTest.className().replace('.', '/') + ".java").normalize();
        if (!source.startsWith(module) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) {
            throw failure("TARGET_TEST_NOT_FOUND", "Target UT source was not found");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw failure("ALGORITHM_INPUT_SOURCE_PARSE_FAILED", "JDK JavaCompiler is unavailable");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(
                    null, files, diagnostics, List.of("-proc:none", "-encoding", "UTF-8"), null,
                    files.getJavaFileObjects(source.toFile()));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            if (diagnostics.getDiagnostics().stream().anyMatch(
                    diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)) {
                throw failure("ALGORITHM_INPUT_SOURCE_PARSE_FAILED", "Target UT source cannot be parsed");
            }
            return locateInAst(
                    module, source, Files.readString(source), targetTest, units, Trees.instance(task));
        } catch (IOException failure) {
            throw failure("ALGORITHM_INPUT_SOURCE_PARSE_FAILED", "Target UT source cannot be read");
        }
    }

    private static AlgorithmInputLocation locateInAst(
            Path module, Path source, String sourceText, TargetTest target,
            List<CompilationUnitTree> units, Trees trees) {
        String simpleClassName = target.className().substring(target.className().lastIndexOf('.') + 1);
        List<MethodMatch> methods = new ArrayList<>();
        for (CompilationUnitTree unit : units) {
            for (Tree declaration : unit.getTypeDecls()) {
                if (declaration instanceof ClassTree type
                        && simpleClassName.contentEquals(type.getSimpleName())) {
                    type.getMembers().stream()
                            .filter(MethodTree.class::isInstance)
                            .map(MethodTree.class::cast)
                            .filter(method -> target.methodName().contentEquals(method.getName()))
                            .forEach(method -> methods.add(new MethodMatch(unit, method)));
                }
            }
        }
        if (methods.isEmpty()) {
            throw failure("TARGET_TEST_NOT_FOUND", "Target UT method was not found");
        }
        if (methods.size() != 1) {
            throw failure("TARGET_TEST_NOT_FOUND", "Target UT method is ambiguous");
        }
        MethodMatch match = methods.getFirst();
        SourcePositions positions = trees.getSourcePositions();
        Map<Path, Candidate> distinct = new LinkedHashMap<>();
        boolean unsupported = false;
        if (match.method().getBody() != null) {
            for (Tree statement : match.method().getBody().getStatements()) {
                if (!(statement instanceof VariableTree variable) || !isString(variable)) {
                    continue;
                }
                Tree initializer = variable.getInitializer();
                if (initializer instanceof LiteralTree literal && literal.getValue() instanceof String value) {
                if (hasSupportedInputSuffix(value)) {
                        if (isDirectLiteral(sourceText, match.unit(), initializer, positions)) {
                            Candidate candidate = candidate(
                                    module, source, match.unit(), variable, value, positions);
                            distinct.putIfAbsent(candidate.resolvedPath(), candidate);
                        } else {
                            unsupported = true;
                        }
                    }
            } else if (initializer != null && hasSupportedInputSuffix(initializer.toString())) {
                    unsupported = true;
                }
            }
        }
        if (unsupported) {
            throw failure("ALGORITHM_INPUT_EXPRESSION_UNSUPPORTED",
                    "Algorithm input path must be a direct String literal");
        }
        if (distinct.isEmpty()) {
            throw failure("ALGORITHM_INPUT_NOT_FOUND",
                    "Target UT has no first-level String literal ending with input.json or input_.json");
        }
        if (distinct.size() != 1) {
            throw failure("MULTIPLE_ALGORITHM_INPUTS_UNSUPPORTED",
                    "Target UT declares more than one algorithm input");
        }
        Candidate candidate = distinct.values().iterator().next();
        return new AlgorithmInputLocation(
                candidate.variableName(), source, candidate.sourceLine(),
                candidate.pathKind(), candidate.resolvedPath());
    }

    private static Candidate candidate(
            Path module, Path source, CompilationUnitTree unit, VariableTree variable,
            String declaredPath, SourcePositions positions) {
        try {
            Path configured = Path.of(declaredPath);
            AlgorithmInputPathKind kind = configured.isAbsolute()
                    ? AlgorithmInputPathKind.ABSOLUTE : AlgorithmInputPathKind.RELATIVE;
            Path resolved = (configured.isAbsolute() ? configured : module.resolve(configured))
                    .toAbsolutePath().normalize();
            long position = positions.getStartPosition(unit, variable);
            long line = unit.getLineMap().getLineNumber(position);
            return new Candidate(variable.getName().toString(), resolved, line, kind);
        } catch (InvalidPathException failure) {
            throw failure("ALGORITHM_INPUT_EXPRESSION_UNSUPPORTED",
                    "Algorithm input String literal is not a valid path");
        }
    }

    private static boolean isString(VariableTree variable) {
        if (variable.getType() == null) {
            return false;
        }
        String type = variable.getType().toString();
        return "String".equals(type) || "java.lang.String".equals(type);
    }

    private static boolean isDirectLiteral(
            String source, CompilationUnitTree unit, Tree initializer, SourcePositions positions) {
        long start = positions.getStartPosition(unit, initializer);
        long end = positions.getEndPosition(unit, initializer);
        if (start < 0 || end <= start || end > source.length()) {
            return false;
        }
        String expression = source.substring((int) start, (int) end).strip();
        if (!expression.startsWith("\"") || expression.length() < 2) {
            return false;
        }
        if (expression.startsWith("\"\"\"")) {
            return expression.endsWith("\"\"\"") && expression.length() >= 6;
        }
        boolean escaped = false;
        for (int index = 1; index < expression.length(); index++) {
            char value = expression.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == '"') {
                return expression.substring(index + 1).isBlank();
            }
        }
        return false;
    }

    private static boolean hasSupportedInputSuffix(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.endsWith("input.json") || normalized.endsWith("input_.json");
    }

    private static AlgorithmInputLocationException failure(String code, String message) {
        return new AlgorithmInputLocationException(code, message);
    }

    private record MethodMatch(CompilationUnitTree unit, MethodTree method) { }
    private record Candidate(
            String variableName, Path resolvedPath, long sourceLine, AlgorithmInputPathKind pathKind) { }
}
