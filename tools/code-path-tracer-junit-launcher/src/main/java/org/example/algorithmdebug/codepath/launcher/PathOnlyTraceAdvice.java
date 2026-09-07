package org.example.algorithmdebug.codepath.launcher;

import net.bytebuddy.asm.Advice;

/** 无投影方法的低开销 Advice，不创建参数数组。 */
public final class PathOnlyTraceAdvice {
    private PathOnlyTraceAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#d") String descriptor) {
        return CodePathCaptureRuntime.enter(className, methodName, descriptor, null);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#d") String descriptor,
            @Advice.Enter long token,
            @Advice.Thrown Throwable thrown) {
        CodePathCaptureRuntime.exit(className, methodName, descriptor, null, thrown, token);
    }
}
