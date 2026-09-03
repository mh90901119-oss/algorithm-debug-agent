package org.example.algorithmdebug.plan;

import java.util.Objects;

/** 大模型提交的可读 CodePath 标量投影。 */
public record CodePathProjectionRequest(String name, String path, boolean required) {

    public CodePathProjectionRequest {
        name = requireText(name, "name", 128);
        path = requireText(path, "path", 512);
    }

    private static String requireText(String value, String field, int maxLength) {
        String checked = Objects.requireNonNull(value, field).strip();
        if (checked.isEmpty() || checked.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain between 1 and " + maxLength + " characters");
        }
        return checked;
    }
}
