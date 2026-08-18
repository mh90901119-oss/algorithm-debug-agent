package org.example.algorithmdebug.jdwp;

/** 仅用于真实 Collector 条件式 Smoke 的最小 JDWP 目标。 */
public final class RealJdwpFixtureMain {
    private RealJdwpFixtureMain() { }

    public static void main(String[] args) {
        System.exit(compute(21) == 42 ? 0 : 2);
    }

    static int compute(int value) {
        int doubled = value * 2; // REAL_JDWP_TRACEPOINT
        return doubled;
    }
}
