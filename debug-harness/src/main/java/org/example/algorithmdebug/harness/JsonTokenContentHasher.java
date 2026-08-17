package org.example.algorithmdebug.harness;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 对 JSON Token 类型和值计算不受格式空白影响的流式 SHA-256。 */
public final class JsonTokenContentHasher {

    private static final byte[] PROFILE = "JSON_TOKEN_SHA256_V1".getBytes(StandardCharsets.UTF_8);
    private final JsonFactory jsonFactory;

    /** 使用 Jackson 默认严格 JSON 工厂创建 Hasher。 */
    public JsonTokenContentHasher() {
        this(new JsonFactory());
    }

    JsonTokenContentHasher(JsonFactory jsonFactory) {
        if (jsonFactory == null) {
            throw new IllegalArgumentException("jsonFactory 不能为空");
        }
        this.jsonFactory = jsonFactory;
    }

    /**
     * 计算一个且仅一个完整 JSON 根值的 Token 内容指纹。
     *
     * @param jsonPath 已捕获的 JSON 文件
     * @return 64 位小写 SHA-256
     * @throws HarnessException 文件无法读取、JSON 非法、多根值或当前 JVM 不支持 SHA-256
     */
    public String sha256(Path jsonPath) throws HarnessException {
        if (jsonPath == null) {
            throw new IllegalArgumentException("jsonPath 不能为空");
        }
        MessageDigest digest = digest();
        update(digest, PROFILE);
        int rootValues = 0;
        int depth = 0;
        try (JsonParser parser = jsonFactory.createParser(jsonPath.toFile())) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (depth == 0 && token != JsonToken.FIELD_NAME
                        && token != JsonToken.END_OBJECT && token != JsonToken.END_ARRAY) {
                    rootValues++;
                }
                update(digest, token.name());
                if (token == JsonToken.FIELD_NAME
                        || token == JsonToken.VALUE_STRING
                        || token.isNumeric()) {
                    update(digest, parser.getText());
                }
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                }
                if (depth < 0) {
                    throw invalidJson("JSON 容器结束 Token 不匹配", null);
                }
            }
            if (rootValues != 1 || depth != 0) {
                throw invalidJson("JSON 必须且只能包含一个完整根值", null);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (HarnessException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw invalidJson("无法读取并计算 JSON Token 内容指纹", failure);
        }
    }

    private static MessageDigest digest() throws HarnessException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw invalidJson("当前 JVM 不支持 SHA-256", failure);
        }
    }

    private static void update(MessageDigest digest, String value) {
        update(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void update(MessageDigest digest, byte[] bytes) {
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static HarnessException invalidJson(String message, Throwable cause) {
        if (cause == null) {
            return new HarnessException("GANTT_JSON_TOKEN_HASH_FAILED", message);
        }
        return new HarnessException("GANTT_JSON_TOKEN_HASH_FAILED", message, cause);
    }
}
