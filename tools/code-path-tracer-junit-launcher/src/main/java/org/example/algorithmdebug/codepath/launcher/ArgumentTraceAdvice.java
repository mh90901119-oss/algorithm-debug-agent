package org.example.algorithmdebug.codepath.launcher;

import net.bytebuddy.asm.Advice;

/** 只含参数投影的方法 Advice。 */
public final class ArgumentTraceAdvice {
    private ArgumentTraceAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#d") String descriptor,
            @Advice.AllArguments Object[] arguments) {
        return CodePathCaptureRuntime.enter(className, methodName, descriptor, arguments);
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
