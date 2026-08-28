package org.example.algorithmdebug.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Comparator;
import java.util.List;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;

/** 将 Agent JDWP Plan 映射为锁定外部 Collector 可以直接解析的稳定 JSON。 */
public final class CollectorDebugPlanWriter {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    /**
     * @param plan 已通过源码和能力校验的 Agent Plan
     * @param port 本次目标 JVM 的 loopback 监听端口
     * @return 稳定 UTF-8 Collector JSON
     * @throws PlanCompilationException 端口或 JSON 序列化失败
     */
    public byte[] write(JdwpCollectionPlan plan, int port) {
        if (plan == null) {
            throw new IllegalArgumentException("plan 不能为空");
        }
        if (port < 1 || port > 65_535) {
            throw new PlanCompilationException("JDWP loopback port 必须在 1 到 65535 之间");
        }
        List<CollectorDebugPlan.Tracepoint> points = plan.tracepoints().stream()
                .sorted(Comparator.comparing(point -> point.tracepointId()))
                .map(point -> {
            var capture = point.capture();
            return new CollectorDebugPlan.Tracepoint(
                    point.tracepointId(), point.sourceAnchor().className(), point.line(),
                    point.sourceAnchor().methodName(), point.sourceAnchor().descriptor(),
                    point.maxHits(), point.captureOnHits(),
                    new CollectorDebugPlan.Capture(
                            capture.locals(), capture.stack(), capture.maxFrames(),
                            capture.maxDepth(), capture.maxItems(), capture.maxStringLength(),
                            capture.localNames(), capture.fieldPaths()));
                }).toList();
        CollectorDebugPlan document = new CollectorDebugPlan(
                "2.0", plan.planId().value(), new CollectorDebugPlan.Target("127.0.0.1", port),
                true, plan.budget().idleTimeoutMillis(), plan.budget().maxEvents(), points);
        try {
            return mapper.writeValueAsBytes(document);
        } catch (JsonProcessingException failure) {
            throw new PlanCompilationException("无法序列化 Collector JDWP Plan", failure);
        }
    }
}
