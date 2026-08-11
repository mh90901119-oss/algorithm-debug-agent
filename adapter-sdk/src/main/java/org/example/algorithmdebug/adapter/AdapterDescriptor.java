package org.example.algorithmdebug.adapter;

import java.util.Set;

/**
 * 可发现的 Adapter 元数据。
 *
 * @param adapterId 稳定、小写的 Adapter ID
 * @param adapterVersion Adapter 实现版本
 * @param displayName 面向用户的显示名
 * @param capabilities 当前实现明确支持的能力
 */
public record AdapterDescriptor(
        String adapterId,
        String adapterVersion,
        String displayName,
        Set<AdapterCapability> capabilities) {

    /** 校验元数据并冻结能力集合。 */
    public AdapterDescriptor {
        adapterId = AdapterChecks.requireAdapterId(adapterId);
        adapterVersion = AdapterChecks.requireNonBlank(adapterVersion, "adapterVersion");
        displayName = AdapterChecks.requireNonBlank(displayName, "displayName");
        capabilities = AdapterChecks.immutableSet(capabilities, "capabilities");
    }

    /**
     * 判断 Adapter 是否声明支持某项能力。
     *
     * @param capability 待检查能力
     * @return 已声明时返回 true
     */
    public boolean supports(AdapterCapability capability) {
        return capabilities.contains(AdapterChecks.requireNonNull(capability, "capability"));
    }
}

