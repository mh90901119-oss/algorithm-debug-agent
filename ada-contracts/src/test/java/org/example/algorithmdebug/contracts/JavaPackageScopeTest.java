package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JavaPackageScopeTest {

    @Test
    void includesOnlyRootAndDotDelimitedDescendants() {
        assertTrue(JavaPackageScope.contains("com.foo", "com.foo"));
        assertTrue(JavaPackageScope.contains("com.foo", "com.foo.sub"));
        assertFalse(JavaPackageScope.contains("com.foo", "com.foobar"));
        assertFalse(JavaPackageScope.contains("com.foo", "com.foobar.sub"));
    }
}
