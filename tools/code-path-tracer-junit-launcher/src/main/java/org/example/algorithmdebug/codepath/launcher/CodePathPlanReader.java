package org.example.algorithmdebug.codepath.launcher;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 有界、严格读取归档 CodePath v2 计划。 */
public final class CodePathPlanReader {
    static final long MAX_PLAN_BYTES = 1024L * 1024;
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** 读取最多 1 MiB 的计划；记录构造器继续执行完整契约校验。 */
    public LauncherCodePathPlan read(Path plan) throws IOException {
        long size = Files.size(plan);
        if (size < 1 || size > MAX_PLAN_BYTES) {
            throw new IOException("CodePath Plan 大小必须在 1 到 1 MiB 之间");
        }
        return mapper.readValue(Files.readAllBytes(plan), LauncherCodePathPlan.class);
    }
}
