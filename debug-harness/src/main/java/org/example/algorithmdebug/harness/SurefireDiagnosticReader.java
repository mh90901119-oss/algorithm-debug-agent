package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.FailureCategory;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.TargetFailureDiagnostic;
import org.example.algorithmdebug.contracts.TestOutcome;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 从 Maven Surefire XML 中提取与业务语义无关的目标测试失败事实。 */
public final class SurefireDiagnosticReader {
    private static final long DEFAULT_MAXIMUM_REPORT_BYTES = 10L * 1024 * 1024;
    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})");
    private final long maximumReportBytes;

    /** 使用 10 MiB 单报告预算创建 Reader。 */
    public SurefireDiagnosticReader() {
        this(DEFAULT_MAXIMUM_REPORT_BYTES);
    }

    /** @param maximumReportBytes 单个目标 Surefire XML 允许的最大字节数 */
    public SurefireDiagnosticReader(long maximumReportBytes) {
        if (maximumReportBytes <= 0) {
            throw new IllegalArgumentException("maximumReportBytes 必须为正数");
        }
        this.maximumReportBytes = maximumReportBytes;
    }

    /**
     * @param reportsDirectory Surefire 报告目录
     * @param targetTest `fully.qualified.Class#method` 格式目标测试
     * @return 匹配测试的失败事实；报告或失败节点不存在时为空
     * @throws SurefireDiagnosticException XML 不安全、格式损坏或读取失败
     */
    public Optional<TargetFailureDiagnostic> read(Path reportsDirectory, String targetTest)
            throws SurefireDiagnosticException {
        if (reportsDirectory == null || targetTest == null || targetTest.isBlank()) {
            throw new IllegalArgumentException("reportsDirectory 和 targetTest 不能为空");
        }
        if (!Files.isDirectory(reportsDirectory)) {
            return Optional.empty();
        }
        int separator = targetTest.lastIndexOf('#');
        if (separator <= 0 || separator == targetTest.length() - 1) {
            throw new IllegalArgumentException("targetTest 必须使用 fully.qualified.Class#method 格式");
        }
        TargetTest target = new TargetTest(
                targetTest.substring(0, separator), targetTest.substring(separator + 1));
        String className = target.className();
        String expectedFileName = "TEST-" + className + ".xml";
        try (Stream<Path> paths = Files.list(reportsDirectory)) {
            Comparator<Path> reportOrder = Comparator
                    .comparing((Path path) -> !path.getFileName().toString().equals(expectedFileName))
                    .thenComparing(Path::toString);
            for (Path report : paths.filter(path -> isTargetReport(
                            path.getFileName().toString(), expectedFileName))
                    .sorted(reportOrder).toList()) {
                Optional<SurefireTestResult> result = readResult(report, target);
                if (result.isPresent()) {
                    return result.orElseThrow().targetFailure();
                }
            }
            return Optional.empty();
        } catch (SurefireDiagnosticException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SurefireDiagnosticException("无法读取 Surefire 报告目录", exception);
        }
    }

    /**
     * 安全解析一份已由运行前后快照筛选出的目标报告。
     *
     * @param report 本次新增或变化的候选 XML
     * @param targetTest 精确目标 UT
     * @return 匹配 testcase 的结果；报告不包含目标 testcase 时为空
     */
    public Optional<SurefireTestResult> readResult(Path report, TargetTest targetTest)
            throws SurefireDiagnosticException {
        if (report == null || targetTest == null) {
            throw new IllegalArgumentException("report 和 targetTest 不能为空");
        }
        try {
            if (Files.size(report) > maximumReportBytes) {
                throw new SurefireDiagnosticException(
                        "Surefire XML 超过大小上限: " + report.getFileName());
            }
            var builder = secureFactory().newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void warning(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            var document = builder.parse(report.toFile());
            var testCases = document.getElementsByTagNameNS("*", "testcase");
            java.util.List<Element> matches = new java.util.ArrayList<>();
            for (int index = 0; index < testCases.getLength(); index++) {
                Element testCase = (Element) testCases.item(index);
                if (targetTest.className().equals(testCase.getAttribute("classname"))
                        && matchesMethod(targetTest.methodName(), testCase.getAttribute("name"))) {
                    matches.add(testCase);
                }
            }
            if (matches.isEmpty()) {
                return Optional.empty();
            }
            for (Element testCase : matches) {
                Optional<TargetFailureDiagnostic> error = diagnostic(
                        testCase, "error", FailureCategory.TEST_ERROR);
                if (error.isPresent()) {
                    return Optional.of(new SurefireTestResult(
                            TestOutcome.ERROR, error, report));
                }
            }
            for (Element testCase : matches) {
                Optional<TargetFailureDiagnostic> failure = diagnostic(
                        testCase, "failure", FailureCategory.TEST_FAILURE);
                if (failure.isPresent()) {
                    return Optional.of(new SurefireTestResult(
                            TestOutcome.FAILED, failure, report));
                }
            }
            for (Element testCase : matches) {
                Optional<TargetFailureDiagnostic> skipped = diagnostic(
                        testCase, "skipped", FailureCategory.TEST_NOT_EXECUTED);
                if (skipped.isPresent()) {
                    return Optional.of(new SurefireTestResult(
                            TestOutcome.NOT_EXECUTED, skipped, report));
                }
            }
            return Optional.of(new SurefireTestResult(
                    TestOutcome.PASSED, Optional.empty(), report));
        } catch (Exception exception) {
            if (exception instanceof SurefireDiagnosticException diagnosticException) {
                throw diagnosticException;
            }
            throw new SurefireDiagnosticException("无法安全解析 Surefire XML: " + report.getFileName(), exception);
        }
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static Optional<TargetFailureDiagnostic> diagnostic(
            Element testCase, String elementName, FailureCategory category) {
        var nodes = testCase.getElementsByTagNameNS("*", elementName);
        if (nodes.getLength() == 0) {
            return Optional.empty();
        }
        Element failure = (Element) nodes.item(0);
        String trace = failure.getTextContent() == null ? "" : failure.getTextContent();
        return Optional.of(new TargetFailureDiagnostic(category, failure.getAttribute("type"),
                normalizeMessage(failure.getAttribute("message")), extractCause(trace),
                extractBusinessFrame(trace)));
    }

    private static String normalizeMessage(String message) {
        String compact = message == null ? "" : message.strip().replaceAll("\\s+", " ");
        return TIMESTAMP.matcher(compact).replaceAll("<TIMESTAMP>");
    }

    private static String extractCause(String trace) {
        return trace.lines().map(String::strip)
                .filter(line -> line.startsWith("Caused by:"))
                .map(line -> line.substring("Caused by:".length()).strip())
                .reduce((ignored, deepest) -> deepest).orElse("");
    }

    private static String extractBusinessFrame(String trace) {
        return trace.lines().map(String::strip).filter(line -> line.startsWith("at "))
                .map(line -> line.substring(3)).filter(frame -> !isFrameworkFrame(frame))
                .findFirst().orElse("");
    }

    private static boolean isFrameworkFrame(String frame) {
        return frame.startsWith("java.") || frame.startsWith("java.base/")
                || frame.startsWith("jdk.") || frame.startsWith("sun.")
                || frame.startsWith("org.junit.") || frame.startsWith("org.opentest4j.")
                || frame.startsWith("org.apache.maven.") || frame.startsWith("org.apache.surefire.");
    }

    private static boolean isTargetReport(String fileName, String expectedFileName) {
        if (fileName.equals(expectedFileName)) {
            return true;
        }
        String expectedPrefix = expectedFileName.substring(0, expectedFileName.length() - ".xml".length());
        return fileName.startsWith(expectedPrefix + "-") && fileName.endsWith(".xml");
    }

    private static boolean matchesMethod(String methodName, String reportedName) {
        return reportedName.equals(methodName)
                || reportedName.startsWith(methodName + "[")
                || reportedName.startsWith(methodName + "(");
    }
}
