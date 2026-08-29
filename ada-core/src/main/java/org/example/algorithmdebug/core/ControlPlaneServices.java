package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.ProjectIdGenerator;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.ProjectRegistry;
import org.example.algorithmdebug.casecore.RepositoryRootLocator;
import org.example.algorithmdebug.casecore.WorkspaceInitializer;
import org.example.algorithmdebug.casecore.WorkspaceManifestRepository;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.harness.MavenTestExecutor;
import org.example.algorithmdebug.plan.CodePathPlanCompiler;
import org.example.algorithmdebug.staticanalysis.JavaSourceCallGraphAnalyzer;
import org.example.algorithmdebug.staticanalysis.JavaTestAlgorithmInputLocator;
import org.example.algorithmdebug.methodpath.MethodPathCollector;
import org.example.algorithmdebug.methodpath.MethodPathCollectionException;
import org.example.algorithmdebug.methodpath.TargetClasspathResolver;
import org.example.algorithmdebug.contracts.DoctorCheck;
import org.example.algorithmdebug.contracts.DoctorStatus;
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * 鍦?Core 鍐呰閰?Workspace 鎺у埗闈㈡湇鍔★紝閬垮厤 CLI 鐩存帴渚濊禆 Case 瀹炵幇绫诲瀷銆? */
public final class ControlPlaneServices {

    private final WorkspaceApplicationService workspace;
    private final ProjectApplicationService project;
    private final DoctorApplicationService doctor;
    private final CaseApplicationService cases;
    private final AlgorithmInputApplicationService algorithmInputs;
    private final RunApplicationService runs;
    private final StaticAnalysisApplicationService staticAnalysis;
    private final CollectionApplicationService collections;
    private final JdwpCollectionApplicationService jdwpCollections;

    private ControlPlaneServices(
            WorkspaceApplicationService workspace,
            ProjectApplicationService project,
            DoctorApplicationService doctor,
            CaseApplicationService cases,
            AlgorithmInputApplicationService algorithmInputs,
            RunApplicationService runs,
            StaticAnalysisApplicationService staticAnalysis,
            CollectionApplicationService collections,
            JdwpCollectionApplicationService jdwpCollections) {
        this.workspace = workspace;
        this.project = project;
        this.doctor = doctor;
        this.cases = cases;
        this.algorithmInputs = algorithmInputs;
        this.runs = runs;
        this.staticAnalysis = staticAnalysis;
        this.collections = collections;
        this.jdwpCollections = jdwpCollections;
    }

    /**
     * 浣跨敤鏈満鐜鍜屾樉寮忔敞鍏ョ殑鏃堕棿/骞冲彴淇℃伅瑁呴厤榛樿绂荤嚎鎺у埗闈€?     *
     * @param clock Workspace 鍜岄」鐩櫥璁版椂閽?     * @param javaFeatureSupplier Java feature 鎻愪緵鍣?     * @param environment 鐜鍙橀噺蹇収
     * @param pathSeparator PATH 鍒嗛殧绗?     * @param windows 鏄惁涓?Windows
     * @return 瀹屾暣 Core 鏈嶅姟闆嗗悎
     */
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows) {
        return createInternal(
                clock, javaFeatureSupplier, environment, pathSeparator, windows,
                null, null, null, null, null, null, null, List.of());
    }

    /**
     * 瑁呴厤鍖呭惈 Case/Run 鐢ㄤ緥鐨勫畬鏁寸绾挎湇鍔￠泦鍚堬紱Adapter 鐢?CLI 缁勫悎鏍规樉寮忔敞鍏ャ€?     */
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter> adapters,
            Path mavenExecutable) {
        return create(
                clock, javaFeatureSupplier, environment, pathSeparator, windows,
                adapters, Optional.ofNullable(mavenExecutable));
    }

    /** 瑁呴厤瀹屾暣鏈嶅姟闆嗗悎锛汳aven 缂哄け涓嶄細闃绘鍙/Case 鍛戒护銆?*/
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter> adapters,
            Optional<Path> mavenExecutable) {
        if (adapters == null || mavenExecutable == null) {
            throw new IllegalArgumentException("The full control plane requires adapters and mavenExecutable");
        }
        MethodPathCollector unavailableCollector = request -> {
            throw new MethodPathCollectionException(
                    "CODEPATH_TOOL_NOT_CONFIGURED", "CodePath launcher is not configured", null);
        };
        TargetClasspathResolver unavailableClasspath = (maven, module, output) -> {
            throw new MethodPathCollectionException(
                    "CODEPATH_TOOL_NOT_CONFIGURED", "CodePath classpath resolver is not configured", null);
        };
        return create(clock, javaFeatureSupplier, environment, pathSeparator, windows, adapters,
                mavenExecutable, unavailableCollector, unavailableClasspath,
                () -> new DoctorCheck(
                        "codepath", DoctorStatus.FAIL, "CODEPATH_TOOL_NOT_CONFIGURED",
                        "CodePath launcher is not configured"));
    }

    /** CLI 缁勫悎鏍规樉寮忔敞鍏?Collector 瀹炵幇锛汣ore 浠呬緷璧栫ǔ瀹?SPI銆?*/
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter> adapters,
            Optional<Path> mavenExecutable,
            MethodPathCollector collector,
            TargetClasspathResolver classpathResolver) {
        return create(clock, javaFeatureSupplier, environment, pathSeparator, windows,
                adapters, mavenExecutable, collector, classpathResolver,
                () -> new DoctorCheck(
                        "codepath", DoctorStatus.PASS, "CODEPATH_TOOL_INJECTED",
                        "CodePath Collector is injected"));
    }

    /** CLI 缁勫悎鏍瑰悓鏃舵敞鍏?Collector 涓庡叾纭畾鎬?Doctor 鎺㈤拡銆?*/
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter> adapters,
            Optional<Path> mavenExecutable,
            MethodPathCollector collector,
            TargetClasspathResolver classpathResolver,
            ToolDoctorProbe toolProbe) {
        if (adapters == null || mavenExecutable == null || collector == null
                || classpathResolver == null || toolProbe == null) {
            throw new IllegalArgumentException("Full control plane composition dependencies must not be null");
        }
        return createInternal(clock, javaFeatureSupplier, environment, pathSeparator, windows,
                List.copyOf(adapters), mavenExecutable, collector, classpathResolver,
                null, null, null, List.of(toolProbe));
    }

    /** CLI 缁勫悎鏍瑰悓鏃舵敞鍏?CodePath 涓?JDWP 宸ュ叿杈圭晫鍙婂叾 Doctor 鎺㈤拡銆?*/
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter> adapters,
            Optional<Path> mavenExecutable,
            MethodPathCollector collector,
            TargetClasspathResolver classpathResolver,
            JdwpToolConfiguration jdwpTool,
            JdwpCollectionExecutor jdwpExecutor,
            JdwpPortProvider jdwpPorts,
            ToolDoctorProbe codePathProbe,
            ToolDoctorProbe jdwpProbe) {
        return create(clock, javaFeatureSupplier, environment, pathSeparator, windows,
                adapters, mavenExecutable, collector, classpathResolver, jdwpTool,
                jdwpExecutor, jdwpPorts, codePathProbe, jdwpProbe, AgentExecutionLog.disabled());
    }

    /** CLI 注入统一 Java 文件日志端口。 */
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter> adapters,
            Optional<Path> mavenExecutable,
            MethodPathCollector collector,
            TargetClasspathResolver classpathResolver,
            JdwpToolConfiguration jdwpTool,
            JdwpCollectionExecutor jdwpExecutor,
            JdwpPortProvider jdwpPorts,
            ToolDoctorProbe codePathProbe,
            ToolDoctorProbe jdwpProbe,
            AgentExecutionLog executionLog) {
        if (jdwpTool == null || jdwpExecutor == null || jdwpPorts == null
                || codePathProbe == null || jdwpProbe == null || executionLog == null) {
            throw new IllegalArgumentException("JDWP control plane composition dependencies must not be null");
        }
        return createInternal(clock, javaFeatureSupplier, environment, pathSeparator, windows,
                List.copyOf(adapters), mavenExecutable, collector, classpathResolver,
                jdwpTool, jdwpExecutor, jdwpPorts, List.of(codePathProbe, jdwpProbe), executionLog);
    }

    private static ControlPlaneServices createInternal(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter> adapters,
            Optional<Path> mavenExecutable,
            MethodPathCollector methodPathCollector,
            TargetClasspathResolver classpathResolver,
            JdwpToolConfiguration jdwpTool,
            JdwpCollectionExecutor jdwpExecutor,
            JdwpPortProvider jdwpPorts,
            List<ToolDoctorProbe> toolProbes) {
        return createInternal(clock, javaFeatureSupplier, environment, pathSeparator, windows,
                adapters, mavenExecutable, methodPathCollector, classpathResolver,
                jdwpTool, jdwpExecutor, jdwpPorts, toolProbes, AgentExecutionLog.disabled());
    }

    private static ControlPlaneServices createInternal(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter> adapters,
            Optional<Path> mavenExecutable,
            MethodPathCollector methodPathCollector,
            TargetClasspathResolver classpathResolver,
            JdwpToolConfiguration jdwpTool,
            JdwpCollectionExecutor jdwpExecutor,
            JdwpPortProvider jdwpPorts,
            List<ToolDoctorProbe> toolProbes,
            AgentExecutionLog executionLog) {
        if (clock == null || javaFeatureSupplier == null || environment == null
                || pathSeparator == null || pathSeparator.isEmpty() || toolProbes == null) {
            throw new IllegalArgumentException("ControlPlaneServices dependencies must be valid");
        }
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        WorkspaceManifestRepository manifestRepository = new WorkspaceManifestRepository(mapper, writer);
        WorkspaceInitializer initializer = new WorkspaceInitializer(manifestRepository, clock);
        ProjectRegistry registry = new ProjectRegistry(
                manifestRepository,
                new ProjectRegistrationRepository(mapper, writer),
                new RepositoryRootLocator(),
                new ProjectIdGenerator(),
                clock);
        MavenExecutableLocator mavenLocator = new MavenExecutableLocator(
                environment, pathSeparator, windows);
        CaseApplicationService cases = null;
        AlgorithmInputApplicationService algorithmInputs = null;
        RunApplicationService runs = null;
        StaticAnalysisApplicationService staticAnalysis = null;
        CollectionApplicationService collections = null;
        JdwpCollectionApplicationService jdwpCollections = null;
        if (adapters != null) {
            AdapterCatalog catalog = new AdapterCatalog(adapters);
            OpaqueIdGenerator ids = new OpaqueIdGenerator();
            cases = new CaseApplicationService(
                    new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                    catalog, ids, clock, executionLog);
            algorithmInputs = new AlgorithmInputApplicationService(
                    new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                    new JavaTestAlgorithmInputLocator(), clock, executionLog);
            runs = new RunApplicationService(
                    new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                    catalog, ids, clock, new MavenTestExecutor(),
                    new RunArtifactArchiver(), mavenExecutable, executionLog);
            staticAnalysis = new StaticAnalysisApplicationService(
                    new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                    new JavaSourceCallGraphAnalyzer(), new CodePathPlanCompiler(), clock,
                    mavenExecutable, classpathResolver, executionLog);
            collections = new CollectionApplicationService(
                    new ProjectRegistrationRepository(mapper, writer), mapper, writer, catalog,
                    ids, clock, mavenExecutable, currentJavaExecutable(windows),
                    methodPathCollector, classpathResolver, executionLog);
            if (jdwpTool != null) {
                jdwpCollections = new JdwpCollectionApplicationService(
                        new ProjectRegistrationRepository(mapper, writer), mapper, writer, catalog,
                        ids, clock, mavenExecutable, currentJavaExecutable(windows), jdwpTool,
                        jdwpExecutor, jdwpPorts, executionLog);
            }
        }
        return new ControlPlaneServices(
                new WorkspaceApplicationService(initializer),
                new ProjectApplicationService(registry),
                new DoctorApplicationService(
                        javaFeatureSupplier, mavenLocator, manifestRepository, toolProbes),
                cases,
                algorithmInputs,
                runs,
                staticAnalysis,
                collections,
                jdwpCollections);
    }

    /** @return Workspace 鍒濆鍖栨湇鍔?*/
    public WorkspaceApplicationService workspace() {
        return workspace;
    }

    /** @return 椤圭洰娉ㄥ唽鏈嶅姟 */
    public ProjectApplicationService project() {
        return project;
    }

    /** @return 鐜璇婃柇鏈嶅姟 */
    public DoctorApplicationService doctor() {
        return doctor;
    }

    /** @return 瀹屾暣瑁呴厤涓嬬殑 Case 鐢ㄤ緥锛涘熀纭€鎺у埗闈㈣閰嶄笉鎻愪緵 */
    public CaseApplicationService cases() {
        if (cases == null) {
            throw new IllegalStateException("ControlPlaneServices has no configured Adapter");
        }
        return cases;
    }

    /** @return 目标 UT 单一算法输入捕获服务。 */
    public AlgorithmInputApplicationService algorithmInputs() {
        if (algorithmInputs == null) {
            throw new IllegalStateException("ControlPlaneServices has no target Project Adapter");
        }
        return algorithmInputs;
    }

    /** @return 瀹屾暣瑁呴厤涓嬬殑 Run 鐢ㄤ緥锛涘熀纭€鎺у埗闈㈣閰嶄笉鎻愪緵锛孧aven 鍙湪鎵ц鏃舵姤鍛婄己澶?*/
    public RunApplicationService runs() {
        if (runs == null) {
            throw new IllegalStateException("ControlPlaneServices has no configured Adapter");
        }
        return runs;
    }

    /** @return 闈欐€佸垎鏋愪笌 CodePath 璁″垝鐢ㄤ緥锛涘熀纭€鎺у埗闈㈣閰嶄笉鎻愪緵 */
    public StaticAnalysisApplicationService staticAnalysis() {
        if (staticAnalysis == null) {
            throw new IllegalStateException("ControlPlaneServices has no configured Adapter");
        }
        return staticAnalysis;
    }

    /** @return CodePath/JDWP 鍔ㄦ€侀噰闆嗙敤渚嬶紱鍩虹鎺у埗闈㈣閰嶄笉鎻愪緵 */
    public CollectionApplicationService collections() {
        if (collections == null) {
            throw new IllegalStateException("ControlPlaneServices has no configured Adapter");
        }
        return collections;
    }

    /** @return JDWP 鍔ㄦ€侀噰闆嗙敤渚嬶紱鏈湪缁勫悎鏍归厤缃椂鎷掔粷璁块棶銆?*/
    public JdwpCollectionApplicationService jdwpCollections() {
        if (jdwpCollections == null) {
            throw new IllegalStateException("ControlPlaneServices has no configured JDWP Collector");
        }
        return jdwpCollections;
    }

    private static Path currentJavaExecutable(boolean windows) {
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toAbsolutePath().normalize();
    }
}
