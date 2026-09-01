package org.example.algorithmdebug.casecore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.algorithmdebug.contracts.CaseArtifactRegistration;
import org.example.algorithmdebug.contracts.CaseAuditIssue;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseWorkspaceAudit;
import org.example.algorithmdebug.contracts.ProjectId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 只读审计 Case 控制文件、Artifact 完整性、交互日志和空目录。 */
public final class CaseWorkspaceAuditor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BoundedDocumentMapper mapper = new BoundedDocumentMapper();
    private final ArtifactIntegrityChecker integrity = new ArtifactIntegrityChecker();

    public CaseWorkspaceAudit audit(Path workspace, ProjectId projectId, CaseId caseId) {
        Path root = WorkspaceLayout.of(workspace).projectCases(projectId).resolve(caseId.value()).normalize();
        ArrayList<CaseAuditIssue> issues = new ArrayList<>();
        ArrayList<String> expected = new ArrayList<>();
        ArrayList<String> actual = new ArrayList<>();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            issues.add(issue("CASE_ROOT_MISSING", "CASE", caseId.value(), "", "", "Case Workspace directory does not exist"));
            return result(caseId, 0, expected, actual, issues);
        }
        List<Path> entries;
        try (Stream<Path> stream = Files.walk(root)) { entries = stream.sorted().toList(); }
        catch (IOException | SecurityException failure) {
            throw new WorkspaceException("CASE_AUDIT_READ_FAILED", "Unable to enumerate Case Workspace", failure);
        }
        entries.stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .map(path -> relative(root, path)).forEach(actual::add);
        require(root, caseId, "case.json", "CASE_MANIFEST", expected, issues);
        checkScopes(root.resolve("contexts"), "context.json", "CONTEXT", caseId, expected, issues);
        checkScopes(root.resolve("analyses"), "analysis-request.json", "ANALYSIS", caseId, expected, issues);
        checkScopes(root.resolve("runs"), "run-request.json", "RUN", caseId, expected, issues);
        checkScopes(root.resolve("runs"), "run-outcome.json", "RUN", caseId, expected, issues);
        checkLog(root.resolve("interaction.jsonl"), caseId, issues);
        checkJavaLogs(root.resolve("logs"), caseId, issues);
        int checked = checkArtifacts(root, caseId, expected, issues);
        actual.stream().filter(CaseWorkspaceAuditor::isKnownControlFile).forEach(expected::add);
        Set<String> tracked = new HashSet<>(expected);
        actual.stream().filter(path -> !tracked.contains(path)).forEach(path ->
                issues.add(issue("UNTRACKED_FILE", "CASE", caseId.value(), "", path,
                        "Case Workspace file is neither a control file nor a registered Artifact")));
        for (Path directory : entries.stream().filter(path -> !path.equals(root))
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
            try (Stream<Path> children = Files.list(directory)) {
                if (children.findAny().isEmpty()) issues.add(issue("EMPTY_DIRECTORY", "CASE", caseId.value(), "",
                        relative(root, directory), "Case Workspace contains an empty directory"));
            } catch (IOException | SecurityException failure) {
                issues.add(issue("DIRECTORY_READ_FAILED", "CASE", caseId.value(), "", relative(root, directory),
                        "Unable to inspect Case Workspace directory"));
            }
        }
        expected = new ArrayList<>(new LinkedHashSet<>(expected));
        expected.sort(String::compareTo); actual.sort(String::compareTo);
        issues.sort(Comparator.comparing(CaseAuditIssue::code).thenComparing(CaseAuditIssue::relativePath));
        return result(caseId, checked, expected, actual, issues);
    }

    private int checkArtifacts(Path root, CaseId caseId, List<String> expected, List<CaseAuditIssue> issues) {
        Path directory = root.resolve("artifacts");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        List<Path> documents;
        try (Stream<Path> stream = Files.list(directory)) {
            documents = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException | SecurityException failure) {
            issues.add(issue("ARTIFACT_REGISTRY_READ_FAILED", "CASE", caseId.value(), "", "artifacts",
                    "Unable to read Artifact registrations")); return 0;
        }
        int checked = 0;
        for (Path document : documents) {
            try {
                CaseArtifactRegistration registration = mapper.readJson(document, CaseArtifactRegistration.class);
                if (!registration.caseId().equals(caseId)) {
                    issues.add(issue("ARTIFACT_CASE_MISMATCH", "ARTIFACT", document.getFileName().toString(), "",
                            relative(root, document), "Artifact registration belongs to another Case")); continue;
                }
                Path artifact = root.resolve(registration.artifact().relativePath()).normalize();
                expected.add(registration.artifact().relativePath()); checked++;
                ArtifactIntegrityChecker.Status status = integrity.verify(registration.artifact(), artifact).status();
                if (status != ArtifactIntegrityChecker.Status.VALID) {
                    issues.add(issue("ARTIFACT_" + status, "ARTIFACT", registration.artifact().artifactId(),
                            registration.artifact().artifactType(), registration.artifact().relativePath(),
                            "Artifact does not match its immutable registration: " + status));
                }
            } catch (RuntimeException failure) {
                issues.add(issue("ARTIFACT_REGISTRATION_INVALID", "ARTIFACT", document.getFileName().toString(), "",
                        relative(root, document), "Artifact registration is invalid"));
            }
        }
        return checked;
    }

    private static void checkScopes(Path directory, String name, String scope, CaseId caseId,
            List<String> expected, List<CaseAuditIssue> issues) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path child : stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String relative = directory.getFileName() + "/" + child.getFileName() + "/" + name;
                expected.add(relative);
                if (!Files.isRegularFile(child.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                    issues.add(issue(scope + "_CONTROL_FILE_MISSING", scope, child.getFileName().toString(), name,
                            relative, scope + " control file is missing"));
                }
            }
        } catch (IOException | SecurityException failure) {
            issues.add(issue(scope + "_DIRECTORY_READ_FAILED", "CASE", caseId.value(), "",
                    directory.getFileName().toString(), "Unable to inspect " + scope + " directories"));
        }
    }

    private static void checkLog(Path path, CaseId caseId, List<CaseAuditIssue> issues) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<String> lines = Files.lines(path)) {
            int[] line = {0};
            lines.forEach(value -> { line[0]++; try {
                if (value.isBlank() || !JSON.readTree(value).isObject()) throw new IOException("invalid");
            } catch (IOException failure) {
                issues.add(issue("INTERACTION_LOG_INVALID", "CASE", caseId.value(), "CASE_INTERACTION_LOG",
                        "interaction.jsonl", "Interaction log contains invalid JSON at line " + line[0]));
            }});
        } catch (IOException | SecurityException failure) {
            issues.add(issue("INTERACTION_LOG_READ_FAILED", "CASE", caseId.value(), "CASE_INTERACTION_LOG",
                    "interaction.jsonl", "Unable to read Case interaction log"));
        }
    }

    private static void checkJavaLogs(
            Path directory, CaseId caseId, List<CaseAuditIssue> issues) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> Files.isRegularFile(
                    path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = file.getFileName().toString();
                if (!name.matches("agent-\\d{4}-\\d{2}-\\d{2}\\.log")) continue;
                if (Files.size(file) == 0) {
                    issues.add(issue("JAVA_EXECUTION_LOG_EMPTY", "CASE", caseId.value(),
                            "JAVA_EXECUTION_LOG", "logs/" + name,
                            "Java execution log must not be empty"));
                }
            }
        } catch (IOException | SecurityException failure) {
            issues.add(issue("JAVA_EXECUTION_LOG_READ_FAILED", "CASE", caseId.value(),
                    "JAVA_EXECUTION_LOG", "logs", "Unable to inspect Java execution logs"));
        }
    }

    private static void require(Path root, CaseId caseId, String path, String type,
            List<String> expected, List<CaseAuditIssue> issues) {
        expected.add(path);
        if (!Files.isRegularFile(root.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
            issues.add(issue(type + "_MISSING", "CASE", caseId.value(), type, path, "Required Case file is missing"));
        }
    }

    private static CaseWorkspaceAudit result(CaseId id, int checked, List<String> expected,
            List<String> actual, List<CaseAuditIssue> issues) {
        return new CaseWorkspaceAudit("1.0", id, issues.isEmpty(), checked, expected, actual, issues);
    }

    private static boolean isKnownControlFile(String path) {
        return path.equals("case.json")
                || path.equals("interaction.jsonl")
                || path.matches("logs/agent-\\d{4}-\\d{2}-\\d{2}\\.log")
                || path.matches("contexts/[^/]+/(context|reproduction)\\.json")
                || path.matches("analyses/[^/]+/(analysis-request|analysis-result|method-catalog)\\.json")
                || path.matches("analyses/[^/]+/input/input-analysis\\.json")
                || path.matches("analyses/[^/]+/plans/[^/]+\\.json")
                || path.matches("runs/[^/]+/(run-request|run-outcome|run-result-fingerprint)\\.json")
                || path.matches("collections/[^/]+/(collection-request|manifest|collection-summary)\\.json")
                || path.matches("collections/[^/]+/collector-plan\\.json")
                || path.matches("collections/[^/]+/logs/(stdout|stderr)\\.log")
                || path.matches("collections/[^/]+/logs/(target|collector)-(stdout|stderr)\\.log")
                || path.matches("collections/[^/]+/validation/(baseline-check|post-processing-failure)\\.json")
                || path.matches("evidence/[^/]+/(evidence-build-request|evidence-bundle|sufficiency-evaluation)\\.json")
                || path.matches("artifacts/[^/]+\\.json");
    }
    private static CaseAuditIssue issue(String code, String scope, String id, String type, String path, String message) {
        return new CaseAuditIssue(code, scope, id, type, path, message);
    }
    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
}
