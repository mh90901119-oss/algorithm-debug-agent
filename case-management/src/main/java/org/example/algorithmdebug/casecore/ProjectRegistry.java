package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.ProjectRegistrationResult;
import org.example.algorithmdebug.contracts.SchemaVersions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** 灏嗙嫭绔?Maven 绠楁硶妯″潡鍙鐧昏鍒板閮?Agent Workspace銆?*/
public final class ProjectRegistry {

    private static final String POM_FILE_NAME = "pom.xml";

    private final WorkspaceManifestRepository manifestRepository;
    private final ProjectRegistrationRepository registrationRepository;
    private final RepositoryRootLocator repositoryRootLocator;
    private final ProjectIdGenerator projectIdGenerator;
    private final Clock clock;

    /**
     * 鍒涘缓椤圭洰娉ㄥ唽鍣ㄣ€?     *
     * @param manifestRepository Workspace Manifest 浠撳偍
     * @param registrationRepository 椤圭洰鐧昏浠撳偍
     * @param repositoryRootLocator Git 浠撳簱鏍瑰畾浣嶅櫒
     * @param projectIdGenerator 榛樿 ProjectId 鐢熸垚鍣?     * @param clock 鐧昏鏃堕棿鏃堕挓
     */
    public ProjectRegistry(
            WorkspaceManifestRepository manifestRepository,
            ProjectRegistrationRepository registrationRepository,
            RepositoryRootLocator repositoryRootLocator,
            ProjectIdGenerator projectIdGenerator,
            Clock clock) {
        if (manifestRepository == null || registrationRepository == null
                || repositoryRootLocator == null || projectIdGenerator == null || clock == null) {
            throw new IllegalArgumentException("ProjectRegistry dependencies must not be null");
        }
        this.manifestRepository = manifestRepository;
        this.registrationRepository = registrationRepository;
        this.repositoryRootLocator = repositoryRootLocator;
        this.projectIdGenerator = projectIdGenerator;
        this.clock = clock;
    }

    /**
     * 鐧昏 Maven 绠楁硶妯″潡锛涚浉鍚?ID 涓庢ā鍧楄矾寰勭殑閲嶅璋冪敤涓哄箓绛夋垚鍔熴€?     *
     * @param workspaceRoot 宸插垵濮嬪寲鐨勫閮?Workspace 鏍圭洰褰?     * @param moduleRoot 鍚嫭绔?pom.xml 鐨勭畻娉曟ā鍧楃洰褰?     * @param requestedId 鍙€夋樉寮?ProjectId
     * @return 褰撳墠鐧昏淇℃伅鍙婃湰娆℃槸鍚﹀垱寤?     */
    private ProjectRegistrationResult registerBase(
            Path workspaceRoot,
            Path moduleRoot,
            Optional<ProjectId> requestedId) {
        if (moduleRoot == null || requestedId == null) {
            throw new IllegalArgumentException("moduleRoot and requestedId must not be null");
        }
        WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
        manifestRepository.require(layout);
        Path canonicalModule = canonicalMavenModule(moduleRoot);
        Path pom = canonicalModule.resolve(POM_FILE_NAME);
        Path canonicalRepository = repositoryRootLocator.locate(canonicalModule);
        requireExternalWorkspace(layout, canonicalRepository);
        String modulePortable = portable(canonicalModule);
        ProjectId projectId = requestedId.orElseGet(() -> projectIdGenerator.generate(canonicalModule));
        layout.projectWorkspace(projectId);

        List<ProjectRegistration> registrations = registrationRepository.findAll(layout);
        Optional<ProjectRegistration> sameId = registrations.stream()
                .filter(registration -> registration.projectId().equals(projectId))
                .findFirst();
        if (sameId.isPresent()) {
            ProjectRegistration existing = sameId.orElseThrow();
            if (existing.moduleRoot().equals(modulePortable)) {
                return new ProjectRegistrationResult(existing, false);
            }
            throw new WorkspaceException(
                    "PROJECT_ID_CONFLICT", "ProjectId already refers to another algorithm module: " + projectId.value());
        }
        if (registrations.stream().anyMatch(registration -> registration.moduleRoot().equals(modulePortable))) {
            throw new WorkspaceException(
                    "PROJECT_PATH_CONFLICT", "Algorithm module path is registered with another ProjectId: " + modulePortable);
        }

        ProjectRegistration registration = new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION,
                projectId,
                canonicalModule.getFileName().toString(),
                portable(canonicalRepository),
                modulePortable,
                modulePortable,
                POM_FILE_NAME,
                "MAVEN",
                clock.instant());
        registrationRepository.create(layout, registration);
        return new ProjectRegistrationResult(registration, true);
    }

    /** 娉ㄥ唽 Maven 妯″潡锛屽苟鑷姩閲囩敤椤圭洰鏍圭洰褰曚腑鐨?Agent 閰嶇疆銆?*/
    public ProjectRegistrationResult register(
            Path workspaceRoot,
            Path moduleRoot,
            Optional<ProjectId> requestedId) {
        return register(workspaceRoot, moduleRoot, requestedId, Optional.empty());
    }

    /** 娉ㄥ唽 Maven 妯″潡锛屽苟鍙箓绛夎缃」鐩浉瀵圭殑 JSON 缁撴灉鐩綍銆?*/
    public ProjectRegistrationResult register(
            Path workspaceRoot,
            Path moduleRoot,
            Optional<ProjectId> requestedId,
            Optional<String> resultJsonDirectory) {
        if (resultJsonDirectory == null) {
            throw new IllegalArgumentException("resultJsonDirectory must not be null");
        }
        Path canonicalModule = canonicalMavenModule(moduleRoot);
        Optional<String> effectiveResultDirectory = resultJsonDirectory
                .map(ProjectRegistration::validateResultJsonDirectory);
        ProjectRegistrationResult current = registerBase(
                workspaceRoot, canonicalModule, requestedId);
        if (effectiveResultDirectory.isEmpty()
                || effectiveResultDirectory.orElseThrow().equals(
                        current.registration().resultJsonDirectory())) {
            return current;
        }
        ProjectRegistration existing = current.registration();
        ProjectRegistration updated = new ProjectRegistration(
                existing.schemaVersion(), existing.projectId(), existing.displayName(),
                existing.repositoryRoot(), existing.moduleRoot(), existing.mavenExecutionRoot(),
                existing.pomPath(), existing.buildTool(),
                effectiveResultDirectory.orElseThrow(), existing.registeredAt());
        registrationRepository.replace(WorkspaceLayout.of(workspaceRoot), updated);
        return new ProjectRegistrationResult(updated, current.created());
    }

    private static Path canonicalMavenModule(Path moduleRoot) {
        try {
            Path canonical = moduleRoot.toRealPath();
            if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceException("PROJECT_NOT_MAVEN", "Algorithm module path is not a directory: " + canonical);
            }
            Path pom = canonical.resolve(POM_FILE_NAME);
            if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceException("PROJECT_NOT_MAVEN", "Algorithm module does not contain a regular pom.xml file: " + canonical);
            }
            return canonical;
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException("PROJECT_NOT_MAVEN", "Unable to read algorithm module: " + moduleRoot, failure);
        }
    }

    private static void requireExternalWorkspace(WorkspaceLayout layout, Path repositoryRoot) {
        try {
            Path workspaceRoot = layout.root().toRealPath();
            Path canonicalRepository = repositoryRoot.toRealPath();
            if (workspaceRoot.startsWith(canonicalRepository)
                    || canonicalRepository.startsWith(workspaceRoot)) {
                throw new WorkspaceException(
                        "WORKSPACE_PATH_INVALID", "Workspace must be outside the target algorithm repository");
            }
        } catch (WorkspaceException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException(
                    "WORKSPACE_PATH_INVALID", "Unable to verify the boundary between Workspace and target algorithm repository", failure);
        }
    }

    private static String portable(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
