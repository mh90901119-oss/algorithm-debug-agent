package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ClasspathWorkspaceTemplateProvider;
import org.example.algorithmdebug.casecore.ContextSnapshotBuilder;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.ProjectIdGenerator;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.ProjectRegistry;
import org.example.algorithmdebug.casecore.RepositoryRootLocator;
import org.example.algorithmdebug.casecore.WorkspaceInitializer;
import org.example.algorithmdebug.casecore.WorkspaceManifestRepository;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.harness.MavenTestExecutor;

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

    private ControlPlaneServices(
            WorkspaceApplicationService workspace,
            ProjectApplicationService project,
            DoctorApplicationService doctor,
            CaseApplicationService cases,
            RunApplicationService runs) {
        this.workspace = workspace;
        this.project = project;
        this.doctor = doctor;
        this.cases = cases;
        this.runs = runs;
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
                clock, javaFeatureSupplier, environment, pathSeparator, windows, null, null);
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
        return createInternal(
                clock, javaFeatureSupplier, environment, pathSeparator, windows,
                List.copyOf(adapters), mavenExecutable);
    }

    private static ControlPlaneServices createInternal(
            Clock clock,
            IntSupplier javaFeatureSupplier,
            Map<String, String> environment,
            String pathSeparator,
            boolean windows,
            List<TargetProjectAdapter<?>> adapters,
            Optional<Path> mavenExecutable) {
        if (clock == null || javaFeatureSupplier == null || environment == null
                || pathSeparator == null || pathSeparator.isEmpty()) {
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
        if (adapters != null) {
            AdapterCatalog catalog = new AdapterCatalog(adapters);
            OpaqueIdGenerator ids = new OpaqueIdGenerator();
            cases = new CaseApplicationService(
                    new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                    catalog, new ContextSnapshotBuilder(), ids, clock,
                    () -> System.getProperty("java.version", "UNAVAILABLE"));
            runs = new RunApplicationService(
                    new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                    catalog, ids, clock, new MavenTestExecutor(),
                    new RunArtifactArchiver(), mavenExecutable);
        }
        return new ControlPlaneServices(
                new WorkspaceApplicationService(initializer),
                new ProjectApplicationService(registry),
                new DoctorApplicationService(javaFeatureSupplier, mavenLocator, manifestRepository),
                cases,
                runs);
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
}
