package org.example.algorithmdebug.contracts;

/**
 * Case Digest 读取子文档时保留的有界非致命归档告警。
 *
 * @param code 稳定告警码
 * @param message 不含堆栈的有界说明
 * @param relativePath 相对 Case 根目录的文档路径
 */
public record ArchiveWarning(String code, String message, String relativePath) {

    /** 校验告警码、说明和 Case 内相对路径。 */
    public ArchiveWarning {
        code = ContractChecks.requireOpaqueId(code, "code");
        message = ContractChecks.requireBoundedText(message, "message", 2_048, false);
        relativePath = ContractChecks.requirePortableRelativePath(relativePath, "relativePath");
    }
}
