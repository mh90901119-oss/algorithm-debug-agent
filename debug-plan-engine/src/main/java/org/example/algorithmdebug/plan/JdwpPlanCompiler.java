package org.example.algorithmdebug.plan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpTracepointSpec;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.SchemaVersions;

/** 将模型提出的 JDWP 采集意图绑定到当前 MethodCatalog 和真实源码内容。 */
public final class JdwpPlanCompiler {

    /**
     * 解析方法身份、复验每个源码文件 Hash，并生成确定性排序的 Agent JDWP Plan。
     *
     * @param catalog 当前 Case/Context 的静态方法目录
     * @param request 大模型提出的采集意图
     * @param moduleRoot 已登记 Maven 算法模块根目录
     * @return 可归档且只包含当前 Collector 能力的计划
     * @throws PlanCompilationException 请求不安全、源码漂移或文件读取失败
     */
    public JdwpCollectionPlan compile(
            MethodCatalog catalog, JdwpPlanRequest request, Path moduleRoot) {
        if (catalog == null || request == null || moduleRoot == null) {
            throw new IllegalArgumentException("catalog、request 和 moduleRoot 不能为空");
        }
        if (request.tracepoints().isEmpty() || request.tracepoints().size() > 20) {
            throw new PlanCompilationException("JDWP 计划必须包含 1 到 20 个 tracepoint");
        }
        Map<String, MethodCatalogEntry> entries = catalog.entries().stream().collect(
                Collectors.toUnmodifiableMap(MethodCatalogEntry::methodKey, Function.identity()));
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JdwpTracepointRequest point : request.tracepoints()) {
            if (!ids.add(point.tracepointId())) {
                throw new PlanCompilationException("tracepointId 不得重复: " + point.tracepointId());
            }
        }
        Path realRoot = realModuleRoot(moduleRoot);
        var points = request.tracepoints().stream()
                .sorted(Comparator.comparing(JdwpTracepointRequest::tracepointId))
                .map(point -> compilePoint(entries, point, realRoot))
                .toList();
        try {
            return new JdwpCollectionPlan(
                    SchemaVersions.JDWP_COLLECTION_PLAN,
                    request.planId(), catalog.caseId(), catalog.contextId(), catalog.analysisId(),
                    catalog.targetTest(), catalog.sourceFingerprintSha256(), points,
                    request.budget(), request.rationale(), request.requestedAt());
        } catch (IllegalArgumentException failure) {
            throw new PlanCompilationException("JDWP 计划不满足安全契约: " + failure.getMessage(), failure);
        }
    }

    private static JdwpTracepointSpec compilePoint(
            Map<String, MethodCatalogEntry> entries,
            JdwpTracepointRequest request,
            Path realRoot) {
        MethodCatalogEntry entry = entries.get(request.methodKey());
        if (entry == null) {
            throw new PlanCompilationException(
                    "选择的方法不属于当前 MethodCatalog: " + request.methodKey());
        }
        var anchor = entry.sourceAnchor();
        Path source = resolveSource(realRoot, anchor.sourceRelativePath());
        String observedHash = sha256(source);
        if (!observedHash.equals(anchor.sourceSha256())) {
            throw new PlanCompilationException(
                    "JDWP_PLAN_SOURCE_DRIFT: " + anchor.sourceRelativePath());
        }
        try {
            return new JdwpTracepointSpec(
                    request.tracepointId(), entry.methodKey(), anchor,
                    request.line(), request.maxHits(), request.capture());
        } catch (IllegalArgumentException failure) {
            throw new PlanCompilationException(
                    "JDWP tracepoint 非法 " + request.tracepointId() + ": " + failure.getMessage(),
                    failure);
        }
    }

    private static Path realModuleRoot(Path moduleRoot) {
        try {
            Path root = moduleRoot.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(root)) {
                throw new PlanCompilationException("Maven 模块根目录不是目录: " + moduleRoot);
            }
            return root;
        } catch (IOException failure) {
            throw new PlanCompilationException("无法解析 Maven 模块根目录: " + moduleRoot, failure);
        }
    }

    private static Path resolveSource(Path realRoot, String relativePath) {
        Path candidate = realRoot.resolve(relativePath).normalize();
        if (!candidate.startsWith(realRoot)) {
            throw new PlanCompilationException("源码路径逃逸 Maven 模块: " + relativePath);
        }
        try {
            Path realSource = candidate.toRealPath();
            if (!realSource.startsWith(realRoot) || !Files.isRegularFile(realSource)) {
                throw new PlanCompilationException("源码不是模块内普通文件: " + relativePath);
            }
            return realSource;
        } catch (IOException failure) {
            throw new PlanCompilationException("无法读取 JDWP 锚点源码: " + relativePath, failure);
        }
    }

    private static String sha256(Path source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(source)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        } catch (IOException failure) {
            throw new PlanCompilationException("读取 JDWP 锚点源码失败: " + source, failure);
        }
    }
}
