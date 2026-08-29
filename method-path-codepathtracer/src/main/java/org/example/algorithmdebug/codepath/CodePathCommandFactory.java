package org.example.algorithmdebug.codepath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.example.algorithmdebug.methodpath.MethodPathCollectionRequest;

/** 以 argv 列表构造外部 CodePath 命令，绝不拼接 shell 字符串。 */
public final class CodePathCommandFactory {
    private final String pathSeparator;
    public CodePathCommandFactory(String pathSeparator) {
        if (pathSeparator == null || pathSeparator.isEmpty()) throw new IllegalArgumentException("pathSeparator must not be null");
        this.pathSeparator = pathSeparator;
    }
    /** 传入归档 Plan 和单一 Raw Trace，不传包范围。 */
    public List<String> create(
            CodePathToolConfiguration configuration, MethodPathCollectionRequest request,
            Path launcherPlan, Path rawTrace) {
        List<String> classpath = new ArrayList<>();
        classpath.add(configuration.launcherJar().toString()); classpath.addAll(request.targetClasspath());
        return List.of(configuration.javaExecutable().toString(), "-cp", String.join(pathSeparator, classpath),
                configuration.mainClass(), "--plan", launcherPlan.toAbsolutePath().normalize().toString(),
                "--trace", rawTrace.toAbsolutePath().normalize().toString());
    }
}
