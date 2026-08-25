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

/** 通过锁定版本的 Maven Dependency Plugin 构建目标模块测试运行 classpath。 */
public final class MavenTestClasspathResolver implements TargetClasspathResolver {
    private static final String DEPENDENCY_GOAL =
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath";
    private final ExternalProcessRunner processes;
    private final String pathSeparator;

    /** 使用平台 classpath 分隔符和共享进程监控器。 */
    public MavenTestClasspathResolver() {
        this(new ExternalProcessRunner(), java.io.File.pathSeparator);
    }

    MavenTestClasspathResolver(ExternalProcessRunner processes, String pathSeparator) {
        this.processes = processes;
        this.pathSeparator = pathSeparator;
    }

    /**
     * 在系统临时目录解析 classpath，读取后立即清理，不把启动期中间文件归档到 Case。
     */
    @Override
    public List<String> resolve(
            Path mavenExecutable, Path moduleRoot, Path collectionDirectory)
            throws MethodPathCollectionException {
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("algorithm-debug-classpath-");
            Path output = scratch.resolve("test-classpath.txt").toAbsolutePath().normalize();
            List<String> argv = List.of(
                    mavenExecutable.toString(), "-q", "test-compile", DEPENDENCY_GOAL,
                    "-Dmdep.includeScope=test", "-Dmdep.outputFile=" + output);
            RunResult result = processes.execute(
                    argv, moduleRoot, scratch.resolve("stdout.log"), scratch.resolve("stderr.log"),
                    Duration.ofMinutes(10), ProcessLimits.defaults());
            if (result.completion() != RunCompletion.SUCCEEDED) {
                throw new MethodPathCollectionException(
                        "CLASSPATH_RESOLUTION_FAILED", "Maven test classpath resolution failed", null);
            }
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(output) > 4L * 1024 * 1024) {
                throw new MethodPathCollectionException(
                        "CLASSPATH_OUTPUT_INVALID", "Maven classpath output is missing or exceeds the limit", null);
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
                    "CLASSPATH_RESOLUTION_FAILED", "Unable to resolve the target Maven test classpath", failure);
        } finally {
            deleteScratchDirectory(scratch);
        }
    }

    private static void deleteScratchDirectory(Path directory) {
        if (directory == null) return;
        try {
            Files.deleteIfExists(directory.resolve("test-classpath.txt"));
            Files.deleteIfExists(directory.resolve("stdout.log"));
            Files.deleteIfExists(directory.resolve("stderr.log"));
            Files.deleteIfExists(directory);
        } catch (IOException cleanupFailure) {
            directory.resolve("test-classpath.txt").toFile().deleteOnExit();
            directory.resolve("stdout.log").toFile().deleteOnExit();
            directory.resolve("stderr.log").toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
        }
    }
}
