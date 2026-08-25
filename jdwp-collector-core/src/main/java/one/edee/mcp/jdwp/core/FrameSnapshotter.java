package one.edee.mcp.jdwp.core;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在线程因断点暂停期间采集调用栈和顶层局部变量，不调用目标 JVM 中的业务方法。
 */
public final class FrameSnapshotter {
    private final JdiValueSnapshotter valueSnapshotter;

    public FrameSnapshotter(JdiValueSnapshotter valueSnapshotter) {
        this.valueSnapshotter = valueSnapshotter;
    }

    public List<Map<String, Object>> capture(ThreadReference thread, int maxFrames, boolean locals) {
        return capture(thread, maxFrames, locals, Set.of(), Set.of());
    }

    /**
     * 采集有界栈帧；局部变量和字段投影为空时采用预算内默认采集。
     */
    public List<Map<String, Object>> capture(
        ThreadReference thread,
        int maxFrames,
        boolean locals,
        Set<String> localNames,
        Set<String> fieldPaths
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<StackFrame> frames = thread.frames(0, Math.min(thread.frameCount(), maxFrames));
            for (int index = 0; index < frames.size(); index++) {
                StackFrame frame = frames.get(index);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", index);
                item.put("className", frame.location().declaringType().name());
                item.put("methodName", frame.location().method().name());
                item.put("methodDescriptor", frame.location().method().signature());
                item.put("line", frame.location().lineNumber());
                item.put("codeIndex", frame.location().codeIndex());
                if (locals && index == 0) {
                    item.put("locals", captureLocals(frame, localNames, fieldPaths));
                    if (frame.thisObject() != null && selected("this", localNames, fieldPaths)) {
                        item.put("this", valueSnapshotter.snapshot(
                            frame.thisObject(), nestedPaths("this", fieldPaths)
                        ));
                    }
                }
                result.add(item);
            }
        } catch (IncompatibleThreadStateException exception) {
            result.add(Map.of("$error", "Thread is not suspended: " + exception.getMessage()));
        }
        return result;
    }

    private Map<String, Object> captureLocals(
        StackFrame frame,
        Set<String> localNames,
        Set<String> fieldPaths
    ) {
        Map<String, Object> locals = new LinkedHashMap<>();
        try {
            List<LocalVariable> variables = frame.visibleVariables();
            Map<LocalVariable, Value> values = frame.getValues(variables);
            for (LocalVariable variable : variables) {
                if (selected(variable.name(), localNames, fieldPaths)) {
                    locals.put(variable.name(), valueSnapshotter.snapshot(
                        values.get(variable), nestedPaths(variable.name(), fieldPaths)
                    ));
                }
            }
        } catch (AbsentInformationException noDebugInfo) {
            locals.put("$error", "LocalVariableTable is absent; compile target classes with debug information");
        }
        return locals;
    }

    private static boolean selected(String root, Set<String> localNames, Set<String> fieldPaths) {
        if (localNames.isEmpty() && fieldPaths.isEmpty()) {
            return true;
        }
        if (localNames.contains(root)) {
            return true;
        }
        return fieldPaths.stream().anyMatch(path -> path.equals(root) || path.startsWith(root + "."));
    }

    private static Set<String> nestedPaths(String root, Set<String> fieldPaths) {
        Set<String> result = new LinkedHashSet<>();
        String prefix = root + ".";
        for (String path : fieldPaths) {
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                result.add(path.substring(prefix.length()));
            }
        }
        return result;
    }
}
