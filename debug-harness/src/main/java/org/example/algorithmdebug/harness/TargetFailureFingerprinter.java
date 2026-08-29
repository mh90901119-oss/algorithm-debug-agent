package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.TargetFailureDiagnostic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 对已结构化的目标失败事实计算不依赖源码行号的稳定 SHA-256。 */
public final class TargetFailureFingerprinter {

    private static final String PROFILE = "TARGET_FAILURE_SHA256_V1";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SOURCE_LINE = Pattern.compile("\\.java:[0-9]+\\)");

    /**
     * 计算目标失败指纹。
     *
     * @param diagnostic Surefire 等确定性来源产生的目标失败事实
     * @return 64 位小写 SHA-256
     * @throws HarnessException 当前 JVM 不支持 SHA-256
     */
    public String sha256(TargetFailureDiagnostic diagnostic) throws HarnessException {
        if (diagnostic == null) {
            throw new IllegalArgumentException("diagnostic must not be null");
        }
        MessageDigest digest = digest();
        update(digest, PROFILE);
        update(digest, diagnostic.category().name());
        update(digest, normalizeText(diagnostic.exceptionClass()));
        update(digest, normalizeText(diagnostic.normalizedMessage()));
        update(digest, normalizeText(diagnostic.cause()));
        update(digest, normalizeBusinessFrame(diagnostic.stableBusinessFrame()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() throws HarnessException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new HarnessException(
                    "TARGET_FAILURE_FINGERPRINT_FAILED",
                    "current JVM does not support SHA-256",
                    failure);
        }
    }

    private static String normalizeBusinessFrame(String value) {
        return SOURCE_LINE.matcher(normalizeText(value)).replaceAll(".java:#)");
    }

    private static String normalizeText(String value) {
        return WHITESPACE.matcher(value.strip()).replaceAll(" ");
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
