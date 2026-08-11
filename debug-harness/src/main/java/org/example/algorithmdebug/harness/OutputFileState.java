package org.example.algorithmdebug.harness;

import java.nio.file.Path;

/**
 * 一次目录快照中的文件元数据。
 *
 * @param relativePath 相对于结果源目录的规范化路径
 * @param sizeBytes 文件大小
 * @param lastModifiedMillis 最后修改时间
 */
public record OutputFileState(Path relativePath, long sizeBytes, long lastModifiedMillis) {

    /** 校验相对路径和文件元数据。 */
    public OutputFileState {
        if (relativePath == null || relativePath.isAbsolute() || relativePath.normalize().startsWith("..")) {
            throw new IllegalArgumentException("relativePath 必须是安全相对路径");
        }
        relativePath = relativePath.normalize();
        if (sizeBytes < 0 || lastModifiedMillis < 0) {
            throw new IllegalArgumentException("文件元数据不能为负数");
        }
    }
}
