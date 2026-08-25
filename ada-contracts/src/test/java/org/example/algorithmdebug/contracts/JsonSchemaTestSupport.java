package org.example.algorithmdebug.contracts;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;

/** 测试期使用正式 Draft 2020-12 校验器验证真实 JSON，而非只比较字段名。 */
final class JsonSchemaTestSupport {
    private JsonSchemaTestSupport() {
    }

    static void assertValid(Path schemaPath, String instanceJson) throws Exception {
        String schemaJson = Files.readString(schemaPath, StandardCharsets.UTF_8);
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaJson, InputFormat.JSON);
        var errors = schema.validate(instanceJson, InputFormat.JSON);
        Assertions.assertTrue(errors.isEmpty(), () -> "Schema validation failed: " + errors);
    }
}
