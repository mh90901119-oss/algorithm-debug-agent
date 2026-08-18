package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ClasspathWorkspaceTemplateProvider;
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
import org.example.algorithmdebug.methodpath.MethodPathCollector;
import org.example.algorithmdebug.methodpath.MethodPathCollectionException;
import org.example.algorithmdebug.methodpath.TargetClasspathResolver;
import org.example.algorithmdebug.contracts.DoctorCheck;
import org.example.algorithmdebug.contracts.DoctorStatus;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * 在 Core 内装配 Workspace 控制面服务，避免 CLI 直接依赖 Case 实现类型。
 */
public final class ControlPlaneServices {

    private final WorkspaceApplicationService workspace;
    private final ProjectApplicationService project;
    private final DoctorApplicationService doctor;
    private final CaseApplicationService cases;
    private final RunApplicationService runs;
    private final StaticAnalysisApplicationService staticAnalysis;
    private final CollectionApplicationService collections;
    private final JdwpCollectionApplicationService jdwpCollections;

    private ControlPlaneServices(
            WorkspaceApplicationService workspace,
            ProjectApplicationService project,
            DoctorApplicationService doctor,
            CaseApplicationService cases,
            RunApplicationService runs,
            StaticAnalysisApplicationService staticAnalysis,
            CollectionApplicationService collections,
            JdwpCollectionApplicationService jdwpCollections) {
        this.workspace = workspace;
        this.project = project;
        this.doctor = doctor;
        this.cases = cases;
        this.runs = runs;
        this.staticAnalysis = staticAnalysis;
        this.collections = collections;
        this.jdwpCollections = jdwpCollections;
    }

    /**
     * 使用本机环境和显式注入的时间/平台信息装配默认离线控制面。
     *
     * @param clock Workspace 和项目登记时钟
     * @param javaFeatureSupplier Java feature 提供器
     * @param environment 环境变量快照
     * @param pathSeparator PATH 分隔符
     * @param windows 是否为 Windows
     * @return 完整 Core 服务集合
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
     * 装配包含 Case/Run 用例的完整离线服务集合；Adapter 由 CLI 组合根显式注入。
     */
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter<?>> adapters,
            Path mavenExecutable) {
        return create(
                clock, javaFeatureSupplier, environment, pathSeparator, windows,
                adapters, Optional.ofNullable(mavenExecutable));
    }

    /** 装配完整服务集合；Maven 缺失不会阻止只读/Case 命令。 */
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter<?>> adapters,
            Optional<Path> mavenExecutable) {
        if (adapters == null || mavenExecutable == null) {
            throw new IllegalArgumentException("完整控制面必须提供 adapters 和 mavenExecutable");
        }
        MethodPathCollector unavailableCollector = request -> {
            throw new MethodPathCollectionException(
                    "CODEPATH_TOOL_NOT_CONFIGURED", "CodePath launcher 未配置", null);
        };
        TargetClasspathResolver unavailableClasspath = (maven, module, output) -> {
            throw new MethodPathCollectionException(
                    "CODEPATH_TOOL_NOT_CONFIGURED", "CodePath classpath resolver 未配置", null);
        };
        return create(clock, javaFeatureSupplier, environment, pathSeparator, windows, adapters,
                mavenExecutable, unavailableCollector, unavailableClasspath,
                () -> new DoctorCheck(
                        "codepath", DoctorStatus.FAIL, "CODEPATH_TOOL_NOT_CONFIGURED",
                        "CodePath launcher 未配置"));
    }

    /** CLI 组合根显式注入 Collector 实现；Core 仅依赖稳定 SPI。 */
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter<?>> adapters,
            Optional<Path> mavenExecutable,
            MethodPathCollector collector,
            TargetClasspathResolver classpathResolver) {
        return create(clock, javaFeatureSupplier, environment, pathSeparator, windows,
                adapters, mavenExecutable, collector, classpathResolver,
                () -> new DoctorCheck(
                        "codepath", DoctorStatus.PASS, "CODEPATH_TOOL_INJECTED",
                        "CodePath Collector 已由组合根注入"));
    }

    /** CLI 组合根同时注入 Collector 与其确定性 Doctor 探针。 */
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter<?>> adapters,
            Optional<Path> mavenExecutable,
            MethodPathCollector collector,
            TargetClasspathResolver classpathResolver,
            ToolDoctorProbe toolProbe) {
        if (adapters == null || mavenExecutable == null || collector == null
                || classpathResolver == null || toolProbe == null) {
            throw new IllegalArgumentException("完整控制面组合根依赖不能为空");
        }
        return createInternal(clock, javaFeatureSupplier, environment, pathSeparator, windows,
                List.copyOf(adapters), mavenExecutable, collector, classpathResolver,
                null, null, null, List.of(toolProbe));
    }

    /** CLI 组合根同时注入 CodePath 与 JDWP 工具边界及其 Doctor 探针。 */
    public static ControlPlaneServices create(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter<?>> adapters,
            Optional<Path> mavenExecutable,
            MethodPathCollector collector,
            TargetClasspathResolver classpathResolver,
            JdwpToolConfiguration jdwpTool,
            JdwpCollectionExecutor jdwpExecutor,
            JdwpPortProvider jdwpPorts,
            ToolDoctorProbe codePathProbe,
            ToolDoctorProbe jdwpProbe) {
        if (jdwpTool == null || jdwpExecutor == null || jdwpPorts == null
                || codePathProbe == null || jdwpProbe == null) {
            throw new IllegalArgumentException("JDWP 控制面组合根依赖不能为空");
        }
        return createInternal(clock, javaFeatureSupplier, environment, pathSeparator, windows,
                List.copyOf(adapters), mavenExecutable, collector, classpathResolver,
                jdwpTool, jdwpExecutor, jdwpPorts, List.of(codePathProbe, jdwpProbe));
    }

    private static ControlPlaneServices createInternal(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter<?>> adapters,
            Optional<Path> mavenExecutable,
            MethodPathCollector methodPathCollector,
            TargetClasspathResolver classpathResolver,
            JdwpToolConfiguration jdwpTool,
            JdwpCollectionExecutor jdwpExecutor,
            JdwpPortProvider jdwpPorts,
            List<ToolDoctorProbe> toolProbes) {
        if (clock == null || javaFeatureSupplier == null || environment == null
                || pathSeparator == null || pathSeparator.isEmpty() || toolProbes == null) {
            throw new IllegalArgumentException("控制面装配参数不能为空");
        }
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        WorkspaceManifestRepository manifestRepository = new WorkspaceManifestRepository(mapper, writer);
        WorkspaceInitializer initializer = new WorkspaceInitializer(
                manifestRepository,
                writer,
                new ClasspathWorkspaceTemplateProvider(),
                clock);
        ProjectRegistry registry = new ProjectRegistry(
                manifestRepository,
                new ProjectRegistrationRepository(mapper, writer),
                new RepositoryRootLocator(),
                new ProjectIdGenerator(),
                clock);
        MavenExecutableLocator mavenLocator = new MavenExecutableLocator(
                environment, pathSeparator, windows);
        CaseApplicationService cases = null;
        RunApplicationService runs = null;
        StaticAnalysisApplicationService staticAnalysis = null;
        CollectionApplicationService collections = null;
        JdwpCollectionApplicationService jdwpCollections = null;
        if (adapters != null) {
            AdapterCatalog catalog = new AdapterCatalog(adapters);
            OpaqueIdGenerator ids = new OpaqueIdGenerator();
            cases = new CaseApplicationService(
                    new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                    catalog, ids, clock);
            runs = new RunApplicationService(
                    new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                    catalog, ids, clock, new MavenTestExecutor(),
                    new RunArtifactArchiver(), mavenExecutable);
            staticAnalysis = new StaticAnalysisApplicationService(
                    new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                    new JavaSourceCallGraphAnalyzer(), new CodePathPlanCompiler(), clock);
            collections = new CollectionApplicationService(
                    new ProjectRegistrationRepository(mapper, writer), mapper, writer, catalog,
                    ids, clock, mavenExecutable, currentJavaExecutable(windows),
                    methodPathCollector, classpathResolver);
            if (jdwpTool != null) {
                jdwpCollections = new JdwpCollectionApplicationService(
                        new ProjectRegistrationRepository(mapper, writer), mapper, writer, catalog,
                        ids, clock, mavenExecutable, currentJavaExecutable(windows), jdwpTool,
                        jdwpExecutor, jdwpPorts);
            }
        }
        return new ControlPlaneServices(
                new WorkspaceApplicationService(initializer),
                new ProjectApplicationService(registry),
                new DoctorApplicationService(
                        javaFeatureSupplier, mavenLocator, manifestRepository, toolProbes),
                cases,
                runs,
                staticAnalysis,
                collections,
                jdwpCollections);
    }

    /** @return Workspace 初始化服务 */
    public WorkspaceApplicationService workspace() {
        return workspace;
    }

    /** @return 项目注册服务 */
    public ProjectApplicationService project() {
        return project;
    }

    /** @return 环境诊断服务 */
    public DoctorApplicationService doctor() {
        return doctor;
    }

    /** @return 完整装配下的 Case 用例；基础控制面装配不提供 */
    public CaseApplicationService cases() {
        if (cases == null) {
            throw new IllegalStateException("当前 ControlPlaneServices 未装配 Adapter");
        }
        return cases;
    }

    /** @return 完整装配下的 Run 用例；基础控制面装配不提供，Maven 可在执行时报告缺失 */
    public RunApplicationService runs() {
        if (runs == null) {
            throw new IllegalStateException("当前 ControlPlaneServices 未装配 Adapter");
        }
        return runs;
    }

    /** @return 静态分析与 CodePath 计划用例；基础控制面装配不提供 */
    public StaticAnalysisApplicationService staticAnalysis() {
        if (staticAnalysis == null) {
            throw new IllegalStateException("当前 ControlPlaneServices 未装配 Adapter");
        }
        return staticAnalysis;
    }

    /** @return CodePath/JDWP 动态采集用例；基础控制面装配不提供 */
    public CollectionApplicationService collections() {
        if (collections == null) {
            throw new IllegalStateException("当前 ControlPlaneServices 未装配 Adapter");
        }
        return collections;
    }

    /** @return JDWP 动态采集用例；未在组合根配置时拒绝访问。 */
    public JdwpCollectionApplicationService jdwpCollections() {
        if (jdwpCollections == null) {
            throw new IllegalStateException("当前 ControlPlaneServices 未装配 JDWP Collector");
        }
        return jdwpCollections;
    }

    private static Path currentJavaExecutable(boolean windows) {
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toAbsolutePath().normalize();
    }
}
