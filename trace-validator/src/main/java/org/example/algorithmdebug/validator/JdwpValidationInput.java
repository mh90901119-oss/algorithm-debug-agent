package org.example.algorithmdebug.validator;

import java.nio.file.Path;
import java.time.Instant;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CollectionBaselineCheck;
import org.example.algorithmdebug.contracts.JdwpCollectionManifest;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpCollectionRecord;
import org.example.algorithmdebug.contracts.JdwpSnapshotSummary;
import org.example.algorithmdebug.contracts.NormalizationManifest;
import org.example.algorithmdebug.contracts.SourceSnapshot;

/** JDWP 采集证据执行确定性校验所需的不可变输入。 */
public record JdwpValidationInput(
        JdwpCollectionRecord collection,
        JdwpCollectionPlan plan,
        JdwpCollectionManifest collectorManifest,
        NormalizationManifest normalizationManifest,
        JdwpSnapshotSummary summary,
        CollectionBaselineCheck baselineCheck,
        ArtifactReference rawReference,
        Path rawPath,
        ArtifactReference summaryReference,
        Path summaryPath,
        Path planPath,
        SourceSnapshot expectedSource,
        SourceSnapshot observedSource,
        Instant validatedAt) {

    /** 校验所有必需对象，并把本地路径规范化为绝对路径。 */
    public JdwpValidationInput {
        collection = require(collection, "collection");
        plan = require(plan, "plan");
        collectorManifest = require(collectorManifest, "collectorManifest");
        normalizationManifest = require(normalizationManifest, "normalizationManifest");
        summary = require(summary, "summary");
        baselineCheck = require(baselineCheck, "baselineCheck");
        rawReference = require(rawReference, "rawReference");
        rawPath = normalize(rawPath, "rawPath");
        summaryReference = require(summaryReference, "summaryReference");
        summaryPath = normalize(summaryPath, "summaryPath");
        planPath = normalize(planPath, "planPath");
        expectedSource = require(expectedSource, "expectedSource");
        observedSource = require(observedSource, "observedSource");
        validatedAt = require(validatedAt, "validatedAt");
    }

    private static Path normalize(Path path, String name) {
        return require(path, name).toAbsolutePath().normalize();
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
