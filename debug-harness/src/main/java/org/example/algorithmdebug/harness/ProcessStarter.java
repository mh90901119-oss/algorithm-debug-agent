package org.example.algorithmdebug.harness;

import java.io.IOException;

/** 隔离 `ProcessBuilder.start()` 副作用的包内端口。 */
@FunctionalInterface
interface ProcessStarter {
    Process start(ProcessBuilder builder) throws IOException;
}
