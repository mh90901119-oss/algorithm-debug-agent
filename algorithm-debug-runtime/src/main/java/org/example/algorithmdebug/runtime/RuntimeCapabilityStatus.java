package org.example.algorithmdebug.runtime;

/** 一个本机 Runtime 能力的 typed 可用性与稳定原因。 */
public record RuntimeCapabilityStatus(
        String capability,
        boolean available,
        String code,
        String message) {

    private static final int MAX_TEXT_LENGTH = 256;

    /** 校验名称、code 和说明均有界。 */
    public RuntimeCapabilityStatus {
        capability = requireText(capability, "capability");
        code = requireText(code, "code");
        message = requireText(message, "message");
    }

    /** 创建可用能力。 */
    public static RuntimeCapabilityStatus available(String capability, String code) {
        return new RuntimeCapabilityStatus(capability, true, code, capability + " is available");
    }

    /** 创建不可用能力。 */
    public static RuntimeCapabilityStatus unavailable(
            String capability, String code, String message) {
        return new RuntimeCapabilityStatus(capability, false, code, message);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
