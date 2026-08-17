package org.example.algorithmdebug.core;

import org.example.algorithmdebug.adapter.AdapterCapability;
import org.example.algorithmdebug.adapter.AdapterDescriptor;
import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.InputLocator;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.ScheduleResultParser;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdapterCatalogTest {

    @TempDir
    Path projectRoot;

    @Test
    void explicitAdapterUsesExactStableId() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        AdapterCatalog catalog = new AdapterCatalog(List.of(
                new StubAdapter("zeta", true), new StubAdapter("alpha", true)));

        AdapterCatalog.AdapterSelection selection = catalog.select(
                projectRoot, Optional.of("alpha"));

        assertEquals("alpha", selection.adapter().descriptor().adapterId());
    }

    @Test
    void autoSelectionRejectsAmbiguityInsteadOfUsingListOrder() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        AdapterCatalog catalog = new AdapterCatalog(List.of(
                new StubAdapter("zeta", true), new StubAdapter("alpha", true)));

        CaseRunException failure = assertThrows(CaseRunException.class,
                () -> catalog.select(projectRoot, Optional.empty()));

        assertEquals("ADAPTER_AMBIGUOUS", failure.code());
    }

    @Test
    void duplicateAdapterIdsAreRejectedAtCompositionTime() {
        assertThrows(IllegalArgumentException.class, () -> new AdapterCatalog(List.of(
                new StubAdapter("same", true), new StubAdapter("same", false))));
    }

    private record Snapshot(String schemaVersion) implements ScheduleResultSnapshot {
    }

    private static final class StubAdapter implements TargetProjectAdapter<Snapshot> {
        private final AdapterDescriptor descriptor;
        private final boolean supported;

        private StubAdapter(String id, boolean supported) {
            this.descriptor = new AdapterDescriptor(
                    id, "1.0", id, Set.of(AdapterCapability.BASELINE_EXECUTION));
            this.supported = supported;
        }

        @Override
        public AdapterDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ProjectDescriptor inspect(Path root) throws AdapterException {
            if (!supported) {
                throw new AdapterException("ADAPTER_PROJECT_NOT_SUPPORTED", "unsupported");
            }
            return new ProjectDescriptor(
                    new ProjectId("stub-project"), "stub", root.toAbsolutePath(),
                    BuildTool.MAVEN, Path.of("pom.xml"));
        }

        @Override
        public TestLaunchSpec createLaunchSpec(
                ProjectDescriptor project, TargetTest targetTest, RunMode runMode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputLocator inputLocator() {
            return (project, targetTest) -> Optional.empty();
        }

        @Override
        public ScheduleResultSource scheduleResultSource(
                ProjectDescriptor project, TargetTest targetTest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduleResultParser<Snapshot> scheduleResultParser() {
            throw new UnsupportedOperationException();
        }

    }
}
