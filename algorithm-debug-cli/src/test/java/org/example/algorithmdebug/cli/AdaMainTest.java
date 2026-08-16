package org.example.algorithmdebug.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.algorithmdebug.contracts.ToolResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaMainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldInitializeRegisterAndDiagnoseThroughSingleJsonResponses() throws Exception {
        AdaMain application = AdaMain.defaultApplication();
        Path workspace = temporaryDirectory.resolve("workspace");
        Path repository = Files.createDirectories(temporaryDirectory.resolve("large-system"));
        Files.createDirectories(repository.resolve(".git"));
        Path module = Files.createDirectories(repository.resolve("algorithm-module"));
        Files.writeString(module.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

        Invocation initialized = invoke(application,
                "workspace", "init", "--root", workspace.toString());
        Invocation registered = invoke(application,
                "project", "register",
                "--workspace", workspace.toString(),
                "--project", module.toString());
        Invocation diagnosed = invoke(application,
                "doctor", "--workspace", workspace.toString(), "--project", module.toString());

        assertSuccess(initialized);
        assertSuccess(registered);
        assertSuccess(diagnosed);
        assertTrue(initialized.response().path("data").path("created").booleanValue());
        assertEquals(portable(module), registered.response().path("data").path("registration").path("moduleRoot").textValue());
        assertEquals(5, diagnosed.response().path("data").path("checks").size());
        assertTrue(Files.isRegularFile(workspace.resolve("workspace.yaml")));
        assertEquals("", initialized.stderr());
    }

    @Test
    void shouldReturnArgumentAndDomainExitCodesWithStableSanitizedResponses() throws Exception {
        AdaMain application = AdaMain.defaultApplication();
        Invocation invalidArguments = invoke(application, "workspace", "init", "--root");

        Path unsupportedWorkspace = temporaryDirectory.resolve("unsupported-workspace");
        Files.createDirectories(unsupportedWorkspace);
        Files.writeString(
                unsupportedWorkspace.resolve("workspace.yaml"),
                "schemaVersion: \"9.0\"\nkind: ALGORITHM_DEBUG_WORKSPACE\ncreatedAt: 2026-08-16T00:00:00Z\n",
                StandardCharsets.UTF_8);
        Invocation unsupported = invoke(application,
                "workspace", "init", "--root", unsupportedWorkspace.toString());

        Path workspace = temporaryDirectory.resolve("workspace");
        invoke(application, "workspace", "init", "--root", workspace.toString());
        Path missingPom = Files.createDirectories(temporaryDirectory.resolve("missing-pom"));
        Invocation notMaven = invoke(application,
                "project", "register", "--workspace", workspace.toString(), "--project", missingPom.toString());

        Path uninitializedWorkspace = Files.createDirectories(temporaryDirectory.resolve("uninitialized-workspace"));
        Invocation missingManifest = invoke(application,
                "project", "register",
                "--workspace", uninitializedWorkspace.toString(),
                "--project", moduleWithPom(temporaryDirectory.resolve("uninitialized-module")).toString());

        assertFailure(invalidArguments, 2, "CLI_INVALID_ARGUMENTS");
        assertFailure(unsupported, 3, "WORKSPACE_SCHEMA_UNSUPPORTED");
        assertFailure(notMaven, 3, "PROJECT_NOT_MAVEN");
        assertFailure(missingManifest, 3, "WORKSPACE_MANIFEST_INVALID");
        assertFalse(unsupported.stdout().contains(unsupportedWorkspace.toString()));
        assertFalse(notMaven.stdout().contains(missingPom.toString()));
    }

    @Test
    void shouldSanitizeUnexpectedFailuresAndKeepStderrOutOfStdout() throws Exception {
        String secret = "D:/company/secret/algorithm";
        AdaMain application = new AdaMain(
                command -> {
                    throw new IllegalStateException("unexpected at " + secret);
                },
                new CliResponseWriter());

        Invocation invocation = invoke(application,
                "workspace", "init", "--root", temporaryDirectory.resolve("workspace").toString());

        assertFailure(invocation, 10, "INTERNAL_ERROR");
        assertFalse(invocation.stdout().contains(secret));
        assertFalse(invocation.stderr().contains(secret));
        assertFalse(invocation.stdout().contains("IllegalStateException"));
        assertFalse(invocation.stderr().contains("IllegalStateException"));
        assertEquals("INTERNAL_ERROR" + System.lineSeparator(), invocation.stderr());
    }

    @Test
    void shouldRejectResponseAboveOneMebibyteBeforeWritingAnyBytes() throws Exception {
        CliResponseWriter writer = new CliResponseWriter();
        ToolResponse<String> oversized = ToolResponse.success(
                "x".repeat(CliResponseWriter.MAX_OUTPUT_BYTES), List.of());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream stdout = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> writer.write(oversized, stdout));
        assertEquals(0, bytes.size());
    }

    private static Invocation invoke(AdaMain application, String... arguments) throws Exception {
        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
             PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8)) {
            exitCode = application.run(arguments, stdout, stderr);
        }
        String stdout = stdoutBytes.toString(StandardCharsets.UTF_8);
        String stderr = stderrBytes.toString(StandardCharsets.UTF_8);
        assertEquals(stdout.strip(), stdout);
        JsonNode response = JSON.readTree(stdout);
        assertEquals("2.0", response.path("schemaVersion").textValue());
        return new Invocation(exitCode, stdout, stderr, response);
    }

    private static void assertSuccess(Invocation invocation) {
        assertEquals(0, invocation.exitCode());
        assertTrue(invocation.response().path("success").booleanValue());
        assertEquals("OK", invocation.response().path("code").textValue());
    }

    private static void assertFailure(Invocation invocation, int exitCode, String code) {
        assertEquals(exitCode, invocation.exitCode());
        assertFalse(invocation.response().path("success").booleanValue());
        assertEquals(code, invocation.response().path("code").textValue());
        assertTrue(invocation.response().path("data").isNull());
    }

    private static String portable(Path path) throws Exception {
        return path.toRealPath().toString().replace('\\', '/');
    }

    private static Path moduleWithPom(Path module) throws Exception {
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        return module;
    }

    private record Invocation(int exitCode, String stdout, String stderr, JsonNode response) {
    }
}
