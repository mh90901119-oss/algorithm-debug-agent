package org.example.algorithmdebug.core.coordination;

import org.example.algorithmdebug.contracts.coordination.ActionTarget;

/**
 * 将一个 typed 动作适配到既有 ApplicationService 的执行端口。
 *
 * <p>Handler 不重复实现公共身份、幂等、互斥或通用前后置规则。</p>
 *
 * @param <I> 动作输入类型
 * @param <O> 动作输出类型
 */
@FunctionalInterface
public interface AnalysisActionHandler<I, O> {
    /** 执行动作并响应协作式取消。 */
    O execute(ActionTarget target, I input, ActionCancellation cancellation);
}
