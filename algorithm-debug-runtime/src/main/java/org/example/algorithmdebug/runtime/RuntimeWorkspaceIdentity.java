package org.example.algorithmdebug.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 为所有宿主入口派生相同、不可逆且不泄漏本地路径的 Workspace 标识。 */
public final class RuntimeWorkspaceIdentity {
    private static final String WORKSPACE_ID_PREFIX = "workspace-";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String DERIVATION_FAILURE = "RUNTIME_WORKSPACE_ID_DERIVATION_FAILED";

    private RuntimeWorkspaceIdentity() {
    }

    /**
     * 对真实规范路径求 SHA-256，但不把该路径返回给调用方或替换 Runtime 的权限检查路径。
     *
     * @param workspace 受信任宿主配置中的 Workspace 路径
     * @return 带固定前缀的稳定不透明标识
     * @throws RuntimeBootstrapException 路径无法规范化
     */
    public static String derive(Path workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace must not be null");
        }
        final Path canonical;
        try {
            canonical = workspace.toFile().getCanonicalFile().toPath();
        } catch (IOException failure) {
            throw new RuntimeBootstrapException(
                    DERIVATION_FAILURE, "Workspace identity could not be derived", failure);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8));
            return WORKSPACE_ID_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(HASH_ALGORITHM + " is unavailable", impossible);
        }
    }
}
