package org.example.algorithmdebug.core.coordination;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.CoordinationLimits;

/**
 * 一个动作类型与唯一输入类型、Policy、Handler 和结果映射的不可变绑定。
 *
 * @param actionType 动作类型
 * @param inputType 输入运行时类型
 * @param sideEffect 副作用等级
 * @param policy 唯一动作策略
 * @param handler 唯一动作处理器
 * @param resultAdapter typed 结果到统一元数据的纯函数
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public record AnalysisActionBinding<I, O>(
        AnalysisActionType actionType,
        Class<I> inputType,
        ActionSideEffect sideEffect,
        AnalysisActionPolicy<I, O> policy,
        AnalysisActionHandler<I, O> handler,
        ResultAdapter<O> resultAdapter) {

    /** 校验绑定各组成部分完整，禁止注册半绑定动作。 */
    public AnalysisActionBinding {
        actionType = requireNonNull(actionType, "actionType");
        inputType = requireNonNull(inputType, "inputType");
        sideEffect = requireNonNull(sideEffect, "sideEffect");
        policy = requireNonNull(policy, "policy");
        handler = requireNonNull(handler, "handler");
        resultAdapter = requireNonNull(resultAdapter, "resultAdapter");
    }

    /**
     * 在调用 Handler 前完成一次集中运行时类型校验。
     *
     * @param payload 未擦除的请求载荷
     * @return inputType 对应的 typed 载荷
     */
    public I requirePayload(Object payload) {
        if (!inputType.isInstance(payload)) {
            throw new IllegalArgumentException(
                    "Action payload type does not match " + actionType.name());
        }
        return inputType.cast(payload);
    }

    /**
     * 将 Handler 结果映射成统一返回元数据并拒绝空描述。
     *
     * @param result Handler typed 结果
     * @return 已验证协议预算的结果元数据
     */
    public ResultMetadata describe(O result) {
        return requireNonNull(resultAdapter.describe(requireNonNull(result, "result")), "metadata");
    }

    /** Handler 结果到协议元数据的无副作用适配器。 */
    @FunctionalInterface
    public interface ResultAdapter<O> {
        /** @return 稳定 code、说明、Artifact 和主结果 Artifact ID */
        ResultMetadata describe(O result);
    }

    /**
     * 动作成功结果的统一协议元数据。
     *
     * @param code 稳定结果码
     * @param message 有界说明
     * @param artifacts 产生或推荐读取的 Artifact
     * @param resultArtifactId 可选主结果 Artifact ID
     */
    public record ResultMetadata(
            String code,
            String message,
            List<ArtifactReference> artifacts,
            Optional<String> resultArtifactId) {
        public ResultMetadata {
            code = requireText(code, "code", CoordinationLimits.MAX_CODE_LENGTH);
            message = requireText(
                    message, "message", CoordinationLimits.MAX_MESSAGE_LENGTH);
            artifacts = List.copyOf(requireNonNull(artifacts, "artifacts"));
            if (artifacts.size() > CoordinationLimits.MAX_ARTIFACTS) {
                throw new IllegalArgumentException(
                        "artifacts exceeds " + CoordinationLimits.MAX_ARTIFACTS);
            }
            if (artifacts.stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException("artifacts must not contain null values");
            }
            if (new HashSet<>(artifacts.stream().map(ArtifactReference::artifactId).toList()).size()
                    != artifacts.size()) {
                throw new IllegalArgumentException("artifacts must have unique artifactId values");
            }
            resultArtifactId = requireNonNull(resultArtifactId, "resultArtifactId")
                    .map(value -> requireText(
                            value, "resultArtifactId", CoordinationLimits.MAX_ID_LENGTH));
            String primaryArtifactId = resultArtifactId.orElse(null);
            if (primaryArtifactId != null
                    && artifacts.stream().noneMatch(value ->
                            value.artifactId().equals(primaryArtifactId))) {
                throw new IllegalArgumentException(
                        "resultArtifactId must reference one of the result artifacts");
            }
        }

        /** 创建不产生 Artifact 的结果说明。 */
        public static ResultMetadata withoutArtifacts(String code, String message) {
            return new ResultMetadata(code, message, List.of(), Optional.empty());
        }
    }

    private static String requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be non-blank without surrounding whitespace");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
