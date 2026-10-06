package org.example.algorithmdebug.core.coordination;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;

/** 按动作类型保存唯一完整绑定的不可变 Registry。 */
public final class AnalysisActionRegistry {
    private final Map<AnalysisActionType, AnalysisActionBinding<?, ?>> bindings;
    private final List<AnalysisActionBinding<?, ?>> orderedBindings;

    private AnalysisActionRegistry(
            Map<AnalysisActionType, AnalysisActionBinding<?, ?>> bindings,
            List<AnalysisActionBinding<?, ?>> orderedBindings) {
        this.bindings = bindings;
        this.orderedBindings = orderedBindings;
    }

    /**
     * 从完整绑定列表创建 Registry；同一动作重复注册立即失败。
     *
     * @param source 完整 typed 绑定
     * @return 不可变 Registry
     */
    public static AnalysisActionRegistry of(List<AnalysisActionBinding<?, ?>> source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        EnumMap<AnalysisActionType, AnalysisActionBinding<?, ?>> indexed =
                new EnumMap<>(AnalysisActionType.class);
        for (AnalysisActionBinding<?, ?> binding : source) {
            if (binding == null) {
                throw new IllegalArgumentException("source must not contain null bindings");
            }
            if (indexed.putIfAbsent(binding.actionType(), binding) != null) {
                throw new IllegalArgumentException(
                        "Action already has a binding: " + binding.actionType().name());
            }
        }
        List<AnalysisActionBinding<?, ?>> ordered = new ArrayList<>(indexed.values());
        ordered.sort(Comparator.comparing(value -> value.actionType().name()));
        return new AnalysisActionRegistry(Map.copyOf(indexed), List.copyOf(ordered));
    }

    /**
     * 返回动作的唯一绑定，未注册动作 fail closed。
     *
     * @param actionType 动作类型
     * @return 唯一 Policy/Handler 绑定
     */
    public AnalysisActionBinding<?, ?> require(AnalysisActionType actionType) {
        if (actionType == null) {
            throw new IllegalArgumentException("actionType must not be null");
        }
        AnalysisActionBinding<?, ?> binding = bindings.get(actionType);
        if (binding == null) {
            throw new IllegalArgumentException("Action is not registered: " + actionType.name());
        }
        return binding;
    }

    /** @return 按动作名稳定排序的不可变绑定列表 */
    public List<AnalysisActionBinding<?, ?>> bindings() {
        return orderedBindings;
    }
}
