package org.example.algorithmdebug.codepath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.example.algorithmdebug.methodpath.CollectionCompletion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherSummaryReaderTest {
    @TempDir Path temp;

    @Test
    void classifiesTargetFailureFromStructuredSummaryNotExitCode() throws Exception {
        Path stdout = Files.writeString(temp.resolve("stdout.log"), "noise\n"
                + "ADA_CODEPATH_SUMMARY={\"outcome\":\"TARGET_FAILED\",\"testsFound\":1,"
                + "\"testsSucceeded\":0,\"testsAborted\":0,\"testsFailed\":1,"
                + "\"eventsWritten\":8,\"bytesWritten\":800,\"limit\":\"NONE\",\"detail\":\"assertion\"}\n");

        CodePathLauncherSummary summary = new LauncherSummaryReader().read(stdout);

        assertEquals(CollectionCompletion.TARGET_FAILED,
                CodePathProcessCollector.completion(false, 2, summary));
        assertEquals(CollectionCompletion.TOOL_FAILED,
                CodePathProcessCollector.completion(false, 2, null));
    }

    @Test
    void rejectsMissingOrDuplicateStructuredSummary() throws Exception {
        assertThrows(CodePathAdapterException.class,
                () -> new LauncherSummaryReader().read(Files.writeString(
                        temp.resolve("missing.log"), "plain output")));
        String line = "ADA_CODEPATH_SUMMARY={\"outcome\":\"TOOL_FAILED\",\"testsFound\":0,"
                + "\"testsSucceeded\":0,\"testsAborted\":0,\"testsFailed\":0,"
                + "\"eventsWritten\":0,\"bytesWritten\":0,\"limit\":\"NONE\",\"detail\":\"x\"}\n";
        assertThrows(CodePathAdapterException.class,
                () -> new LauncherSummaryReader().read(Files.writeString(
                        temp.resolve("duplicate.log"), line + line)));
    }
}
