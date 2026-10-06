package org.example.algorithmdebug.mcp;

import io.modelcontextprotocol.spec.McpSchema.Root;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;

/** 把 MCP roots 和传输层 requestId 收敛为 Server 冻结项目的唯一可信上下文。 */
public final class McpRequestContextResolver {
    private static final String FILE_SCHEME = "file";
    private static final String SHA_256 = "SHA-256";

    private final Path workspaceRoot;
    private final Path projectRoot;
    private final String workspaceId;

    /**
     * 创建绑定一个真实 Workspace/Project 的解析器，并立即核对持久化 ProjectRegistration。
     */
    public McpRequestContextResolver(Path workspaceRoot, Path projectRoot, String workspaceId) {
        this.workspaceRoot = checkedFrozenPath(workspaceRoot, "workspaceRoot");
        this.projectRoot = checkedFrozenPath(projectRoot, "projectRoot");
        if (workspaceId == null || workspaceId.isBlank()
                || !workspaceId.equals(workspaceId.strip())) {
            throw new IllegalArgumentException("workspaceId is invalid");
        }
        this.workspaceId = workspaceId;
    }

    /** 校验 request metadata 与客户端 roots 均指向冻结项目。 */
    public McpRequestContext resolve(Map<String, Object> metadata, List<Root> roots) {
        McpRequestId requestId = requestId(metadata);
        Path realWorkspace = requireDirectRealPath(workspaceRoot, "workspaceRoot");
        Path realProject = requireDirectRealPath(projectRoot, "projectRoot");
        requireBoundRoot(roots, realProject);
        ProjectId registeredProject = resolveRegisteredProject(realWorkspace, realProject);
        return new McpRequestContext(
                workspaceId, realWorkspace, registeredProject, realProject, requestId,
                invocationToken(requestId));
    }

    /** 从保留 metadata 字段恢复保留 wire 类型的 requestId。 */
    McpRequestId requestId(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey(McpProtocolInputStream.REQUEST_ID_META_KEY)) {
            throw new IllegalArgumentException("MCP request correlation is missing");
        }
        return McpRequestId.fromWire(metadata.get(McpProtocolInputStream.REQUEST_ID_META_KEY));
    }

    /** @return Server 冻结且已注册的 Project ID */
    ProjectId projectId() {
        Path realWorkspace = requireDirectRealPath(workspaceRoot, "workspaceRoot");
        Path realProject = requireDirectRealPath(projectRoot, "projectRoot");
        return resolveRegisteredProject(realWorkspace, realProject);
    }

    /** @return Server 冻结的 Workspace ID */
    String workspaceId() {
        return workspaceId;
    }

    private ProjectId resolveRegisteredProject(Path realWorkspace, Path realProject) {
        ProjectRegistrationRepository repository = new ProjectRegistrationRepository(
                new BoundedDocumentMapper(), new AtomicDocumentWriter());
        List<ProjectRegistration> matches = repository.findAll(WorkspaceLayout.of(realWorkspace))
                .stream()
                .filter(registration -> registeredRoot(registration).equals(realProject))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "Frozen project root must match exactly one ProjectRegistration");
        }
        return matches.getFirst().projectId();
    }

    private Path registeredRoot(ProjectRegistration registration) {
        return requireDirectRealPath(Path.of(registration.moduleRoot()), "registered moduleRoot");
    }

    private void requireBoundRoot(List<Root> roots, Path realProject) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("MCP roots must include the frozen project");
        }
        int matches = 0;
        for (Root root : roots) {
            Path candidate = rootPath(root);
            if (candidate.equals(realProject)) {
                matches++;
            }
        }
        if (matches != 1) {
            throw new IllegalArgumentException(
                    "MCP roots must contain the frozen registered project exactly once");
        }
    }

    private static Path rootPath(Root root) {
        if (root == null || root.uri() == null) {
            throw new IllegalArgumentException("MCP root is invalid");
        }
        try {
            URI uri = new URI(root.uri());
            if (!FILE_SCHEME.equalsIgnoreCase(uri.getScheme())
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("MCP root must be a local file URI");
            }
            return requireDirectRealPath(Path.of(uri), "MCP root");
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("MCP root URI is invalid", failure);
        }
    }

    private String invocationToken(McpRequestId requestId) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        update(digest, workspaceId);
        update(digest, requestId instanceof McpRequestId.Text ? "text" : "integer");
        update(digest, String.valueOf(requestId.wireValue()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static Path requireDirectRealPath(Path path, String label) {
        if (path == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        try {
            Path direct = path.toAbsolutePath().normalize();
            if (!Files.isDirectory(direct, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(direct)) {
                throw new IllegalArgumentException(label + " must be a direct directory");
            }
            Path real = direct.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!direct.equals(real)) {
                throw new IllegalArgumentException(label + " must not use a filesystem alias");
            }
            return real;
        } catch (java.io.IOException | SecurityException failure) {
            throw new IllegalArgumentException(label + " cannot be resolved", failure);
        }
    }

    private static Path checkedFrozenPath(Path path, String label) {
        if (path == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            throw new IllegalArgumentException(label + " must not be a filesystem root");
        }
        return normalized;
    }
}
