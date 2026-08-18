package org.example.algorithmdebug.jdwp;

import java.nio.file.Files;
import java.nio.file.Path;

final class CoordinatorFixtureMain {
    private CoordinatorFixtureMain() { }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            case "ready-exit" -> {
                System.err.println(args[1]);
                System.err.flush();
                Thread.sleep(Long.parseLong(args[2]));
                System.exit(Integer.parseInt(args[3]));
            }
            case "ready-sleep" -> {
                System.out.println(args[1]);
                System.out.flush();
                Thread.sleep(60_000);
            }
            case "ready-sleep-pid" -> {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                System.out.println(args[2]);
                System.out.flush();
                Thread.sleep(60_000);
            }
            case "sleep" -> Thread.sleep(60_000);
            case "write-sleep" -> {
                Path output = Path.of(args[1]);
                Files.createDirectories(output.getParent());
                Files.write(output, new byte[Integer.parseInt(args[2])]);
                Thread.sleep(60_000);
            }
            case "write-pid-sleep" -> {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(60_000);
            }
            default -> throw new IllegalArgumentException("unknown fixture mode");
        }
    }
}
