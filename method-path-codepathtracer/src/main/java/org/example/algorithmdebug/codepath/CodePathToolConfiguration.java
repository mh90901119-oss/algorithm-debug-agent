package org.example.algorithmdebug.codepath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** 本机已锁定 CodePath fat JAR 与 Java 启动信息。 */
public record CodePathToolConfiguration(
        Path javaExecutable,
        Path launcherJar,
        String expectedSha256,
        String toolVersion,
        String mainClass) {

    /** 校验配置字段；文件内容在每次执行前重新校验。 */
    public CodePathToolConfiguration {
        javaExecutable = Objects.requireNonNull(javaExecutable, "javaExecutable");
        launcherJar = Objects.requireNonNull(launcherJar, "launcherJar").toAbsolutePath().normalize();
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("expectedSha256 非法");
        }
        expectedSha256 = expectedSha256.toLowerCase(Locale.ROOT);
        if (toolVersion == null || toolVersion.isBlank() || toolVersion.length() > 256
                || mainClass == null || !mainClass.matches(
                "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            throw new IllegalArgumentException("CodePath 工具版本或主类非法");
        }
    }

    /** 执行前验证 JAR 是普通文件且内容与锁文件一致。 */
    public void verifyTool() throws CodePathAdapterException {
        if (!Files.isRegularFile(launcherJar, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(launcherJar)) {
            throw new CodePathAdapterException(
                    "CODEPATH_TOOL_MISSING", "CodePath launcher JAR 不存在", null);
        }
        try {
            String actual = sha256(launcherJar);
            if (!expectedSha256.equals(actual)) {
                throw new CodePathAdapterException(
                        "CODEPATH_TOOL_HASH_MISMATCH", "CodePath launcher JAR Hash 不匹配", null);
            }
        } catch (IOException failure) {
            throw new CodePathAdapterException(
                    "CODEPATH_TOOL_UNREADABLE", "无法读取 CodePath launcher JAR", failure);
        }
    }

    /** 计算文件的流式 SHA-256。 */
    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
    }
}
