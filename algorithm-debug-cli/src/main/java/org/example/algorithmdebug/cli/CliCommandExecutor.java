package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.core.CaseApplicationService;
import org.example.algorithmdebug.core.DoctorApplicationService;
import org.example.algorithmdebug.core.ProjectApplicationService;
import org.example.algorithmdebug.core.RunApplicationService;
import org.example.algorithmdebug.core.WorkspaceApplicationService;

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

    private static final int MAX_QUESTION_BYTES = 65_536;

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
            RunApplicationService runService) {
        if (workspaceService == null || projectService == null || doctorService == null
                || caseService == null || runService == null) {
            throw new IllegalArgumentException("CLI Core 服务不能为空");
        }
        this.workspaceService = workspaceService;
        this.projectService = projectService;
        this.doctorService = doctorService;
        this.caseService = caseService;
        this.runService = runService;
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
                    readQuestion(open.questionFile()), open.caseId(), open.adapterId());
        }
        if (command instanceof CliCommand.CaseInspect inspect) {
            return caseService.inspect(
                    inspect.workspace(), inspect.projectId(), inspect.caseId());
        }
        if (command instanceof CliCommand.RunExecute run) {
            return runService.execute(
                    run.workspace(), run.projectId(), run.caseId(), run.analysisId());
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
}
