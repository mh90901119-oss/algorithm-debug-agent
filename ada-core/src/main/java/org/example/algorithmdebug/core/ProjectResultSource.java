package org.example.algorithmdebug.core;

import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.contracts.ProjectRegistration;

import java.nio.file.Path;
import java.util.Optional;

/** 从项目注册配置确定性解析非递归 JSON 结果源。 */
final class ProjectResultSource {

    private ProjectResultSource() {
    }

    static Optional<ScheduleResultSource> from(ProjectRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException("registration 不能为空");
        }
        if (registration.resultJsonDirectory() == null) {
            return Optional.empty();
        }
        Path moduleRoot = Path.of(registration.moduleRoot()).toAbsolutePath().normalize();
        Path configured = Path.of(registration.resultJsonDirectory());
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
