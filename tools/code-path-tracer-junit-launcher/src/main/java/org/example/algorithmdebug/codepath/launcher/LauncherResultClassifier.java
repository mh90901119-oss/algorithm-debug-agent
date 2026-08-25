package org.example.algorithmdebug.codepath.launcher;

import java.io.IOException;
import java.util.Optional;

/** 把 JUnit 事实与基础设施失败确定性分类，禁止由退出码反推目标状态。 */
public final class LauncherResultClassifier {
    private LauncherResultClassifier() {
    }

    /** 返回目标成功、目标失败或工具失败。 */
    public static LauncherOutcome classify(
            long testsFound, long testsFailed, long testsAborted, Optional<IOException> toolFailure) {
        if (toolFailure.isPresent()) {
            return LauncherOutcome.TOOL_FAILED;
        }
        if (testsFound == 0 || testsFailed > 0 || testsAborted > 0) {
            return LauncherOutcome.TARGET_FAILED;
        }
        return LauncherOutcome.TARGET_SUCCEEDED;
    }
}
