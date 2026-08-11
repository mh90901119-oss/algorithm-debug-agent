package org.example.algorithmdebug.adapter.waferdemo;

import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Path;
import java.util.Map;

/** 当前 Wafer Demo 已验证 UT 与输入、结果相对路径的只读目录。 */
final class WaferDemoCaseCatalog {

    static final String TEST_CLASS = "org.example.scheduler.wafer.WaferSchedulingReproductionTest";

    private static final String REPRODUCTION_METHOD =
            "reproduceComplexSchedulingFromTimestampedInput";

    private static final Map<String, CaseDefinition> CASES = Map.of(
            REPRODUCTION_METHOD,
            definition("20260810101501.json"));

    private WaferDemoCaseCatalog() {
    }

    static CaseDefinition requireCase(TargetTest targetTest) throws AdapterException {
        if (targetTest == null || !TEST_CLASS.equals(targetTest.className())) {
            throw unsupported(targetTest);
        }
        CaseDefinition definition = CASES.get(targetTest.methodName());
        if (definition == null) {
            throw unsupported(targetTest);
        }
        return definition;
    }

    static Path complexInputRelativePath() {
        return CASES.get(REPRODUCTION_METHOD).inputRelativePath();
    }

    private static CaseDefinition definition(String inputName) {
        return new CaseDefinition(
                Path.of("input", "cases", inputName),
                Path.of("output", "algorithm-results"));
    }

    private static AdapterException unsupported(TargetTest targetTest) {
        String selector = targetTest == null ? "null" : targetTest.selector();
        return new AdapterException(
                "ADAPTER_TEST_NOT_SUPPORTED",
                "Wafer Demo Adapter 不支持目标测试: " + selector);
    }

    record CaseDefinition(Path inputRelativePath, Path resultDirectoryRelativePath) {
    }
}
