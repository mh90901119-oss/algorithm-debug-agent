package org.example.algorithmdebug.codepath.launcher;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** 包含返回值投影的非 void 方法 Advice。 */
public final class ReturnTraceAdvice {
    private ReturnTraceAdvice() {}

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
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown Throwable thrown) {
        CodePathCaptureRuntime.exit(
                className, methodName, descriptor, returnValue, thrown, token);
    }
}
