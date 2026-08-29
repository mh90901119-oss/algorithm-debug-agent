package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 一个目标算法 Maven 模块在 Agent Workspace 中的不可变注册信息。
 *
 * <p>仓库根目录与算法模块根目录分别保存，使大型软件仓库中的独立算法模块可以作为 Agent Project 使用。</p>
 *
 * @param schemaVersion 注册信息 Schema 版本
 * @param projectId Agent 内部项目 ID
 * @param displayName 面向用户的项目名称
 * @param repositoryRoot 大型软件 Git 仓库的规范化绝对路径
 * @param moduleRoot 含目标算法 pom.xml 的模块规范化绝对路径
 * @param mavenExecutionRoot 运行目标 UT 时使用的 Maven 工作目录
 * @param pomPath 相对 moduleRoot 的可移植 pom.xml 路径
 * @param buildTool 构建工具，当前固定为 MAVEN
 * @param registeredAt 注册时间
 */
@JsonIgnoreProperties("pomSha256")
public record ProjectRegistration(
        String schemaVersion,
        ProjectId projectId,
        String displayName,
        String repositoryRoot,
        String moduleRoot,
        String mavenExecutionRoot,
        String pomPath,
        String buildTool,
        String resultJsonDirectory,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant registeredAt) {

    private static final int MAX_DISPLAY_NAME_LENGTH = 256;
    private static final String MAVEN = "MAVEN";

    /** 校验版本、路径、构建工具和注册时指纹。 */
    public ProjectRegistration {
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.PROJECT_REGISTRATION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported ProjectRegistration schemaVersion: " + schemaVersion);
        }
        projectId = ContractChecks.requireNonNull(projectId, "projectId");
        displayName = ContractChecks.requireNonBlank(displayName, "displayName");
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("displayName length must not exceed " + MAX_DISPLAY_NAME_LENGTH);
        }
        repositoryRoot = ContractChecks.requireNonBlank(repositoryRoot, "repositoryRoot");
        moduleRoot = ContractChecks.requireNonBlank(moduleRoot, "moduleRoot");
        mavenExecutionRoot = ContractChecks.requireNonBlank(mavenExecutionRoot, "mavenExecutionRoot");
        pomPath = ContractChecks.requirePortableRelativePath(pomPath, "pomPath");
        buildTool = ContractChecks.requireNonBlank(buildTool, "buildTool");
        if (!MAVEN.equals(buildTool)) {
            throw new IllegalArgumentException("Only the MAVEN build tool is supported: " + buildTool);
        }
        if (resultJsonDirectory != null) {
            resultJsonDirectory = validateResultJsonDirectory(resultJsonDirectory);
        }
        registeredAt = ContractChecks.requireNonNull(registeredAt, "registeredAt");
    }

    /** 校验并返回项目相对的算法 JSON 结果目录，供确定性配置读取复用。 */
    public static String validateResultJsonDirectory(String value) {
        Path configuredPath = Path.of(value);
        if (configuredPath.isAbsolute()) {
            return configuredPath.normalize().toString().replace('\\', '/');
        }
        return ContractChecks.requirePortableRelativePath(value, "resultJsonDirectory");
    }

    /** 兼容未配置结果目录的既有调用方和旧版 project.json。 */
    public ProjectRegistration(
            String schemaVersion,
            ProjectId projectId,
            String displayName,
            String repositoryRoot,
            String moduleRoot,
            String mavenExecutionRoot,
            String pomPath,
            String buildTool,
            Instant registeredAt) {
        this(schemaVersion, projectId, displayName, repositoryRoot, moduleRoot,
                mavenExecutionRoot, pomPath, buildTool, null, registeredAt);
    }
}
