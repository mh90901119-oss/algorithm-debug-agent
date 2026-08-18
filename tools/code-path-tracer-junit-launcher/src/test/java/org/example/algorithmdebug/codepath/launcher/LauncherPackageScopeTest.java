package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.example.algorithmdebug.contracts.JavaPackageScope;
import org.junit.jupiter.api.Test;

class LauncherPackageScopeTest {
    @Test
    void acceptsExactAndChildPackageButNotLexicalSibling() {
        assertTrue(JavaPackageScope.contains("com.foo", "com.foo"));
        assertTrue(JavaPackageScope.contains("com.foo", "com.foo.child"));
        assertFalse(JavaPackageScope.contains("com.foo", "com.foobar"));
    }
}
