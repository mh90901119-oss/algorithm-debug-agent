package org.example.algorithmdebug.codepath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.example.algorithmdebug.methodpath.MethodPathCollectionRequest;

/** 以 argv 列表构造外部 CodePath 命令，绝不拼接 shell 字符串。 */
public final class CodePathCommandFactory {
    private final String pathSeparator;

    /** @param pathSeparator 当前平台 classpath 分隔符 */
    public CodePathCommandFactory(String pathSeparator) {
        if (pathSeparator == null || pathSeparator.isEmpty()) {
            throw new IllegalArgumentException("pathSeparator 不能为空");
        }
        this.pathSeparator = pathSeparator;
    }

    /** 返回可直接传给 {@link ProcessBuilder} 的不可变 argv。 */
    public List<String> create(
            CodePathToolConfiguration configuration,
            MethodPathCollectionRequest request,
            Path rawTrace) {
        List<String> classpath = new ArrayList<>();
        classpath.add(configuration.launcherJar().toString());
        classpath.addAll(request.targetClasspath());
        return List.of(
                configuration.javaExecutable().toString(),
                "-cp", String.join(pathSeparator, classpath),
                configuration.mainClass(),
                "--test", request.targetTestSelector(),
                "--include", request.plan().packagePrefixes().getFirst(),
                "--trace", rawTrace.toAbsolutePath().normalize().toString(),
                "--max-output-bytes", Long.toString(request.plan().budget().maxBytes()),
                "--max-events", Long.toString(request.plan().budget().maxEvents()));
    }
}
