package org.example.algorithmdebug.codepath;

/** Collector 进程监管测试使用的最小阻塞子进程。 */
public final class CodePathProcessFixtureMain {
    private CodePathProcessFixtureMain() {
    }

    /** 保持运行直到父进程超时清理。 */
    public static void main(String[] args) throws Exception {
        Thread.sleep(30_000);
    }
}
