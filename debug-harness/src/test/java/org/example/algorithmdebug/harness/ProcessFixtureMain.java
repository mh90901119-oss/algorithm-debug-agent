package org.example.algorithmdebug.harness;

import java.nio.file.Path;

/** 仅供 Forked JVM 进程监管测试使用的最小目标程序。 */
public final class ProcessFixtureMain {

    private ProcessFixtureMain() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            case "sleep" -> Thread.sleep(60_000);
            case "marker" -> {
                System.out.print(args[1].substring(0, args[1].length() / 2));
                System.out.flush();
                Thread.sleep(50);
                System.out.println(args[1].substring(args[1].length() / 2));
                System.out.flush();
                Thread.sleep(60_000);
            }
            case "marker-exit" -> {
                System.out.print("x".repeat(2_000_000));
                System.out.print(args[1]);
            }
            case "spawn-child" -> {
                Process child = new ProcessBuilder(
                        javaExecutable().toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ProcessFixtureMain.class.getName(),
                        "sleep").start();
                System.out.println(child.pid());
                System.out.flush();
                Thread.sleep(60_000);
            }
            default -> throw new IllegalArgumentException("未知 fixture 模式: " + args[0]);
        }
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
