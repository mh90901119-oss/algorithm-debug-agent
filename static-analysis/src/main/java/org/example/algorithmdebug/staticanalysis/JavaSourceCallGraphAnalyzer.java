package org.example.algorithmdebug.staticanalysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.example.algorithmdebug.contracts.CallResolutionKind;
import org.example.algorithmdebug.contracts.MethodCallEdge;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceAnchor;

/** 使用 JDK Compiler Tree API 构建从一个目标 UT 出发的有界静态调用目录。 */
public final class JavaSourceCallGraphAnalyzer {

    private static final int MAX_WARNINGS = CatalogJsonSizeBudget.RESERVED_WARNING_COUNT;

    /**
     * 分析模块的 main/test Java 源码并返回目标方法可达目录。
     *
     * <p>deadline 是有界输入上的协作式期限。JDK 进程内 {@code parse/analyze} 不可可靠中断，
     * 因此只在调用前后检查；真正的 hard wall-clock timeout 需要未来的 worker process。</p>
     *
     * @param request 已校验的分析请求
     * @return 稳定排序、带源码锚点和截断说明的方法目录
     * @throws StaticAnalysisException 编译器不可用、源码不可读、编译阶段超期或目标方法不唯一
     */
    public MethodCatalog analyze(StaticAnalysisRequest request) {
        return analyze(request, List.of());
    }

    /** 使用调用方已解析的目标 Maven Test Classpath 进行 javac 符号分析。 */
    public MethodCatalog analyze(StaticAnalysisRequest request, List<Path> testClasspath) {
        if (testClasspath == null || testClasspath.stream().anyMatch(path -> path == null)) {
            throw new IllegalArgumentException("testClasspath must not contain null values");
        }
        BudgetGuard guard = new BudgetGuard(request);
        Discovery discovery = discoverSources(request, guard);
        if (discovery.sources().isEmpty()) {
            throw new StaticAnalysisException("The target module has no analyzable Java source within the budget");
        }
        return compileAndAnalyze(request, discovery, guard, List.copyOf(testClasspath));
    }

    private MethodCatalog compileAndAnalyze(
            StaticAnalysisRequest request, Discovery discovery, BudgetGuard guard,
            List<Path> testClasspath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new StaticAnalysisException("The current runtime does not include the JDK JavaCompiler");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ENGLISH, StandardCharsets.UTF_8)) {
            if (!testClasspath.isEmpty()) {
                fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH,
                        testClasspath.stream()
                                .map(path -> path.toAbsolutePath().normalize())
                                .toList());
            }
            List<JavaFileObject> units = discovery.sources().stream()
                    .map(MemoryJavaSource::new)
                    .map(value -> (JavaFileObject) value)
                    .toList();
            JavacTask task = (JavacTask) compiler.getTask(
                    null, fileManager, diagnostics,
                    List.of("-proc:none", "-Xlint:none", "-encoding", "UTF-8"), null, units);
            guard.requireBeforeCompiler("javac parse");
            List<CompilationUnitTree> parsed = new ArrayList<>();
            task.parse().forEach(parsed::add);
            guard.requireAfterCompiler("javac parse");
            task.analyze();
            guard.requireAfterCompiler("javac analyze");

            Trees trees = Trees.instance(task);
            Types types = task.getTypes();
            Elements elements = task.getElements();
            MethodScan methodScan = collectMethods(
                    request.moduleRoot(), parsed, trees, types, elements,
                    guard);
            EdgeScan edgeScan = collectEdges(parsed, trees, types, elements, methodScan, guard);
            return selectReachable(request, discovery, diagnostics, methodScan, edgeScan, guard);
        } catch (IOException exception) {
            throw new StaticAnalysisException("Failed to read Java source", exception);
        } catch (RuntimeException exception) {
            if (exception instanceof StaticAnalysisException staticException) {
                throw staticException;
            }
            throw new StaticAnalysisException("JDK AST analysis failed", exception);
        }
    }

    private static MethodScan collectMethods(
            Path moduleRoot,
            List<CompilationUnitTree> units,
            Trees trees,
            Types types,
            Elements elements,
            BudgetGuard guard) {
        Map<String, MethodModel> methods = new LinkedHashMap<>();
        SourcePositions positions = trees.getSourcePositions();
        try {
            for (CompilationUnitTree unit : units) {
                Path source = pathOf(unit.getSourceFile().toUri());
                String packageName = unit.getPackageName() == null
                        ? "" : unit.getPackageName().toString();
                if (packageName.isBlank()) {
                    throw new StaticAnalysisException("The default package is not supported by the static analysis plan");
                }
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree node, Void unused) {
                        if (!guard.tryMethod()) {
                            throw ScanLimitReached.INSTANCE;
                        }
                        MethodModel method = null;
                        Element element = trees.getElement(getCurrentPath());
                        if (element instanceof ExecutableElement executable
                                && (element.getKind() == ElementKind.METHOD
                                || element.getKind() == ElementKind.CONSTRUCTOR)
                                && executable.getEnclosingElement() instanceof TypeElement owner) {
                            String descriptor = descriptor(executable, types, elements);
                            String className = elements.getBinaryName(owner).toString();
                            String key = className + "#" + executable.getSimpleName() + descriptor;
                            long start = positions.getStartPosition(unit, node);
                            long end = positions.getEndPosition(unit, node);
                            String relative = moduleRoot.relativize(source).toString().replace('\\', '/');
                    SourceAnchor anchor = new SourceAnchor(
                            className, executable.getSimpleName().toString(), descriptor,
                            relative, line(unit, start), line(unit, Math.max(start, end - 1)));
                    method = new MethodModel(key, anchor, executable, owner);
                        }
                        if (!guard.tryCatalogMethod(packageName, method)) {
                            throw ScanLimitReached.INSTANCE;
                        }
                        if (method != null) {
                            methods.putIfAbsent(method.key(), method);
                        }
                        return super.visitMethod(node, unused);
                    }
                }.scan(unit, null);
            }
        } catch (ScanLimitReached ignored) {
            // BudgetGuard 已记录稳定的截断原因，停止访问剩余 AST。
        }
        return new MethodScan(Map.copyOf(methods));
    }

    private static EdgeScan collectEdges(
            List<CompilationUnitTree> units,
            Trees trees,
            Types types,
            Elements elements,
            MethodScan methodScan,
            BudgetGuard guard) {
        Set<String> knownMethods = methodScan.methods().keySet();
        Set<RawEdge> edges = new TreeSet<>(RawEdge.ORDER);
        Set<String> warnings = new TreeSet<>();
        SourcePositions positions = trees.getSourcePositions();
        try {
            for (CompilationUnitTree unit : units) {
                new TreePathScanner<Void, Void>() {
                    private String caller;

                    @Override
                    public Void visitMethod(MethodTree node, Void unused) {
                        String previous = caller;
                        caller = keyOf(trees.getElement(getCurrentPath()), types, elements);
                        super.visitMethod(node, unused);
                        caller = previous;
                        return null;
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        recordInvocation(node, trees.getElement(getCurrentPath()));
                        return super.visitMethodInvocation(node, unused);
                    }

                    @Override
                    public Void visitNewClass(NewClassTree node, Void unused) {
                        recordInvocation(node, trees.getElement(getCurrentPath()));
                        return super.visitNewClass(node, unused);
                    }

                    private void recordInvocation(com.sun.source.tree.Tree node, Element element) {
                        String callee = keyOf(element, types, elements);
                        if (caller != null && callee != null && knownMethods.contains(callee)) {
                            if (!guard.tryEdge(caller, callee)) {
                                throw ScanLimitReached.INSTANCE;
                            }
                            edges.add(new RawEdge(caller, callee,
                                    line(unit, positions.getStartPosition(unit, node)),
                                    CallResolutionKind.DIRECT));
                        } else if (caller != null && isUnresolved(element)
                                && warnings.size() < MAX_WARNINGS) {
                            warnings.add(unresolvedWarning(
                                    unit, positions.getStartPosition(unit, node), caller, node.toString()));
                        }
                        if (caller != null && element instanceof ExecutableElement declaredTarget
                                && declaredTarget.getKind() == ElementKind.METHOD
                                && !declaredTarget.getModifiers().contains(Modifier.STATIC)
                                && !declaredTarget.getModifiers().contains(Modifier.PRIVATE)
                                && !declaredTarget.getModifiers().contains(Modifier.FINAL)) {
                            String declaredKey = keyOf(declaredTarget, types, elements);
                            int sourceLine = line(unit, positions.getStartPosition(unit, node));
                            for (MethodModel candidate : methodScan.methods().values().stream()
                                    .sorted(Comparator.comparing(MethodModel::key))
                                    .toList()) {
                                if (candidate.executable().getKind() != ElementKind.METHOD
                                        || candidate.key().equals(declaredKey)
                                        || candidate.executable().getModifiers().contains(Modifier.ABSTRACT)
                                        || !elements.overrides(candidate.executable(), declaredTarget,
                                                candidate.owner())) {
                                    continue;
                                }
                                if (!guard.tryEdge(caller, candidate.key())) {
                                    throw ScanLimitReached.INSTANCE;
                                }
                                edges.add(new RawEdge(
                                        caller, candidate.key(), sourceLine,
                                        CallResolutionKind.POLYMORPHIC_CANDIDATE));
                            }
                        }
                    }
                }.scan(unit, null);
            }
        } catch (ScanLimitReached ignored) {
            // BudgetGuard 已记录稳定的截断原因，停止访问剩余 AST。
        }
        return new EdgeScan(List.copyOf(edges), List.copyOf(warnings));
    }

    private static MethodCatalog selectReachable(
            StaticAnalysisRequest request,
            Discovery discovery,
            DiagnosticCollector<JavaFileObject> diagnostics,
            MethodScan methodScan,
            EdgeScan edgeScan,
            BudgetGuard guard) {
        Map<String, MethodModel> methods = methodScan.methods();
        List<String> targetKeys = methods.values().stream()
                .filter(method -> method.anchor().className().equals(request.targetTest().className()))
                .filter(method -> method.anchor().methodName().equals(request.targetTest().methodName()))
                .map(MethodModel::key).sorted().toList();
        if (targetKeys.isEmpty()) {
            throw new StaticAnalysisException(
                    "TARGET_TEST_NOT_FOUND", "The target UT method was not found in the current source");
        }
        if (targetKeys.size() != 1) {
            throw new StaticAnalysisException("The target UT method must be unique; actual match count: " + targetKeys.size());
        }
        String targetKey = targetKeys.getFirst();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (RawEdge edge : edgeScan.edges()) {
            outgoing.computeIfAbsent(edge.caller(), ignored -> new ArrayList<>()).add(edge.callee());
        }
        outgoing.values().forEach(list -> list.sort(String::compareTo));
        Map<String, Integer> distances = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        distances.put(targetKey, 0);
        queue.add(targetKey);
        while (!queue.isEmpty()) {
            if (guard.stopForDeadline("reachability scan")) {
                break;
            }
            String caller = queue.removeFirst();
            for (String callee : outgoing.getOrDefault(caller, List.of())) {
                if (!distances.containsKey(callee)) {
                    distances.put(callee, distances.get(caller) + 1);
                    queue.addLast(callee);
                }
            }
        }

        List<String> orderedReachable = distances.keySet().stream()
                .sorted(Comparator.comparingInt((String key) -> distances.get(key)).thenComparing(key -> key))
                .toList();
        Set<String> savedKeys = new LinkedHashSet<>(orderedReachable);
        List<MethodCatalogEntry> entries = savedKeys.stream()
                .map(key -> new MethodCatalogEntry(
                        key, methods.get(key).anchor(), distances.get(key), key.equals(targetKey)))
                .toList();
        List<MethodCallEdge> edges = edgeScan.edges().stream()
                .filter(edge -> savedKeys.contains(edge.caller()) && savedKeys.contains(edge.callee()))
                .map(edge -> new MethodCallEdge(
                        edge.caller(), edge.callee(), edge.line(), edge.resolutionKind()))
                .toList();

        boolean compilerErrors = diagnostics.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR);
        WarningAccumulator warnings = new WarningAccumulator();
        discovery.budgetReasons().forEach(warnings::critical);
        guard.budgetReasons().forEach(warnings::critical);
        edgeScan.warnings().forEach(warnings::ordinary);
        diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .limit(50)
                .map(diagnostic -> "compiler: code=" + bounded(diagnostic.getCode(), 1_800)
                        + ", line=" + Math.max(0, diagnostic.getLineNumber()))
                .forEach(warnings::ordinary);
        boolean incomplete = discovery.truncated() || guard.truncated()
                || !edgeScan.warnings().isEmpty() || compilerErrors;
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG, request.caseId(), request.analysisId(), request.targetTest(),
                entries, edges, warnings.values(),
                incomplete ? SnapshotCompleteness.INCOMPLETE : SnapshotCompleteness.COMPLETE,
                guard.discoveredMethods(), guard.discoveredEdges(), request.requestedAt());
    }

    private static Discovery discoverSources(StaticAnalysisRequest request, BudgetGuard guard) {
        Comparator<Path> order = sourceOrder(request);
        PriorityQueue<Path> retained = new PriorityQueue<>(order.reversed());
        int discoveredFiles = 0;
        boolean deadlineTruncated = false;
        outer:
        for (String rootName : List.of("src/main/java", "src/test/java")) {
            Path root = request.moduleRoot().resolve(rootName);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    if (guard.stopForDeadline("source discovery")) {
                        deadlineTruncated = true;
                        break outer;
                    }
                    Path candidate = iterator.next();
                    if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(candidate)
                            || !candidate.getFileName().toString().endsWith(".java")) {
                        continue;
                    }
                    discoveredFiles++;
                    Path normalized = candidate.toAbsolutePath().normalize();
                    if (retained.size() < request.budget().maxFiles()) {
                        retained.add(normalized);
                    } else if (order.compare(normalized, retained.element()) < 0) {
                        retained.remove();
                        retained.add(normalized);
                    }
                }
            } catch (IOException exception) {
                throw new StaticAnalysisException("Failed to enumerate Java source files: " + rootName, exception);
            }
        }
        List<Path> selected = retained.stream().sorted(order).toList();
        List<SourceUnit> sources = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        long remainingBytes = request.budget().maxSourceBytes();
        boolean byteTruncated = false;
        for (Path candidate : selected) {
            if (guard.stopForDeadline("source read")) {
                deadlineTruncated = true;
                break;
            }
            byte[] content = readBounded(candidate, remainingBytes);
            if (content.length > remainingBytes) {
                byteTruncated = true;
                break;
            }
            SourceUnit unit = new SourceUnit(candidate, content);
            sources.add(unit);
            remainingBytes -= content.length;
        }
        boolean fileTruncated = discoveredFiles > request.budget().maxFiles();
        if (fileTruncated || byteTruncated) {
            reasons.add("source budget exceeded: discoveredFiles=" + discoveredFiles
                    + ", savedFiles=" + sources.size() + ", savedBytes="
                    + (request.budget().maxSourceBytes() - remainingBytes));
        }
        if (deadlineTruncated) {
            reasons.add("deadline budget exceeded during source discovery/read");
        }
        return new Discovery(List.copyOf(sources), List.copyOf(reasons),
                fileTruncated || byteTruncated || deadlineTruncated);
    }

    private static byte[] readBounded(Path path, long remainingBytes) {
        int maximum = (int) Math.min(Integer.MAX_VALUE - 1L, remainingBytes + 1L);
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(maximum);
        } catch (IOException exception) {
            throw new StaticAnalysisException("Failed to read Java source: " + path.getFileName(), exception);
        }
    }

    private static Comparator<Path> sourceOrder(StaticAnalysisRequest request) {
        String className = request.targetTest().className();
        int nested = className.indexOf('$');
        String topLevel = nested < 0 ? className : className.substring(0, nested);
        String targetRelative = "src/test/java/" + topLevel.replace('.', '/') + ".java";
        return Comparator
                .comparingInt((Path path) -> portableRelative(request.moduleRoot(), path)
                        .equals(targetRelative) ? 0 : 1)
                .thenComparing(path -> portableRelative(request.moduleRoot(), path));
    }

    private static String portableRelative(Path root, Path path) {
        return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static String keyOf(Element element, Types types, Elements elements) {
        if (!(element instanceof ExecutableElement executable)
                || (element.getKind() != ElementKind.METHOD
                && element.getKind() != ElementKind.CONSTRUCTOR)
                || !(element.getEnclosingElement() instanceof TypeElement owner)) {
            return null;
        }
        return elements.getBinaryName(owner) + "#" + executable.getSimpleName()
                + descriptor(executable, types, elements);
    }

    private static boolean isUnresolved(Element element) {
        if (!(element instanceof ExecutableElement executable)) {
            return true;
        }
        return executable.asType().getKind() == TypeKind.ERROR
                || executable.getEnclosingElement().asType().getKind() == TypeKind.ERROR;
    }

    private static String unresolvedWarning(
            CompilationUnitTree unit, long position, String caller, String expression) {
        return bounded("syntax-level unresolved invocation: caller=" + caller
                + ", line=" + line(unit, position) + ", expression=" + expression, 2_048);
    }

    private static String descriptor(ExecutableElement method, Types types, Elements elements) {
        StringBuilder value = new StringBuilder("(");
        method.getParameters().forEach(parameter -> value.append(typeDescriptor(
                types.erasure(parameter.asType()), types, elements)));
        return value.append(')').append(typeDescriptor(
                types.erasure(method.getReturnType()), types, elements)).toString();
    }

    private static String typeDescriptor(TypeMirror type, Types types, Elements elements) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case CHAR -> "C";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> "[" + typeDescriptor(((ArrayType) type).getComponentType(), types, elements);
            case DECLARED -> {
                Element element = ((DeclaredType) type).asElement();
                String name = element instanceof TypeElement declared
                        ? elements.getBinaryName(declared).toString()
                        : types.erasure(type).toString();
                yield "L" + name.replace('.', '/') + ";";
            }
            default -> "Ljava/lang/Object;";
        };
    }

    private static Path pathOf(URI uri) {
        return Path.of(uri).toAbsolutePath().normalize();
    }

    private static int line(CompilationUnitTree unit, long position) {
        if (position < 0 || unit.getLineMap() == null) {
            return 1;
        }
        return Math.max(1, (int) unit.getLineMap().getLineNumber(position));
    }

    private static String bounded(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record Discovery(
            List<SourceUnit> sources,
            List<String> budgetReasons,
            boolean truncated) {
    }

    private record SourceUnit(Path path, byte[] content) {
        private SourceUnit(Path path, byte[] content) {
            this.path = path;
            this.content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    private static final class MemoryJavaSource extends SimpleJavaFileObject {
        private final SourceUnit source;

        private MemoryJavaSource(SourceUnit source) {
            super(source.path().toUri(), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(source.content());
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(source.content()));
            } catch (CharacterCodingException failure) {
                throw new IOException("The Java source is not valid UTF-8: " + source.path().getFileName(), failure);
            }
        }
    }

    private record MethodModel(
            String key,
            SourceAnchor anchor,
            ExecutableElement executable,
            TypeElement owner) {
    }

    private record MethodScan(Map<String, MethodModel> methods) {
    }

    private record RawEdge(
            String caller,
            String callee,
            int line,
            CallResolutionKind resolutionKind) {
        private static final Comparator<RawEdge> ORDER = Comparator.comparing(RawEdge::caller)
                .thenComparing(RawEdge::callee)
                .thenComparingInt(RawEdge::line)
                .thenComparing(RawEdge::resolutionKind);
    }

    private record EdgeScan(List<RawEdge> edges, List<String> warnings) {
    }

    private static final class BudgetGuard {
        private final StaticAnalysisBudget budget;
        private final CatalogJsonSizeBudget catalogBytes;
        private final long deadlineNanos;
        private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
        private int discoveredMethods;
        private int discoveredEdges;
        private boolean methodTruncated;
        private boolean edgeTruncated;
        private boolean catalogTruncated;
        private boolean deadlineTruncated;

        private BudgetGuard(StaticAnalysisRequest request) {
            this.budget = request.budget();
            this.catalogBytes = new CatalogJsonSizeBudget(request);
            this.deadlineNanos = System.nanoTime() + budget.timeoutMillis() * 1_000_000L;
        }

        private boolean tryMethod() {
            if (stopForDeadline("method scan")) {
                methodTruncated = true;
                return false;
            }
            discoveredMethods++;
            if (discoveredMethods > budget.maxMethods()) {
                methodTruncated = true;
                reasons.add("method budget exceeded: discovered=" + discoveredMethods
                        + ", savedAtMost=" + budget.maxMethods());
                return false;
            }
            return true;
        }

        private boolean tryCatalogMethod(String packageName, MethodModel method) {
            String methodKey = method == null ? null : method.key();
            SourceAnchor anchor = method == null ? null : method.anchor();
            if (catalogBytes.tryMethod(packageName, methodKey, anchor)) {
                return true;
            }
            methodTruncated = true;
            catalogTruncated = true;
            reasons.add("catalog byte budget exceeded during method scan: attemptedUpperBound="
                    + catalogBytes.attemptedUpperBoundBytes() + ", savedUpperBound="
                    + catalogBytes.upperBoundBytes() + ", maxCatalogBytes="
                    + budget.maxCatalogBytes());
            return false;
        }

        private boolean tryEdge(String callerKey, String calleeKey) {
            if (stopForDeadline("edge scan")) {
                edgeTruncated = true;
                return false;
            }
            discoveredEdges++;
            if (discoveredEdges > budget.maxEdges()) {
                edgeTruncated = true;
                reasons.add("edge budget exceeded: discovered=" + discoveredEdges
                        + ", savedAtMost=" + budget.maxEdges());
                return false;
            }
            if (!catalogBytes.tryEdge(callerKey, calleeKey)) {
                edgeTruncated = true;
                catalogTruncated = true;
                reasons.add("catalog byte budget exceeded during edge scan: attemptedUpperBound="
                        + catalogBytes.attemptedUpperBoundBytes() + ", savedUpperBound="
                        + catalogBytes.upperBoundBytes() + ", maxCatalogBytes="
                        + budget.maxCatalogBytes());
                return false;
            }
            return true;
        }

        private boolean stopForDeadline(String phase) {
            if (System.nanoTime() <= deadlineNanos) {
                return false;
            }
            deadlineTruncated = true;
            reasons.add("deadline budget exceeded during " + phase);
            return true;
        }

        private void requireBeforeCompiler(String phase) {
            if (System.nanoTime() > deadlineNanos) {
                throw cooperativeDeadlineFailure(phase);
            }
        }

        private void requireAfterCompiler(String phase) {
            if (System.nanoTime() > deadlineNanos) {
                throw cooperativeDeadlineFailure(phase);
            }
        }

        private StaticAnalysisException cooperativeDeadlineFailure(String phase) {
            return new StaticAnalysisException(
                    "The cooperative static-analysis deadline was exceeded during the " + phase
                            + " phase; in-process javac does not provide a hard wall-clock timeout");
        }

        private boolean truncated() {
            return methodTruncated || edgeTruncated || deadlineTruncated || catalogTruncated;
        }

        private List<String> budgetReasons() {
            return List.copyOf(reasons);
        }

        private int discoveredMethods() {
            return discoveredMethods;
        }

        private int discoveredEdges() {
            return discoveredEdges;
        }

        private boolean methodTruncated() {
            return methodTruncated;
        }
    }

    private static final class WarningAccumulator {
        private final LinkedHashSet<String> critical = new LinkedHashSet<>();
        private final TreeSet<String> ordinary = new TreeSet<>();

        private void critical(String warning) {
            critical.add(bounded(warning, 2_048));
        }

        private void ordinary(String warning) {
            ordinary.add(bounded(warning, 2_048));
        }

        private List<String> values() {
            List<String> values = new ArrayList<>(MAX_WARNINGS);
            critical.stream().limit(MAX_WARNINGS).forEach(values::add);
            ordinary.stream().limit(MAX_WARNINGS - values.size()).forEach(values::add);
            return List.copyOf(values);
        }
    }

    private static final class ScanLimitReached extends RuntimeException {
        private static final ScanLimitReached INSTANCE = new ScanLimitReached();

        private ScanLimitReached() {
            super(null, null, false, false);
        }
    }
}
