package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ClasspathWorkspaceTemplateProvider;
import org.example.algorithmdebug.casecore.ProjectIdGenerator;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.ProjectRegistry;
import org.example.algorithmdebug.casecore.RepositoryRootLocator;
import org.example.algorithmdebug.casecore.WorkspaceInitializer;
import org.example.algorithmdebug.casecore.WorkspaceManifestRepository;

import java.time.Clock;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * 在 Core 内装配 Workspace 控制面服务，避免 CLI 直接依赖 Case 实现类型。
 */
public final class ControlPlaneServices {

    private final WorkspaceApplicationService workspace;
    private final ProjectApplicationService project;
    private final DoctorApplicationService doctor;

    private ControlPlaneServices(
            WorkspaceApplicationService workspace,
            ProjectApplicationService project,
            DoctorApplicationService doctor) {
        this.workspace = workspace;
        this.project = project;
        this.doctor = doctor;
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
        return new ControlPlaneServices(
                new WorkspaceApplicationService(initializer),
                new ProjectApplicationService(registry),
                new DoctorApplicationService(javaFeatureSupplier, mavenLocator, manifestRepository));
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
}
