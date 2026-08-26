package org.example.algorithmdebug.core;

import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.contracts.ProjectRegistration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

/** 从项目注册配置确定性解析非递归 JSON 结果源。 */
final class ProjectResultSource {

    private static final String RUN_DATE_TOKEN = "${runDate}";
    private static final String DYNAMIC_TOKEN_PREFIX = "${";

    private ProjectResultSource() {
    }

    static Optional<ScheduleResultSource> from(ProjectRegistration registration) {
        return from(registration, Clock.systemDefaultZone());
    }

    /** 使用给定时钟解析本次 UT 的实际结果目录，便于确定性测试日期分区。 */
    static Optional<ScheduleResultSource> from(ProjectRegistration registration, Clock clock) {
        if (registration == null) {
            throw new IllegalArgumentException("registration 不能为空");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (registration.resultJsonDirectory() == null) {
            return Optional.empty();
        }
        String resolvedValue = registration.resultJsonDirectory().replace(
                RUN_DATE_TOKEN, LocalDate.now(clock).toString());
        if (resolvedValue.contains(DYNAMIC_TOKEN_PREFIX)) {
            throw new CaseRunException(
                    "PROJECT_RESULT_DIRECTORY_INVALID",
                    "Only ${runDate} is supported in the algorithm result directory");
        }
        Path moduleRoot = Path.of(registration.moduleRoot()).toAbsolutePath().normalize();
        Path configured = Path.of(resolvedValue);
        Path output = configured.isAbsolute()
                ? configured.normalize()
                : moduleRoot.resolve(configured).normalize();
        if (!configured.isAbsolute() && (!output.startsWith(moduleRoot) || output.equals(moduleRoot))) {
            throw new CaseRunException(
                    "PROJECT_RESULT_DIRECTORY_INVALID",
                    "The relative algorithm result directory must be inside moduleRoot" );
        }
        return Optional.of(new ScheduleResultSource(output, false));
    }
}
