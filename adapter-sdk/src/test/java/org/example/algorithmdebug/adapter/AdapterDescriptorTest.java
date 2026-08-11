package org.example.algorithmdebug.adapter;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdapterDescriptorTest {

    @Test
    void shouldDefensivelyCopyCapabilities() {
        Set<AdapterCapability> capabilities = EnumSet.of(
                AdapterCapability.BASELINE_EXECUTION,
                AdapterCapability.SCHEDULE_RESULT);

        AdapterDescriptor descriptor = new AdapterDescriptor(
                "wafer-demo", "0.1.0", "Wafer Demo", capabilities);
        capabilities.add(AdapterCapability.JDWP_COLLECTION);

        assertEquals(
                Set.of(AdapterCapability.BASELINE_EXECUTION, AdapterCapability.SCHEDULE_RESULT),
                descriptor.capabilities());
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.capabilities().add(AdapterCapability.INPUT_LOCATION));
    }

    @Test
    void shouldRejectInvalidAdapterMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdapterDescriptor("Wafer Demo", "0.1.0", "Wafer", Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AdapterDescriptor("wafer-demo", " ", "Wafer", Set.of()));
    }
}

