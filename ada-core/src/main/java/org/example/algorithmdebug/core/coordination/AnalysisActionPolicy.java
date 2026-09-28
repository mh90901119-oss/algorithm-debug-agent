package org.example.algorithmdebug.core.coordination;

import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;

/**
 * 单类分析动作的确定性前置授权与后置校验策略。
 *
 * <p>Policy 只能读取 typed 请求和派生控制视图，不得启动进程、采集证据或写入归档。</p>
 *
 * @param <I> 动作输入类型
 * @param <O> 动作输出类型
 */
public interface AnalysisActionPolicy<I, O> {
    /** 根据执行前视图决定是否允许动作。 */
    ActionDecision authorize(AnalysisControlView before, AnalysisActionRequest<I> request);

    /** 根据执行前后视图和 typed 结果校验机械后置条件。 */
    ActionDecision verify(
            AnalysisControlView before,
            AnalysisActionRequest<I> request,
            O result,
            AnalysisControlView after);
}
