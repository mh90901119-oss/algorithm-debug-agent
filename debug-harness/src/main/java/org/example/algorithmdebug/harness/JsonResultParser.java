package org.example.algorithmdebug.harness;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.ScheduleResultParser;

import java.io.IOException;
import java.nio.file.Path;

/** 使用 JSON token 哈希完成语法验证，不引入任何领域 DTO。 */
public final class JsonResultParser implements ScheduleResultParser<JsonResultSnapshot> {

    @Override
    public JsonResultSnapshot parse(Path path) throws AdapterException {
        try (var parser = new JsonFactory().createParser(path.toFile())) {
            if (parser.nextToken() == null) throw new IOException("empty JSON");
            while (parser.nextToken() != null) { }
            return new JsonResultSnapshot("1.0");
        } catch (IOException | RuntimeException failure) {
            throw new AdapterException(
                    "ADAPTER_RESULT_PARSE_FAILED", "结果文件不是有效的有界 JSON", failure);
        }
    }
}
