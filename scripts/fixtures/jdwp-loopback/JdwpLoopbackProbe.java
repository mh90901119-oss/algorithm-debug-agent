public final class JdwpLoopbackProbe {
    private JdwpLoopbackProbe() {
    }

    public static void main(String[] args) {
        int observed = inspect(42);
        if (observed != 42) {
            throw new IllegalStateException("Unexpected probe result: " + observed);
        }
        System.out.println("JDWP_LOOPBACK_TARGET_OK marker=" + observed);
    }

    private static int inspect(int marker) {
        int captured = marker; // JDWP_LOOPBACK_TRACEPOINT
        return captured;
    }
}
