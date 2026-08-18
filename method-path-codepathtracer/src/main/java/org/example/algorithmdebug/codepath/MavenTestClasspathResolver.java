package org.example.algorithmdebug.codepath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.example.algorithmdebug.harness.ExternalProcessRunner;
import org.example.algorithmdebug.harness.HarnessException;
import org.example.algorithmdebug.harness.ProcessLimits;
import org.example.algorithmdebug.harness.RunCompletion;
import org.example.algorithmdebug.harness.RunResult;
import org.example.algorithmdebug.methodpath.MethodPathCollectionException;
import org.example.algorithmdebug.methodpath.TargetClasspathResolver;

/** 通过锁定版本 Maven Dependency Plugin 构建目标模块测试运行 classpath。 */
public final class MavenTestClasspathResolver implements TargetClasspathResolver {
    private static final String DEPENDENCY_GOAL =
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath";
    private final ExternalProcessRunner processes;
    private final String pathSeparator;

    /** 使用平台 classpath 分隔符和共享进程监管器。 */
    public MavenTestClasspathResolver() {
        this(new ExternalProcessRunner(), java.io.File.pathSeparator);
    }

    MavenTestClasspathResolver(ExternalProcessRunner processes, String pathSeparator) {
        this.processes = processes;
        this.pathSeparator = pathSeparator;
    }

    /**
     * 在 Case Collection 内写 classpath 文件和日志，不向目标源码树写辅助配置。
     */
    @Override
    public List<String> resolve(
            Path mavenExecutable, Path moduleRoot, Path collectionDirectory)
            throws MethodPathCollectionException {
        Path metadata = collectionDirectory.resolve("metadata");
        Path output = metadata.resolve("test-classpath.txt").toAbsolutePath().normalize();
        try {
            Files.createDirectory(metadata);
            List<String> argv = List.of(
                    mavenExecutable.toString(), "-q", "test-compile", DEPENDENCY_GOAL,
                    "-Dmdep.includeScope=test", "-Dmdep.outputFile=" + output);
            RunResult result = processes.execute(
                    argv, moduleRoot, collectionDirectory.resolve("logs/classpath-stdout.log"),
                    collectionDirectory.resolve("logs/classpath-stderr.log"), Duration.ofMinutes(10),
                    ProcessLimits.defaults());
            if (result.completion() != RunCompletion.SUCCEEDED) {
                throw new MethodPathCollectionException(
                        "CLASSPATH_RESOLUTION_FAILED", "Maven 测试 classpath 构建失败", null);
            }
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(output) > 4L * 1024 * 1024) {
                throw new MethodPathCollectionException(
                        "CLASSPATH_OUTPUT_INVALID", "Maven classpath 文件缺失或超限", null);
            }
            List<String> classpath = new ArrayList<>();
            classpath.add(moduleRoot.resolve("target/test-classes").toAbsolutePath().normalize().toString());
            classpath.add(moduleRoot.resolve("target/classes").toAbsolutePath().normalize().toString());
            String dependencies = Files.readString(output, StandardCharsets.UTF_8).strip();
            if (!dependencies.isEmpty()) {
                for (String entry : dependencies.split(java.util.regex.Pattern.quote(pathSeparator))) {
                    if (!entry.isBlank()) {
                        classpath.add(Path.of(entry).toAbsolutePath().normalize().toString());
                    }
                }
            }
            return List.copyOf(classpath);
        } catch (IOException | HarnessException | SecurityException failure) {
            throw new MethodPathCollectionException(
                    "CLASSPATH_RESOLUTION_FAILED", "无法解析目标 Maven 测试 classpath", failure);
        }
    }
}
