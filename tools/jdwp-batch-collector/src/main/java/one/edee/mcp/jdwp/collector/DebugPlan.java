package one.edee.mcp.jdwp.collector;

import com.fasterxml.jackson.annotation.JsonAlias;
import one.edee.mcp.jdwp.core.SnapshotLimits;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON-serializable collection plan. Public fields intentionally keep the CLI format transparent.
 */
public final class DebugPlan {
    public String schemaVersion = "2.0";
    public String sessionId = "jdwp-collection";
    public Target target = new Target();
    public boolean resumeOnAttach = true;
    public long idleTimeoutMillis = 120_000;
    public int maxEvents = 100_000;
    public List<Tracepoint> tracepoints = new ArrayList<>();

    public void validate() {
        if (!"1.0".equals(schemaVersion) && !"2.0".equals(schemaVersion)
                && !"3.0".equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        target.validate();
        if (idleTimeoutMillis < 1_000) {
            throw new IllegalArgumentException("idleTimeoutMillis must be at least 1000");
        }
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
        if (tracepoints == null || tracepoints.isEmpty()) {
            throw new IllegalArgumentException("at least one tracepoint is required");
        }
        Set<String> ids = new HashSet<>();
        for (Tracepoint tracepoint : tracepoints) {
            tracepoint.validate();
            if (("2.0".equals(schemaVersion) || "3.0".equals(schemaVersion))
                && (tracepoint.methodDescriptor == null || tracepoint.methodDescriptor.isBlank())) {
                throw new IllegalArgumentException(
                    "tracepoint.methodDescriptor is required: " + tracepoint.id
                );
            }
            if (!ids.add(tracepoint.id)) {
                throw new IllegalArgumentException("duplicate tracepoint id: " + tracepoint.id);
            }
        }
    }

    public static final class Target {
        public String host = "localhost";
        public int port = 5005;

        void validate() {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("target.host must not be blank");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("target.port must be between 1 and 65535");
            }
        }
    }

    public static final class Tracepoint {
        public String id;
        public String className;
        public int line;
        public String methodName;
        public String methodDescriptor;
        @JsonAlias("maxHits")
        public int maxObservedHits = 10_000;
        public int maxCapturedHits = 20;
        @JsonAlias("captureOnHits")
        public List<Integer> captureOnMatchedHits = new ArrayList<>();
        public Condition condition;
        public Capture capture = new Capture();

        void validate() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("tracepoint.id must not be blank");
            }
            if (className == null || className.isBlank()) {
                throw new IllegalArgumentException("tracepoint.className must not be blank: " + id);
            }
            if (line < 1) {
                throw new IllegalArgumentException("tracepoint.line must be positive: " + id);
            }
            if (maxObservedHits < 1 || maxObservedHits > 10_000) {
                throw new IllegalArgumentException(
                    "tracepoint.maxObservedHits must be between 1 and 10000: " + id);
            }
            if (maxCapturedHits < 1 || maxCapturedHits > 20) {
                throw new IllegalArgumentException(
                    "tracepoint.maxCapturedHits must be between 1 and 20: " + id);
            }
            if (captureOnMatchedHits == null) {
                captureOnMatchedHits = new ArrayList<>();
            } else {
                int previous = 0;
                for (Integer hit : captureOnMatchedHits) {
                    if (hit == null || hit <= previous || hit > maxObservedHits) {
                        throw new IllegalArgumentException(
                            "tracepoint.captureOnMatchedHits must be strictly increasing and within maxObservedHits: " + id
                        );
                    }
                    previous = hit;
                }
                if (captureOnMatchedHits.size() > maxCapturedHits) {
                    throw new IllegalArgumentException(
                        "tracepoint.captureOnMatchedHits exceeds maxCapturedHits: " + id);
                }
                captureOnMatchedHits = new ArrayList<>(captureOnMatchedHits);
            }
            if (condition != null) {
                condition.validate(id);
            }
            if (capture == null) {
                capture = new Capture();
            }
            capture.validate(id);
        }
    }

    public static final class Capture {
        public boolean locals = true;
        public boolean stack = true;
        public int maxFrames = 8;
        public int maxDepth = SnapshotLimits.DEFAULT.maxDepth();
        public int maxItems = SnapshotLimits.DEFAULT.maxItems();
        public int maxStringLength = SnapshotLimits.DEFAULT.maxStringLength();
        public List<String> localNames = new ArrayList<>();
        public List<String> fieldPaths = new ArrayList<>();

        void validate(String tracepointId) {
            if (maxFrames < 1 || maxFrames > 256) {
                throw new IllegalArgumentException("capture.maxFrames must be between 1 and 256: " + tracepointId);
            }
            new SnapshotLimits(maxDepth, maxItems, maxStringLength);
            localNames = normalized(localNames, "capture.localNames", tracepointId);
            fieldPaths = normalized(fieldPaths, "capture.fieldPaths", tracepointId);
        }

        SnapshotLimits limits() {
            return new SnapshotLimits(maxDepth, maxItems, maxStringLength);
        }

        private static List<String> normalized(List<String> values, String name, String tracepointId) {
            if (values == null) {
                return new ArrayList<>();
            }
            List<String> result = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
            if (result.size() > 256) {
                throw new IllegalArgumentException(name + " must contain at most 256 entries: " + tracepointId);
            }
            return new ArrayList<>(result);
        }
    }

    public static final class Condition {
        public String localName;
        public List<String> fieldPath = new ArrayList<>();
        public String operator = "EQUALS";
        public String expectedType;
        public String expectedValue;

        void validate(String tracepointId) {
            if (localName == null || !("this".equals(localName)
                    || localName.matches("[A-Za-z_$][A-Za-z0-9_$]*"))) {
                throw new IllegalArgumentException(
                    "condition.localName must be a Java identifier or this: " + tracepointId);
            }
            fieldPath = fieldPath == null ? new ArrayList<>() : new ArrayList<>(fieldPath);
            if (fieldPath.size() > 8 || fieldPath.stream().anyMatch(segment -> segment == null
                    || !segment.matches("[A-Za-z_$][A-Za-z0-9_$]*"))) {
                throw new IllegalArgumentException(
                    "condition.fieldPath must contain at most 8 Java field identifiers: " + tracepointId);
            }
            if (!"EQUALS".equals(operator)) {
                throw new IllegalArgumentException(
                    "condition.operator must be EQUALS: " + tracepointId);
            }
            Set<String> types = Set.of(
                "STRING", "LONG", "DOUBLE", "BOOLEAN", "CHAR", "ENUM", "NULL");
            if (!types.contains(expectedType)) {
                throw new IllegalArgumentException(
                    "condition.expectedType is unsupported: " + tracepointId);
            }
            if ("NULL".equals(expectedType)) {
                if (expectedValue != null && !expectedValue.isBlank()) {
                    throw new IllegalArgumentException(
                        "NULL condition must not have expectedValue: " + tracepointId);
                }
                expectedValue = null;
            } else if (expectedValue == null || expectedValue.isBlank()
                    || expectedValue.length() > 1_024) {
                throw new IllegalArgumentException(
                    "condition.expectedValue is invalid: " + tracepointId);
            }
            try {
                switch (expectedType) {
                    case "LONG" -> Long.parseLong(expectedValue);
                    case "DOUBLE" -> Double.parseDouble(expectedValue);
                    case "BOOLEAN" -> {
                        if (!("true".equals(expectedValue) || "false".equals(expectedValue))) {
                            throw new IllegalArgumentException(
                                "condition BOOLEAN value must be true or false: " + tracepointId);
                        }
                    }
                    case "CHAR" -> {
                        if (expectedValue.codePointCount(0, expectedValue.length()) != 1) {
                            throw new IllegalArgumentException(
                                "condition CHAR value must contain one character: " + tracepointId);
                        }
                    }
                    default -> {
                        // STRING, ENUM and NULL need no additional scalar parsing.
                    }
                }
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                    "condition scalar value is malformed: " + tracepointId, failure);
            }
        }
    }

}
