package org.example.algorithmdebug.codepath;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 从有界 stdout 日志中读取且只接受一个受控 Launcher Summary。 */
public final class LauncherSummaryReader {
    private static final String PREFIX = "ADA_CODEPATH_SUMMARY=";
    private final ObjectMapper mapper = new ObjectMapper();

    /** 读取唯一结构化行；缺失、重复和 JSON 非法都视为工具协议失败。 */
    public CodePathLauncherSummary read(Path stdout) throws CodePathAdapterException {
        String found = null;
        try (var lines = Files.lines(stdout, StandardCharsets.UTF_8)) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (!line.startsWith(PREFIX)) {
                    continue;
                }
                if (found != null) {
                    throw new CodePathAdapterException(
                            "CODEPATH_LAUNCHER_PROTOCOL_INVALID", "Launcher Summary 重复", null);
                }
                found = line.substring(PREFIX.length());
            }
            if (found == null) {
                throw new CodePathAdapterException(
                        "CODEPATH_LAUNCHER_PROTOCOL_INVALID", "Launcher Summary 缺失", null);
            }
            return mapper.readValue(found, CodePathLauncherSummary.class);
        } catch (CodePathAdapterException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new CodePathAdapterException(
                    "CODEPATH_LAUNCHER_PROTOCOL_INVALID", "Launcher Summary 无法解析", failure);
        }
    }
}
