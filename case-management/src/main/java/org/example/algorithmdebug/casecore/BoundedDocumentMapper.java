package org.example.algorithmdebug.casecore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 对 Workspace YAML/JSON 控制文档执行有界、确定性的序列化和反序列化。
 *
 * <p>该组件不启用 Jackson 多态类型，不扫描类型，也不会无界读取控制文件。</p>
 */
public final class BoundedDocumentMapper {

    /** 单个控制文档允许的最大字节数：1 MiB。 */
    public static final int MAX_DOCUMENT_BYTES = 1_048_576;

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    /** 使用项目锁定的 Jackson 配置创建 Mapper。 */
    public BoundedDocumentMapper() {
        this.jsonMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module());
        this.yamlMapper = new ObjectMapper(new YAMLFactory())
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module());
    }

    /**
     * 读取并解析有界 YAML 文档。
     *
     * @param path 文档路径
     * @param type 目标契约类型
     * @param <T> 目标类型
     * @return 解析后的对象
     */
    public <T> T readYaml(Path path, Class<T> type) {
        return read(path, type, yamlMapper, "YAML");
    }

    <T> T readYaml(byte[] content, Class<T> type) {
        if (content == null || type == null) {
            throw new IllegalArgumentException("content 和 type 不能为空");
        }
        try {
            ensureWithinLimit(content.length);
            return yamlMapper.readValue(content, type);
        } catch (WorkspaceException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new WorkspaceException("解析内置 YAML Workspace 文档失败", failure);
        }
    }

    /**
     * 将对象序列化为有界 YAML 字节。
     *
     * @param value 待序列化对象
     * @return UTF-8 YAML 字节
     */
    public byte[] writeYaml(Object value) {
        return write(value, yamlMapper, "YAML");
    }

    /**
     * 读取并解析有界 JSON 文档。
     *
     * @param path 文档路径
     * @param type 目标契约类型
     * @param <T> 目标类型
     * @return 解析后的对象
     */
    public <T> T readJson(Path path, Class<T> type) {
        return read(path, type, jsonMapper, "JSON");
    }

    /**
     * 将对象序列化为有界 JSON 字节。
     *
     * @param value 待序列化对象
     * @return UTF-8 JSON 字节
     */
    public byte[] writeJson(Object value) {
        return write(value, jsonMapper, "JSON");
    }

    <T> T convertJsonTree(JsonNode tree, Class<T> type) {
        if (tree == null || type == null) {
            throw new IllegalArgumentException("tree 和 type 不能为空");
        }
        try {
            return jsonMapper.treeToValue(tree, type);
        } catch (IOException | RuntimeException failure) {
            throw new WorkspaceException("转换 Workspace JSON 树失败", failure);
        }
    }

    private static <T> T read(Path path, Class<T> type, ObjectMapper mapper, String format) {
        if (path == null || type == null) {
            throw new IllegalArgumentException("path 和 type 不能为空");
        }
        try {
            byte[] content = readBounded(path);
            return mapper.readValue(content, type);
        } catch (WorkspaceException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new WorkspaceException("读取 " + format + " Workspace 文档失败: " + path, failure);
        }
    }

    private static byte[] write(Object value, ObjectMapper mapper, String format) {
        if (value == null) {
            throw new IllegalArgumentException("value 不能为空");
        }
        try {
            byte[] content = mapper.writeValueAsBytes(value);
            ensureWithinLimit(content.length);
            return content;
        } catch (WorkspaceException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new WorkspaceException("序列化 " + format + " Workspace 文档失败", failure);
        }
    }

    private static byte[] readBounded(Path path) throws IOException {
        long declaredSize = Files.size(path);
        ensureWithinLimit(declaredSize);
        try (InputStream input = Files.newInputStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) declaredSize)) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                ensureWithinLimit(total);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void ensureWithinLimit(long size) {
        if (size > MAX_DOCUMENT_BYTES) {
            throw new WorkspaceException(
                    "Workspace 控制文档超过最大字节数 " + MAX_DOCUMENT_BYTES + ": " + size);
        }
    }
}
