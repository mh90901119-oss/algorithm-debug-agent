package org.example.algorithmdebug.cli;

import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadedAdapterDiscoveryIT {

    @Test
    void shadedJarDiscoversExactlyOneWaferAdapter() throws Exception {
        Path shadedJar = Path.of(System.getProperty("ada.shadedJar"))
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.isRegularFile(shadedJar), "shaded CLI JAR 应先完成打包");

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{shadedJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> serviceType = Class.forName(
                    "org.example.algorithmdebug.adapter.TargetProjectAdapter", true, loader);
            ServiceLoader<?> services = ServiceLoader.load(serviceType, loader);
            List<?> adapters = services.stream().map(ServiceLoader.Provider::get).toList();

            assertEquals(1, adapters.size());
            Object descriptor = adapters.getFirst().getClass()
                    .getMethod("descriptor")
                    .invoke(adapters.getFirst());
            String adapterId = (String) descriptor.getClass()
                    .getMethod("adapterId")
                    .invoke(descriptor);
            assertEquals("maven-junit", adapterId);
        }
    }
}
