package org.example.algorithmdebug.codepath;

/** Collector 进程监管测试使用的最小阻塞子进程。 */
public final class CodePathProcessFixtureMain {
    private CodePathProcessFixtureMain() {
    }

    /** 保持运行直到父进程超时清理。 */
    public static void main(String[] args) throws Exception {
        java.nio.file.Path plan = argument(args, "--plan");
        java.nio.file.Path trace = argument(args, "--trace");
        String archived = java.nio.file.Files.readString(plan);
        if (archived.contains("\"rationale\":\"success\"")) {
            emit(trace, "TARGET_SUCCEEDED", 1, 1, 0, 0, "NONE", "", 0, true);
            return;
        }
        if (archived.contains("\"rationale\":\"zero-hit\"")) {
            emit(trace, "TARGET_SUCCEEDED", 1, 1, 0, 0, "NONE", "", 0, false);
            return;
        }
        if (archived.contains("\"rationale\":\"truncated\"")) {
            emit(trace, "TARGET_SUCCEEDED", 1, 1, 0, 0, "EVENTS", "", 0, true);
            return;
        }
        if (archived.contains("\"rationale\":\"target-failed\"")) {
            emit(trace, "TARGET_FAILED", 1, 0, 0, 1, "NONE", "assertion failed", 2, true);
            return;
        }
        if (archived.contains("\"rationale\":\"tool-and-target-failed\"")) {
            emit(trace, "TOOL_FAILED", 1, 0, 0, 1, "NONE",
                    "CODEPATH_MULTIPLE_THREADS_UNSUPPORTED", 1, true);
            return;
        }
        if (archived.contains("\"rationale\":\"malformed\"")) {
            java.nio.file.Files.createFile(trace);
            System.out.println("ADA_CODEPATH_SUMMARY={not-json}");
            return;
        }
        Thread.sleep(30_000);
    }

    private static void emit(java.nio.file.Path trace, String outcome, long found, long succeeded,
            long aborted, long failed, String limit, String detail, int exitCode, boolean event)
            throws Exception {
        String line = "{\"eventId\":1}\n";
        if (event) java.nio.file.Files.writeString(trace, line);
        else java.nio.file.Files.createFile(trace);
        long events = event ? 1 : 0;
        long bytes = event ? line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0;
        System.out.println("ADA_CODEPATH_SUMMARY={\"outcome\":\"" + outcome
                + "\",\"testsFound\":" + found + ",\"testsSucceeded\":" + succeeded
                + ",\"testsAborted\":" + aborted + ",\"testsFailed\":" + failed
                + ",\"eventsWritten\":" + events + ",\"bytesWritten\":" + bytes
                + ",\"limit\":\"" + limit + "\",\"detail\":\"" + detail + "\"}");
        if (exitCode != 0) System.exit(exitCode);
    }

    private static java.nio.file.Path argument(String[] args, String name) {
        for (int index = 0; index < args.length - 1; index += 2) {
            if (name.equals(args[index])) return java.nio.file.Path.of(args[index + 1]);
        }
        throw new IllegalArgumentException("missing " + name);
    }
}
