package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.FailureCategory;
import org.example.algorithmdebug.contracts.TargetFailureDiagnostic;
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
    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})");

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
        String className = targetTest.substring(0, separator);
        String methodName = targetTest.substring(separator + 1);
        try (Stream<Path> paths = Files.list(reportsDirectory)) {
            for (Path report : paths.filter(path -> path.getFileName().toString().startsWith("TEST-"))
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                Optional<TargetFailureDiagnostic> result = readReport(report, className, methodName);
                if (result.isPresent()) {
                    return result;
                }
            }
            return Optional.empty();
        } catch (SurefireDiagnosticException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SurefireDiagnosticException("无法读取 Surefire 报告目录", exception);
        }
    }

    private Optional<TargetFailureDiagnostic> readReport(Path report, String className, String methodName)
            throws SurefireDiagnosticException {
        try {
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
            var testCases = document.getElementsByTagName("testcase");
            for (int index = 0; index < testCases.getLength(); index++) {
                Element testCase = (Element) testCases.item(index);
                if (!className.equals(testCase.getAttribute("classname"))
                        || !methodName.equals(testCase.getAttribute("name"))) {
                    continue;
                }
                Optional<TargetFailureDiagnostic> failure = diagnostic(
                        testCase, "failure", FailureCategory.TEST_FAILURE);
                return failure.isPresent() ? failure
                        : diagnostic(testCase, "error", FailureCategory.TEST_ERROR);
            }
            return Optional.empty();
        } catch (Exception exception) {
            throw new SurefireDiagnosticException("无法安全解析 Surefire XML: " + report.getFileName(), exception);
        }
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
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
        var nodes = testCase.getElementsByTagName(elementName);
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
                .findFirst().orElse("");
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
}
