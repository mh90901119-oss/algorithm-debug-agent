package org.example.algorithmdebug.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.algorithmdebug.contracts.ToolResponse;

import java.io.IOException;
import java.io.PrintStream;

/** 将单个有界 ToolResponse 2.0 JSON 文档写入 stdout。 */
public final class CliResponseWriter {

    /** CLI stdout 单响应最大字节数：1 MiB。 */
    public static final int MAX_OUTPUT_BYTES = 1_048_576;

    private final ObjectMapper mapper;

    /** 使用 Java 时间模块创建稳定 JSON 写入器。 */
    public CliResponseWriter() {
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * 在写出任何字节前完成序列化和大小校验。
     *
     * @param response 统一工具响应
     * @param stdout 标准输出流
     */
    public void write(ToolResponse<?> response, PrintStream stdout) {
        if (response == null || stdout == null) {
            throw new IllegalArgumentException("response 和 stdout 不能为空");
        }
        byte[] content;
        try {
            content = mapper.writeValueAsBytes(response);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("序列化 CLI ToolResponse 失败", failure);
        }
        if (content.length > MAX_OUTPUT_BYTES) {
            throw new IllegalStateException(
                    "CLI ToolResponse 超过最大字节数 " + MAX_OUTPUT_BYTES + ": " + content.length);
        }
        stdout.write(content, 0, content.length);
        stdout.flush();
        if (stdout.checkError()) {
            throw new IllegalStateException("写入 CLI stdout 失败");
        }
    }
}
