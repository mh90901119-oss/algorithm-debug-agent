package org.example.algorithmdebug.codepath.launcher;

/** 精确方法键的已校验运行时视图。 */
record MethodIdentity(String className, String methodName, String descriptor) {
    String methodKey() { return className + "#" + methodName + descriptor; }

    static MethodIdentity parse(String methodKey) {
        int hash = methodKey.indexOf('#');
        int descriptor = methodKey.indexOf('(', hash + 1);
        if (hash < 1 || descriptor < hash + 2) {
            throw new IllegalArgumentException("Invalid methodKey");
        }
        return new MethodIdentity(methodKey.substring(0, hash),
                methodKey.substring(hash + 1, descriptor), methodKey.substring(descriptor));
    }
}
