package org.example.algorithmdebug.core.coordination;

import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;

/** Policy 使用的确定性只读 prerequisite 端口；实现只能读取已校验控制文档。 */
public interface CoreActionPrerequisites {
    /** Analysis 初始化状态；半初始化和损坏必须返回 INVALID。 */
    AnalysisInitialization analysisInitialization(AnalysisIdentity identity);

    /** 当前 Analysis 是否已归档并校验算法输入。 */
    boolean algorithmInputCaptured(AnalysisIdentity identity);

    /** 当前 Analysis 是否有可验证 Method Catalog。 */
    boolean methodCatalogAvailable(AnalysisIdentity identity);

    /** 指定 Plan 是否存在、属于当前 Analysis 且类型匹配。 */
    boolean planAvailable(AnalysisIdentity identity, PlanId planId, PlanKind planKind);

    enum AnalysisInitialization { ABSENT, INITIALIZED, INVALID }

    enum PlanKind { CODEPATH, JDWP }
}
