package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.RunId;

import java.nio.file.Path;

/** 从受信任的项目 Case 根和不透明 ID 安全派生追加式归档路径。 */
public final class CaseArchiveLayout {

    private final Path casesRoot;
    private final Path caseRoot;

    private CaseArchiveLayout(Path casesRoot, Path caseRoot) {
        this.casesRoot = casesRoot;
        this.caseRoot = caseRoot;
    }

    /**
     * 创建一个只计算路径、不写文件的 Case 布局。
     *
     * @param casesRoot 已登记项目的 Case 根目录
     * @param caseId Case ID，必须能安全作为单一路径段
     * @return 规范化绝对布局
     */
    public static CaseArchiveLayout of(Path casesRoot, CaseId caseId) {
        if (casesRoot == null || caseId == null) {
            throw new IllegalArgumentException("casesRoot 和 caseId 不能为空");
        }
        Path root = casesRoot.toAbsolutePath().normalize();
        Path caseRoot = child(root, safeSegment(caseId.value(), "caseId"));
        return new CaseArchiveLayout(root, caseRoot);
    }

    /** @return 项目 Case 根目录 */
    public Path casesRoot() {
        return casesRoot;
    }

    /** @return 当前 Case 根目录 */
    public Path caseRoot() {
        return caseRoot;
    }

    /** @return Case 终态清单 */
    public Path caseDocument() {
        return child(caseRoot, "case.json");
    }

    /** @return Context 根目录 */
    public Path contextsRoot() {
        return child(caseRoot, "contexts");
    }

    /** @return 指定 Context 目录 */
    public Path contextRoot(ContextId contextId) {
        return child(contextsRoot(), safeSegment(contextId.value(), "contextId"));
    }

    /** @return 指定 Context 终态文档 */
    public Path contextDocument(ContextId contextId) {
        return child(contextRoot(contextId), "context.json");
    }

    /** @return 指定 Context 的一次性复现参考 */
    public Path contextReproduction(ContextId contextId) {
        return child(contextRoot(contextId), "reproduction.json");
    }

    /** @return Analysis 根目录 */
    public Path analysesRoot() {
        return child(caseRoot, "analyses");
    }

    /** @return 指定 Analysis 请求文档 */
    public Path analysisDocument(AnalysisId analysisId) {
        return child(child(analysesRoot(), safeSegment(analysisId.value(), "analysisId")),
                "analysis-request.json");
    }

    /** @return Run 根目录 */
    public Path runsRoot() {
        return child(caseRoot, "runs");
    }

    /** @return 指定 Run 目录 */
    public Path runRoot(RunId runId) {
        return child(runsRoot(), safeSegment(runId.value(), "runId"));
    }

    /** @return 指定 Run 启动请求文档 */
    public Path runRequest(RunId runId) {
        return child(runRoot(runId), "run-request.json");
    }

    /** @return 指定 Run 完成摘要 */
    public Path runOutcome(RunId runId) {
        return child(runRoot(runId), "run-outcome.json");
    }

    /** @return 指定 Run 的确定性结果指纹 */
    public Path runResultFingerprint(RunId runId) {
        return child(runRoot(runId), "run-result-fingerprint.json");
    }

    /** @return 指定 Run 原始产物目录 */
    public Path runRaw(RunId runId) {
        return child(runRoot(runId), "raw");
    }

    /** @return 预留 Evidence 根目录 */
    public Path evidenceRoot() {
        return child(caseRoot, "evidence");
    }

    private static Path child(Path parent, String segment) {
        Path result = parent.resolve(segment).normalize();
        if (!result.startsWith(parent) || result.equals(parent)) {
            throw new IllegalArgumentException("Case 归档路径越界: " + segment);
        }
        return result;
    }

    private static String safeSegment(String value, String field) {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\") || value.contains(":")) {
            throw new IllegalArgumentException(field + " 必须是单一安全路径段");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " 不允许包含控制字符");
        }
        return value;
    }
}
