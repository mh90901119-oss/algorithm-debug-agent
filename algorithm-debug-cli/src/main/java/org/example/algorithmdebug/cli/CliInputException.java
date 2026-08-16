package org.example.algorithmdebug.cli;

/** CLI 文件型输入不存在、超预算或编码无效。 */
final class CliInputException extends RuntimeException {

    CliInputException(String message) {
        super(message);
    }

    CliInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
