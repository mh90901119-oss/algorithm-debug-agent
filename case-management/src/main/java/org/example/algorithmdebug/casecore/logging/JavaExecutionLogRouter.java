package org.example.algorithmdebug.casecore.logging;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * 将 Java 执行事件写入 Case 日期日志；无 Case 的错误写入可选 bootstrap 日志。
 * 所有文件系统失败均在本组件内隔离。
 */
public final class JavaExecutionLogRouter implements AgentExecutionLog {
    public static final String DFX_DIRECTORY_ENV = "ADA_DFX_DIRECTORY";

    private final Clock clock;
    private final Optional<Path> dfxDirectory;
    private final CaseLogPathResolver paths;
    private final AgentLogFormatter formatter;

    public JavaExecutionLogRouter(Clock clock, Optional<Path> dfxDirectory) {
        if (clock == null || dfxDirectory == null) {
            throw new IllegalArgumentException("Log router dependencies must not be null");
        }
        this.clock = clock;
        this.dfxDirectory = dfxDirectory.map(path -> path.toAbsolutePath().normalize());
        this.paths = new CaseLogPathResolver();
        this.formatter = new AgentLogFormatter(clock.getZone(), new SensitiveLogSanitizer());
    }

    /** 从 Adapter 内部环境建立路由器；环境缺失只关闭 bootstrap 日志。 */
    public static JavaExecutionLogRouter fromEnvironment(Clock clock, Map<String, String> environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment must not be null");
        }
        String configured = environment.get(DFX_DIRECTORY_ENV);
        Optional<Path> root = configured == null || configured.isBlank()
                ? Optional.empty() : Optional.of(Path.of(configured));
        return new JavaExecutionLogRouter(clock, root);
    }

    @Override
    public void write(AgentLogEvent event) {
        if (event == null) {
            return;
        }
        Path target;
        try {
            LocalDate date = LocalDate.now(clock);
            if (event.context().hasCaseIdentity()) {
                target = paths.caseLog(event.context(), date);
            } else if (event.level() == AgentLogLevel.ERROR && dfxDirectory.isPresent()) {
                target = paths.bootstrapLog(dfxDirectory.orElseThrow(), date);
            } else {
                return;
            }
            append(target, formatter.format(event, clock.instant()));
        } catch (IOException | RuntimeException ignored) {
            // Logging is a diagnostic side effect and must never alter ToolResponse or cleanup.
        }
    }

    private static void append(Path target, String text) throws IOException {
        Path parent = target.getParent();
        try {
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Log parent is not a directory");
            }
            Files.createDirectories(parent);
            OpenOption[] options = {
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE
            };
            try (FileChannel channel = FileChannel.open(target, options);
                 FileLock ignored = channel.lock()) {
                channel.position(channel.size());
                ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(false);
            }
        } catch (IOException | RuntimeException failure) {
            deleteIfEmpty(parent);
            throw failure;
        }
    }

    private static void deleteIfEmpty(Path directory) {
        if (directory == null || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var children = Files.list(directory)) {
            if (children.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        } catch (IOException | SecurityException ignored) {
            // Best-effort cleanup only.
        }
    }
}
