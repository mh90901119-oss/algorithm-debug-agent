package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.core.CaseApplicationService;
import org.example.algorithmdebug.core.DoctorApplicationService;
import org.example.algorithmdebug.core.ProjectApplicationService;
import org.example.algorithmdebug.core.RunApplicationService;
import org.example.algorithmdebug.core.WorkspaceApplicationService;
import org.example.algorithmdebug.core.StaticAnalysisApplicationService;
import org.example.algorithmdebug.core.CollectionApplicationService;
import org.example.algorithmdebug.core.JdwpCollectionApplicationService;
import org.example.algorithmdebug.plan.CodePathPlanRequest;
import org.example.algorithmdebug.plan.JdwpPlanRequest;
import org.example.algorithmdebug.contracts.AnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** 将 CLI 命令映射到 Core 用例，不处理序列化或文件布局。 */
public final class CliCommandExecutor {

    private final WorkspaceApplicationService workspaceService;
    private final ProjectApplicationService projectService;
    private final DoctorApplicationService doctorService;
    private final CaseApplicationService caseService;
    private final RunApplicationService runService;
    private final StaticAnalysisApplicationService staticAnalysisService;
    private final CollectionApplicationService collectionService;
    private final JdwpCollectionApplicationService jdwpCollectionService;

    private static final int MAX_QUESTION_BYTES = 65_536;
    private static final int MAX_ANALYSIS_RESULT_BYTES = 256 * 1024;

    /**
     * 创建 CLI 命令执行器。
     *
     * @param workspaceService Workspace Core 服务
     * @param projectService Project Core 服务
     * @param doctorService Doctor Core 服务
     * @param caseService Case Core 服务
     * @param runService Run Core 服务
     */
    public CliCommandExecutor(
            WorkspaceApplicationService workspaceService,
            ProjectApplicationService projectService,
            DoctorApplicationService doctorService,
            CaseApplicationService caseService,
            RunApplicationService runService,
            StaticAnalysisApplicationService staticAnalysisService,
            CollectionApplicationService collectionService) {
        this(workspaceService, projectService, doctorService, caseService, runService,
                staticAnalysisService, collectionService, null);
    }

    /** 创建同时支持 CodePath 和 JDWP 的 CLI 命令执行器。 */
    public CliCommandExecutor(
            WorkspaceApplicationService workspaceService,
            ProjectApplicationService projectService,
            DoctorApplicationService doctorService,
            CaseApplicationService caseService,
            RunApplicationService runService,
            StaticAnalysisApplicationService staticAnalysisService,
            CollectionApplicationService collectionService,
            JdwpCollectionApplicationService jdwpCollectionService) {
        if (workspaceService == null || projectService == null || doctorService == null
                || caseService == null || runService == null || staticAnalysisService == null
                || collectionService == null) {
            throw new IllegalArgumentException("CLI Core 服务不能为空");
        }
        this.workspaceService = workspaceService;
        this.projectService = projectService;
        this.doctorService = doctorService;
        this.caseService = caseService;
        this.runService = runService;
        this.staticAnalysisService = staticAnalysisService;
        this.collectionService = collectionService;
        this.jdwpCollectionService = jdwpCollectionService;
    }

    /**
     * 执行命令并返回对应的版本化结果 DTO。
     *
     * @param command 已解析命令
     * @return 对应命令的版本化结果 DTO
     */
    public Object execute(CliCommand command) {
        if (command instanceof CliCommand.WorkspaceInit workspaceInit) {
            return workspaceService.initialize(workspaceInit.root());
        }
        if (command instanceof CliCommand.ProjectRegister projectRegister) {
            return projectService.register(
                    projectRegister.workspace(), projectRegister.module(), projectRegister.projectId());
        }
        if (command instanceof CliCommand.Doctor doctor) {
            return doctorService.diagnose(doctor.workspace(), doctor.module(), Optional.empty());
        }
        if (command instanceof CliCommand.CaseOpen open) {
            return caseService.open(
                    open.workspace(), open.projectId(), open.targetTest(),
                    readQuestion(open.questionFile()), open.caseId(), open.adapterId(),
                    open.contextMode());
        }
        if (command instanceof CliCommand.CaseInspect inspect) {
            return caseService.inspect(
                    inspect.workspace(), inspect.projectId(), inspect.caseId());
        }
        if (command instanceof CliCommand.RunExecute run) {
            return runService.execute(
                    run.workspace(), run.projectId(), run.caseId(), run.analysisId());
        }
        if (command instanceof CliCommand.StaticAnalyze analyze) {
            return staticAnalysisService.analyze(
                    analyze.workspace(), analyze.projectId(), analyze.caseId(), analyze.analysisId());
        }
        if (command instanceof CliCommand.CodePathPlanCreate create) {
            return staticAnalysisService.createCodePathPlan(
                    create.workspace(), create.projectId(), create.caseId(), create.analysisId(),
                    readPlanRequest(create.requestFile()));
        }
        if (command instanceof CliCommand.CodePathCollectionExecute collect) {
            return collectionService.executeCodePath(
                    collect.workspace(), collect.projectId(), collect.caseId(), collect.planId());
        }
        if (command instanceof CliCommand.JdwpPlanCreate create) {
            return staticAnalysisService.createJdwpPlan(
                    create.workspace(), create.projectId(), create.caseId(), create.analysisId(),
                    readJdwpPlanRequest(create.requestFile()));
        }
        if (command instanceof CliCommand.JdwpCollectionExecute collect) {
            if (jdwpCollectionService == null) {
                throw new org.example.algorithmdebug.core.CaseRunException(
                        "JDWP_TOOL_NOT_CONFIGURED", "JDWP Collector 未配置");
            }
            return jdwpCollectionService.execute(
                    collect.workspace(), collect.projectId(), collect.caseId(), collect.planId());
        }
        if (command instanceof CliCommand.ArtifactRead read) {
            return caseService.readArtifact(
                    read.workspace(), read.projectId(), read.caseId(), read.artifactId(),
                    read.offsetBytes(), read.maxBytes());
        }
        if (command instanceof CliCommand.AnalysisComplete complete) {
            return caseService.completeAnalysis(
                    complete.workspace(), complete.projectId(), complete.caseId(),
                    complete.analysisId(), readAnalysisResult(complete.resultFile()));
        }
        throw new IllegalArgumentException("不支持的 CLI 命令类型");
    }

    /** 严格读取 64 KiB 内 UTF-8 普通问题文件；供命令测试复用。 */
    static String readQuestion(Path path) {
        if (path == null) {
            throw new CliInputException("question-file 不能为空");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new CliInputException("question-file 不存在或不是普通文件");
        }
        byte[] bytes;
        try (java.io.InputStream input = Files.newInputStream(normalized)) {
            bytes = input.readNBytes(MAX_QUESTION_BYTES + 1);
        } catch (IOException | SecurityException failure) {
            throw new CliInputException("无法读取 question-file", failure);
        }
        if (bytes.length > MAX_QUESTION_BYTES) {
            throw new CliInputException("question-file 超过 64 KiB");
        }
        String question;
        try {
            question = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new CliInputException("question-file 不是有效 UTF-8", failure);
        }
        if (question.startsWith("\uFEFF")) {
            question = question.substring(1);
        }
        if (question.isBlank()) {
            throw new CliInputException("question-file 内容不能为空");
        }
        return question;
    }

    /** 严格读取 64 KiB 内的 CodePath 计划请求 JSON。 */
    static CodePathPlanRequest readPlanRequest(Path path) {
        byte[] bytes = readBoundedFile(path, "request-file");
        String json = decodeUtf8(bytes, "request-file");
        try {
            return new ObjectMapper().registerModule(new JavaTimeModule())
                    .readValue(json, CodePathPlanRequest.class);
        } catch (IOException | RuntimeException failure) {
            throw new CliInputException("request-file 不是有效的 CodePathPlanRequest JSON", failure);
        }
    }

    /** 严格读取 64 KiB 内且不允许未知字段的 JDWP 计划请求 JSON。 */
    static JdwpPlanRequest readJdwpPlanRequest(Path path) {
        byte[] bytes = readBoundedFile(path, "request-file");
        String json = decodeUtf8(bytes, "request-file");
        try {
            return new ObjectMapper().registerModule(new JavaTimeModule())
                    .readValue(json, JdwpPlanRequest.class);
        } catch (IOException | RuntimeException failure) {
            throw new CliInputException("request-file 不是有效的 JdwpPlanRequest JSON", failure);
        }
    }

    /** 严格读取 256 KiB 内的 AnalysisResult JSON。 */
    static AnalysisResult readAnalysisResult(Path path) {
        byte[] bytes = readBoundedFile(path, "result-file", MAX_ANALYSIS_RESULT_BYTES);
        String json = decodeUtf8(bytes, "result-file");
        try {
            return new ObjectMapper().registerModule(new JavaTimeModule())
                    .readValue(json, AnalysisResult.class);
        } catch (IOException | RuntimeException failure) {
            throw new CliInputException("result-file 不是有效的 AnalysisResult JSON", failure);
        }
    }

    private static String decodeUtf8(byte[] bytes, String label) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return value.startsWith("\uFEFF") ? value.substring(1) : value;
        } catch (CharacterCodingException failure) {
            throw new CliInputException(label + " 不是有效 UTF-8", failure);
        }
    }

    private static byte[] readBoundedFile(Path path, String label) {
        return readBoundedFile(path, label, MAX_QUESTION_BYTES);
    }

    private static byte[] readBoundedFile(Path path, String label, int maximumBytes) {
        if (path == null) {
            throw new CliInputException(label + " 不能为空");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new CliInputException(label + " 不存在或不是普通文件");
        }
        try (java.io.InputStream input = Files.newInputStream(normalized)) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new CliInputException(label + " 超过读取预算");
            }
            return bytes;
        } catch (IOException | SecurityException failure) {
            throw new CliInputException("无法读取 " + label, failure);
        }
    }
}
