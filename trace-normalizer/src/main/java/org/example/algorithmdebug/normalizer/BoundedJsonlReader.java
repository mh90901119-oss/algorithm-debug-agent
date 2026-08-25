package org.example.algorithmdebug.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.example.algorithmdebug.contracts.NormalizationBudget;

/** 以固定缓冲区逐行读取 JSONL，并在解析前执行字节和记录硬预算。 */
public final class BoundedJsonlReader {

    private static final int BUFFER_BYTES = 8 * 1024;
    private final ObjectMapper mapper;

    /** 使用不启用多态类型的普通 Jackson Mapper。 */
    public BoundedJsonlReader() {
        this.mapper = new ObjectMapper();
    }

    /**
     * 流式读取 JSON object 记录。
     *
     * @param input 不可变 JSONL Artifact
     * @param maxInputBytes 文件字节预算
     * @param maxRecordBytes 单条记录 UTF-8 字节预算
     * @param maxRecords 记录数预算
     * @param consumer 按原始行号消费单条 JSON object
     * @throws NormalizationException 文件、编码、JSON 或预算不合法
     */
    public void read(
            Path input,
            long maxInputBytes,
            int maxRecordBytes,
            long maxRecords,
            JsonRecordConsumer consumer) {
        validateArguments(input, maxInputBytes, maxRecordBytes, maxRecords, consumer);
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(input)) {
            throw failure("NORMALIZE_INPUT_MISSING", "Raw JSONL 不存在或不是普通文件", 0, null);
        }
        try {
            long size = Files.size(input);
            if (size > maxInputBytes) {
                throw failure("NORMALIZE_INPUT_TOO_LARGE", "Raw JSONL 超过输入字节预算", 0, null);
            }
            stream(input, maxInputBytes, maxRecordBytes, maxRecords, consumer);
        } catch (NormalizationException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw failure("NORMALIZE_INPUT_READ_FAILED", "读取 Raw JSONL 失败", 0, failure);
        }
    }

    private void stream(
            Path input,
            long maxInputBytes,
            int maxRecordBytes,
            long maxRecords,
            JsonRecordConsumer consumer) throws IOException {
        byte[] readBuffer = new byte[BUFFER_BYTES];
        ByteArrayOutputStream record = new ByteArrayOutputStream(Math.min(maxRecordBytes, BUFFER_BYTES));
        long line = 1;
        long records = 0;
        long inputBytes = 0;
        try (InputStream stream = Files.newInputStream(input)) {
            int count;
            while ((count = stream.read(readBuffer)) >= 0) {
                inputBytes += count;
                if (inputBytes > maxInputBytes) {
                    throw failure(
                            "NORMALIZE_INPUT_TOO_LARGE",
                            "Raw JSONL 超过输入字节预算", line, null);
                }
                for (int index = 0; index < count; index++) {
                    byte value = readBuffer[index];
                    if (value == '\n') {
                        records = consume(record, line, records, maxRecords, consumer);
                        record.reset();
                        line++;
                    } else {
                        if (record.size() >= maxRecordBytes) {
                            throw failure(
                                    "NORMALIZE_RECORD_TOO_LARGE",
                                    "JSONL 单条记录超过字节预算", line, null);
                        }
                        record.write(value);
                    }
                }
            }
        }
        if (record.size() > 0) {
            consume(record, line, records, maxRecords, consumer);
        }
    }

    private long consume(
            ByteArrayOutputStream record,
            long line,
            long records,
            long maxRecords,
            JsonRecordConsumer consumer) {
        byte[] bytes = record.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        if (records >= maxRecords) {
            throw failure(
                    "NORMALIZE_RECORD_LIMIT_EXCEEDED",
                    "JSONL 记录数超过预算", line, null);
        }
        String text = decode(bytes, length, line);
        try {
            JsonNode json = mapper.readTree(text);
            if (json == null || !json.isObject()) {
                throw failure(
                        "NORMALIZE_JSON_INVALID", "JSONL 记录必须是 JSON object", line, null);
            }
            consumer.accept(line, json);
            return records + 1;
        } catch (NormalizationException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw failure("NORMALIZE_JSON_INVALID", "JSONL 记录不是有效 JSON", line, failure);
        }
    }

    private static String decode(byte[] bytes, int length, long line) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException failure) {
            throw failure("NORMALIZE_UTF8_INVALID", "JSONL 记录不是合法 UTF-8", line, failure);
        }
    }

    private static void validateArguments(
            Path input,
            long maxInputBytes,
            int maxRecordBytes,
            long maxRecords,
            JsonRecordConsumer consumer) {
        if (input == null || consumer == null
                || maxInputBytes < 1 || maxInputBytes > NormalizationBudget.MAX_RAW_BYTES
                || maxRecordBytes < 1 || maxRecordBytes > NormalizationBudget.MAX_RECORD_BYTES
                || maxRecords < 1 || maxRecords > NormalizationBudget.MAX_RECORDS) {
            throw new IllegalArgumentException("JSONL Reader 参数或预算非法");
        }
    }

    private static NormalizationException failure(
            String code, String message, long line, Throwable cause) {
        return new NormalizationException(code, message, line, cause);
    }

    /** 单条 JSON object 的同步消费者；不得保留 Reader 的内部缓冲区。 */
    @FunctionalInterface
    public interface JsonRecordConsumer {
        /**
         * @param jsonlLine 从 1 开始的原始行号
         * @param json 当前 JSON object
         */
        void accept(long jsonlLine, JsonNode json);
    }
}
