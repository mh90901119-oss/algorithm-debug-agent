package org.example.algorithmdebug.core;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** 不启动 shell，仅从明确来源定位本机 Maven 可执行文件。 */
public final class MavenExecutableLocator {

    private final Map<String, String> environment;
    private final String pathSeparator;
    private final boolean windows;

    /**
     * 创建可测试的 Maven 定位器。
     *
     * @param environment 环境变量快照；只读取 MAVEN_HOME、M2_HOME 和 PATH
     * @param pathSeparator PATH 分隔符
     * @param windows 是否按 Windows 文件名顺序探测
     */
    public MavenExecutableLocator(
            Map<String, String> environment,
            String pathSeparator,
            boolean windows) {
        if (environment == null || pathSeparator == null || pathSeparator.isEmpty()) {
            throw new IllegalArgumentException("environment 和 pathSeparator 不能为空");
        }
        this.environment = supportedEnvironment(environment, windows);
        this.pathSeparator = pathSeparator;
        this.windows = windows;
    }

    /**
     * 按显式路径、MAVEN_HOME、M2_HOME、PATH 的固定优先级定位 Maven。
     *
     * @param explicit 可选显式可执行文件
     * @return 可用 Maven 的绝对规范化路径
     */
    public Optional<Path> locate(Optional<Path> explicit) {
        if (explicit == null) {
            throw new IllegalArgumentException("explicit 不能为空");
        }
        if (explicit.isPresent()) {
            return usable(explicit.orElseThrow());
        }
        Optional<Path> fromMavenHome = locateFromHome(environment.get("MAVEN_HOME"));
        if (fromMavenHome.isPresent()) {
            return fromMavenHome;
        }
        Optional<Path> fromM2Home = locateFromHome(environment.get("M2_HOME"));
        if (fromM2Home.isPresent()) {
            return fromM2Home;
        }
        return locateFromPath(environment.get("PATH"));
    }

    private Optional<Path> locateFromHome(String home) {
        if (home == null || home.isBlank()) {
            return Optional.empty();
        }
        try {
            Path bin = Path.of(home).resolve("bin");
            return firstUsable(bin);
        } catch (InvalidPathException failure) {
            return Optional.empty();
        }
    }

    private Optional<Path> locateFromPath(String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return Optional.empty();
        }
        for (String entry : pathValue.split(Pattern.quote(pathSeparator), -1)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Optional<Path> candidate = firstUsable(Path.of(entry));
                if (candidate.isPresent()) {
                    return candidate;
                }
            } catch (InvalidPathException ignored) {
                // 无效 PATH 项不应阻断后续受控探测。
            }
        }
        return Optional.empty();
    }

    private Optional<Path> firstUsable(Path directory) {
        for (String executableName : executableNames()) {
            Optional<Path> candidate = usable(directory.resolve(executableName));
            if (candidate.isPresent()) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    private Optional<Path> usable(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!windows && !Files.isExecutable(normalized)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    private List<String> executableNames() {
        if (!windows) {
            return List.of("mvn");
        }
        List<String> names = new ArrayList<>(3);
        names.add("mvn.cmd");
        names.add("mvn.bat");
        names.add("mvn.exe");
        return List.copyOf(names);
    }

    private static Map<String, String> supportedEnvironment(
            Map<String, String> source,
            boolean windows) {
        Map<String, String> supported = new HashMap<>();
        for (String name : List.of("MAVEN_HOME", "M2_HOME", "PATH")) {
            String value = source.get(name);
            if (value == null && windows) {
                value = source.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
            }
            if (value != null) {
                supported.put(name, value);
            }
        }
        return Map.copyOf(supported);
    }
}
