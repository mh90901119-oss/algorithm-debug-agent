package one.edee.mcp.jdwp.core;

import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在断点暂停期间读取有界调用栈，不读取局部变量或递归对象图。 */
public final class FrameSnapshotter {

    /** 返回最多 {@code maxFrames} 个栈帧；读取失败时保留结构化错误。 */
    public List<Map<String, Object>> capture(ThreadReference thread, int maxFrames) {
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
                result.add(item);
            }
        } catch (IncompatibleThreadStateException failure) {
            return List.of();
        }
        return result;
    }
}
