package org.example.algorithmdebug.contracts.investigation;

/** Source Query 默认预算和硬上限的唯一来源。 */
public final class SourceQueryLimits {
    /** 每 KiB 字节数。 */
    public static final int BYTES_PER_KIBIBYTE = 1_024;
    /** Source Query 唯一允许引用的方法目录 Artifact 类型。 */
    public static final String METHOD_CATALOG_ARTIFACT_TYPE = "METHOD_CATALOG";
    /** Java 相对符号允许的确定性语法；明确排除正则和 glob。 */
    public static final String SYMBOL_PATTERN = "[A-Za-z_$][A-Za-z0-9_$.#]*";
    /** 默认返回方法数。 */
    public static final int DEFAULT_METHODS = 20;
    /** 默认返回调用边数。 */
    public static final int DEFAULT_EDGES = 50;
    /** 默认图搜索深度。 */
    public static final int DEFAULT_DEPTH = 3;
    /** 默认返回路径数。 */
    public static final int DEFAULT_PATHS = 5;
    /** 默认返回源码行数。 */
    public static final int DEFAULT_SOURCE_LINES = 200;
    /** 默认 UTF-8 响应字节预算。 */
    public static final int DEFAULT_RESPONSE_BYTES = 64 * BYTES_PER_KIBIBYTE;

    /** 请求可指定的最大方法数。 */
    public static final int MAX_METHODS = 100;
    /** 请求可指定的最大调用边数。 */
    public static final int MAX_EDGES = 500;
    /** 请求可指定的最大图搜索深度。 */
    public static final int MAX_DEPTH = 8;
    /** 请求可指定的最大路径数。 */
    public static final int MAX_PATHS = 20;
    /** 请求可指定的最大源码行数。 */
    public static final int MAX_SOURCE_LINES = 500;
    /** 请求可指定的最大 UTF-8 响应字节数。 */
    public static final int MAX_RESPONSE_BYTES = 256 * BYTES_PER_KIBIBYTE;
    /** 查询符号最大字符数。 */
    public static final int MAX_SYMBOL_LENGTH = 512;
    /** 方法键最大字符数。 */
    public static final int MAX_METHOD_KEY_LENGTH = 1_024;
    /** 单次查询最大源码窗口数。 */
    public static final int MAX_SOURCE_WINDOWS = 16;

    private SourceQueryLimits() {
    }
}
