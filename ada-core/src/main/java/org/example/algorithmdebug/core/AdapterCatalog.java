package org.example.algorithmdebug.core;

import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** 对装配层注入的 Adapter 做稳定、无猜测的选择，不自行调用 ServiceLoader。 */
public final class AdapterCatalog {

    private final List<TargetProjectAdapter> adapters;

    /** @param adapters 装配层已发现的无状态 Adapter 列表 */
    public AdapterCatalog(List<TargetProjectAdapter> adapters) {
        if (adapters == null || adapters.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("adapters 不能为空且不得包含 null");
        }
        List<TargetProjectAdapter> sorted = adapters.stream()
                .sorted(Comparator.comparing(adapter -> adapter.descriptor().adapterId()))
                .toList();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (TargetProjectAdapter adapter : sorted) {
            if (!ids.add(adapter.descriptor().adapterId())) {
                throw new IllegalArgumentException(
                        "Adapter ID 重复: " + adapter.descriptor().adapterId());
            }
        }
        this.adapters = List.copyOf(sorted);
    }

    /**
     * 选择并检查目标项目；显式 ID 必须精确匹配，自动模式必须只有一个 Adapter 支持项目。
     */
    public AdapterSelection select(Path projectRoot, Optional<String> requestedAdapterId) {
        if (projectRoot == null || requestedAdapterId == null) {
            throw new IllegalArgumentException("projectRoot 和 requestedAdapterId 不能为空");
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        if (requestedAdapterId.isPresent()) {
            String requested = requestedAdapterId.orElseThrow().strip();
            if (requested.isEmpty()) {
                throw new IllegalArgumentException("requestedAdapterId 不能为空字符串");
            }
            TargetProjectAdapter adapter = adapters.stream()
                    .filter(candidate -> candidate.descriptor().adapterId().equals(requested))
                    .findFirst()
                    .orElseThrow(() -> new CaseRunException(
                            "ADAPTER_NOT_FOUND", "未找到指定 Adapter: " + requested));
            return inspect(adapter, root);
        }

        List<AdapterSelection> matches = new ArrayList<>();
        for (TargetProjectAdapter adapter : adapters) {
            try {
                matches.add(new AdapterSelection(adapter, adapter.inspect(root)));
            } catch (AdapterException ignored) {
                // 自动探测只把“检查成功”视为支持；具体失败不用于猜测 Adapter。
            }
        }
        if (matches.isEmpty()) {
            throw new CaseRunException("ADAPTER_NOT_FOUND", "没有 Adapter 支持已登记算法模块");
        }
        if (matches.size() > 1) {
            throw new CaseRunException(
                    "ADAPTER_AMBIGUOUS",
                    "多个 Adapter 支持算法模块: " + matches.stream()
                            .map(value -> value.adapter().descriptor().adapterId()).toList());
        }
        return matches.getFirst();
    }

    /** @return 按 ID 排序的不可变 Adapter 列表 */
    public List<TargetProjectAdapter> adapters() {
        return adapters;
    }

    private static AdapterSelection inspect(TargetProjectAdapter adapter, Path root) {
        try {
            return new AdapterSelection(adapter, adapter.inspect(root));
        } catch (AdapterException failure) {
            throw new CaseRunException(failure.code(), "指定 Adapter 不支持算法模块", failure);
        }
    }

    /** 一次选择同时返回 Adapter 和它检查生成的显式项目描述。 */
    public record AdapterSelection(
            TargetProjectAdapter adapter,
            ProjectDescriptor project) {
        /** 校验选择结果完整。 */
        public AdapterSelection {
            if (adapter == null || project == null) {
                throw new IllegalArgumentException("AdapterSelection 字段不能为空");
            }
        }
    }
}
