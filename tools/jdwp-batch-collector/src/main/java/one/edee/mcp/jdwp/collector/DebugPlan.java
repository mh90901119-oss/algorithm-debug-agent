package one.edee.mcp.jdwp.collector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JSON-serializable collection plan. Public fields intentionally keep the CLI format transparent. */
public final class DebugPlan {
    public String schemaVersion = "5.0";
    public String sessionId = "jdwp-collection";
    public Target target = new Target();
    public boolean resumeOnAttach = true;
    public long idleTimeoutMillis = 120_000;
    public int maxEvents = 100_000;
    public List<Tracepoint> tracepoints = new ArrayList<>();

    public void validate() {
        if (!"5.0".equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (target == null) throw new IllegalArgumentException("target is required");
        target.validate();
        if (idleTimeoutMillis < 1_000) {
            throw new IllegalArgumentException("idleTimeoutMillis must be at least 1000");
        }
        if (maxEvents < 1) throw new IllegalArgumentException("maxEvents must be positive");
        if (tracepoints == null || tracepoints.isEmpty() || tracepoints.size() > 20) {
            throw new IllegalArgumentException("tracepoints must contain between 1 and 20 entries");
        }
        Set<String> ids = new HashSet<>();
        for (Tracepoint tracepoint : tracepoints) {
            tracepoint.validate();
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
        public int maxObservedHits = 1_000;
        public int maxCapturedHits = 20;
        public int captureFirstMatchedHits = 5;
        public int captureEveryMatchedHits = 5;
        public List<Condition> conditions = new ArrayList<>();
        public Capture capture = new Capture();

        void validate() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("tracepoint.id must not be blank");
            }
            if (className == null || className.isBlank()) {
                throw new IllegalArgumentException("tracepoint.className must not be blank: " + id);
            }
            if (methodName == null || methodName.isBlank()
                    || methodDescriptor == null || methodDescriptor.isBlank()) {
                throw new IllegalArgumentException(
                        "tracepoint method identity is required: " + id);
            }
            if (line < 1) {
                throw new IllegalArgumentException("tracepoint.line must be positive: " + id);
            }
            if (maxObservedHits < 1 || maxObservedHits > 100_000) {
                throw new IllegalArgumentException(
                        "tracepoint.maxObservedHits must be between 1 and 100000: " + id);
            }
            if (maxCapturedHits < 1 || maxCapturedHits > 200) {
                throw new IllegalArgumentException(
                        "tracepoint.maxCapturedHits must be between 1 and 200: " + id);
            }
            if (captureFirstMatchedHits < 0 || captureFirstMatchedHits > maxCapturedHits) {
                throw new IllegalArgumentException(
                        "tracepoint.captureFirstMatchedHits is outside maxCapturedHits: " + id);
            }
            if (captureEveryMatchedHits < 0 || captureEveryMatchedHits > maxObservedHits
                    || (captureFirstMatchedHits == 0 && captureEveryMatchedHits == 0)) {
                throw new IllegalArgumentException(
                        "tracepoint matched-hit sampling policy is invalid: " + id);
            }
            conditions = conditions == null ? new ArrayList<>() : new ArrayList<>(conditions);
            if (conditions.size() > 4) {
                throw new IllegalArgumentException(
                        "tracepoint.conditions supports at most 4 entries: " + id);
            }
            conditions.forEach(condition -> condition.validate(id));
            if (capture == null) capture = new Capture();
            capture.validate(id);
        }
    }

    public static final class Capture {
        public boolean stack = true;
        public int maxFrames = 8;
        public int maxStringLength = 256;
        public List<String> valuePaths = new ArrayList<>();

        void validate(String tracepointId) {
            if (maxFrames < 1 || maxFrames > 64) {
                throw new IllegalArgumentException(
                        "capture.maxFrames must be between 1 and 64: " + tracepointId);
            }
            if (maxStringLength < 16 || maxStringLength > 1_024) {
                throw new IllegalArgumentException(
                        "capture.maxStringLength must be between 16 and 1024: " + tracepointId);
            }
            valuePaths = valuePaths == null ? new ArrayList<>() : new ArrayList<>(valuePaths);
            if (!stack && valuePaths.isEmpty()) {
                throw new IllegalArgumentException(
                        "capture must request stack or valuePaths: " + tracepointId);
            }
            if (valuePaths.size() > 128 || new HashSet<>(valuePaths).size() != valuePaths.size()) {
                throw new IllegalArgumentException(
                        "capture.valuePaths must be unique and contain at most 128 entries: "
                                + tracepointId);
            }
            valuePaths.forEach(path -> validateValuePath(path, "capture.valuePaths", tracepointId));
        }
    }

    public static final class Condition {
        public String valuePath;
        public String operator = "EQUALS";
        public String expectedType;
        public String expectedValue;

        void validate(String tracepointId) {
            validateValuePath(valuePath, "condition.valuePath", tracepointId);
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
            validateScalar(tracepointId);
        }

        private void validateScalar(String tracepointId) {
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
                    default -> { }
                }
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        "condition scalar value is malformed: " + tracepointId, failure);
            }
        }
    }

    private static void validateValuePath(String path, String name, String tracepointId) {
        if (path == null || path.isBlank() || path.length() > 2_048) {
            throw new IllegalArgumentException(name + " contains an invalid path: " + tracepointId);
        }
        String[] segments = path.split("\\.", -1);
        if (segments.length > 8) {
            throw new IllegalArgumentException(name + " exceeds 8 segments: " + tracepointId);
        }
        for (String segment : segments) {
            if (!segment.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException(
                        name + " contains a non-Java identifier: " + tracepointId);
            }
        }
    }
}
