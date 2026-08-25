package org.example.algorithmdebug.adapter.maven;

import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenJUnitAdapterServiceLoaderTest {

    @Test
    void shouldExposeExactlyOneMavenJUnitAdapter() {
        var adapters = ServiceLoader.load(TargetProjectAdapter.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();

        assertEquals(1, adapters.size());
        assertEquals("maven-junit", adapters.getFirst().descriptor().adapterId());
    }
}
