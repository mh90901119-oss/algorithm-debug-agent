package org.example.algorithmdebug.core;

import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.CaseDigestReader;
import org.example.algorithmdebug.casecore.CaseSessionRequest;
import org.example.algorithmdebug.casecore.CaseSessionService;
import org.example.algorithmdebug.casecore.ContextInputProbe;
import org.example.algorithmdebug.casecore.ContextSnapshotBuilder;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.CaseDigest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/** 编排已登记算法模块的 Case 打开和只读检查；打开 Case 不执行目标 UT。 */
public final class CaseApplicationService {

    private final ProjectRegistrationRepository registrations;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final AdapterCatalog adapters;
    private final ContextSnapshotBuilder snapshots;
    private final OpaqueIdGenerator ids;
    private final Clock clock;
    private final Supplier<String> javaVersion;

    /** 注入登记、归档、Adapter、快照、ID、时钟和 Java 版本端口。 */
    public CaseApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            AdapterCatalog adapters,
            ContextSnapshotBuilder snapshots,
            OpaqueIdGenerator ids,
            Clock clock,
            Supplier<String> javaVersion) {
        if (registrations == null || mapper == null || writer == null || adapters == null
                || snapshots == null || ids == null || clock == null || javaVersion == null) {
            throw new IllegalArgumentException("CaseApplicationService 依赖不能为空");
        }
        this.registrations = registrations;
        this.mapper = mapper;
        this.writer = writer;
        this.adapters = adapters;
        this.snapshots = snapshots;
        this.ids = ids;
        this.clock = clock;
        this.javaVersion = javaVersion;
    }

    /** 新建或显式续接一个 Case Analysis，不运行 Maven。 */
    public CaseOpenResult open(
            Path workspaceRoot,
            ProjectId projectId,
            TargetTest targetTest,
            String question,
            Optional<CaseId> caseId,
            Optional<String> adapterId) {
        if (targetTest == null || question == null || caseId == null || adapterId == null) {
            throw new IllegalArgumentException("Case open 参数不能为空");
        }
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            ProjectRegistration registration = requireRegistration(layout, projectId);
            Path moduleRoot = Path.of(registration.moduleRoot()).toAbsolutePath().normalize();
            AdapterCatalog.AdapterSelection selection = adapters.select(moduleRoot, adapterId);
            ContextInputProbe input = locateInput(selection, moduleRoot, targetTest);
            CaseArchiveRepository archive = archive(layout, projectId);
            return new CaseSessionService(
                    archive, new CaseDigestReader(archive), snapshots, ids, clock).open(
                    new CaseSessionRequest(
                            caseId, projectId, targetTest, question,
                            moduleRoot,
                            Path.of(registration.repositoryRoot()),
                            "UNAVAILABLE",
                            javaVersion.get(),
                            selection.adapter().descriptor().adapterId(),
                            selection.adapter().descriptor().adapterVersion(),
                            input));
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "打开 Case 失败", failure);
        }
    }

    /** 从不可变子文档重建一个 Case 的有界摘要，不执行 Maven。 */
    public CaseDigest inspect(Path workspaceRoot, ProjectId projectId, CaseId caseId) {
        if (caseId == null) {
            throw new IllegalArgumentException("caseId 不能为空");
        }
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            return new CaseDigestReader(archive).read(caseId);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "检查 Case 失败", failure);
        }
    }

    private ProjectRegistration requireRegistration(WorkspaceLayout layout, ProjectId projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        return registrations.findById(layout, projectId).orElseThrow(() ->
                new CaseRunException("PROJECT_NOT_REGISTERED", "项目尚未登记: " + projectId.value()));
    }

    private CaseArchiveRepository archive(WorkspaceLayout layout, ProjectId projectId) {
        return new CaseArchiveRepository(layout.projectCases(projectId), mapper, writer);
    }

    private static ContextInputProbe locateInput(
            AdapterCatalog.AdapterSelection selection,
            Path moduleRoot,
            TargetTest targetTest) {
        try {
            Optional<Path> located = selection.adapter().inputLocator()
                    .locate(selection.project(), targetTest);
            if (located.isEmpty()) {
                return ContextInputProbe.notApplicable();
            }
            Path input = located.orElseThrow().toAbsolutePath().normalize();
            String relative = input.startsWith(moduleRoot)
                    ? portable(moduleRoot.relativize(input))
                    : "external-input/" + opaquePath(input);
            return ContextInputProbe.present(input, relative);
        } catch (AdapterException failure) {
            if ("ADAPTER_INPUT_NOT_FOUND".equals(failure.code())) {
                return ContextInputProbe.missing(
                        "input/" + targetTest.methodName() + ".missing",
                        "Adapter reported missing input: " + failure.code());
            }
            return ContextInputProbe.unresolved(
                    "Adapter could not resolve input: " + failure.code());
        }
    }

    private static String opaquePath(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    path.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", failure);
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
