package org.example.algorithmdebug.adapter.waferdemo;

import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WaferDemoAdapterServiceLoaderTest {

    @Test
    void shouldBeDiscoverableThroughTargetProjectAdapterSpi() {
        boolean found = ServiceLoader.load(TargetProjectAdapter.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(adapter -> adapter instanceof WaferDemoAdapter);

        assertTrue(found);
    }
}

