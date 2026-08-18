import { mkdtemp, rm, writeFile } from "node:fs/promises"
import { homedir, tmpdir } from "node:os"
import { isAbsolute, join, relative, resolve } from "node:path"

const QUESTION_LIMIT_BYTES = 64 * 1024
const REQUEST_LIMIT_BYTES = 64 * 1024
const RESULT_LIMIT_BYTES = 256 * 1024
const DEFAULT_ARTIFACT_BYTES = 16 * 1024
const MAXIMUM_ARTIFACT_BYTES = 64 * 1024

/**
 * 创建 OpenCode Tool 的薄编排层。它只处理工作区准备、参数映射和有界临时文件，
 * 领域校验仍由 Java CLI 完成。
 *
 * @param {{
 *   execute: (args: string[], cwd: string) => Promise<string>,
 *   environment?: Record<string, string | undefined>,
 *   platform?: string,
 *   temporaryRoot?: string
 * }} dependencies 可替换的进程与环境依赖
 */
export function createAlgorithmDebugRuntime({
  execute,
  environment = process.env,
  platform = process.platform,
  temporaryRoot = tmpdir(),
}) {
  if (typeof execute !== "function") throw new TypeError("execute 必须是函数")
  const workspace = workspacePath(environment, platform)
  const tempRoot = resolve(requiredText(temporaryRoot, "temporaryRoot"))

  async function invoke(args, context) {
    const cwd = contextDirectory(context)
    let prepared
    try {
      prepared = await prepare(cwd)
    } catch {
      return failure("ADA_PROJECT_PREPARATION_FAILED")
    }
    if (!prepared.success) return prepared.response
    try {
      return await execute([
        ...args.command,
        "--workspace", workspace,
        "--project-id", prepared.projectId,
        ...args.options,
      ], cwd)
    } catch {
      return failure("ADA_CLI_EXECUTION_FAILED")
    }
  }

  async function prepare(cwd) {
    const initialized = await execute(["workspace", "init", "--root", workspace], cwd)
    if (!successful(initialized)) return { success: false, response: initialized }

    const registered = await execute([
      "project", "register", "--workspace", workspace, "--project", cwd,
    ], cwd)
    const value = parseResponse(registered)
    if (!value?.success) return { success: false, response: registered }
    const projectId = value.data?.registration?.projectId
    if (!nonBlank(projectId)) {
      return { success: false, response: failure("ADA_PROJECT_REGISTRATION_INVALID_RESPONSE") }
    }
    return { success: true, projectId }
  }

  async function invokeWithTextFile(args, context, option, fileName, content, maximumBytes) {
    const text = boundedText(content, fileName, maximumBytes)
    const cwd = contextDirectory(context)
    let prepared
    try {
      prepared = await prepare(cwd)
    } catch {
      return failure("ADA_PROJECT_PREPARATION_FAILED")
    }
    if (!prepared.success) return prepared.response

    let directory
    let path
    try {
      directory = await mkdtemp(join(tempRoot, "ada-opencode-"))
      path = join(directory, fileName)
      await writeFile(path, text, { encoding: "utf8", flag: "wx" })
    } catch {
      if (directory) await removeTemporaryDirectory(directory, tempRoot)
      return failure("ADA_TEMP_FILE_OPERATION_FAILED")
    }
    try {
      const commandOptions = [...args.options]
      const fileOptionIndex = args.fileOptionIndex ?? commandOptions.length
      commandOptions.splice(fileOptionIndex, 0, option, path)
      return await execute([
        ...args.command,
        "--workspace", workspace,
        "--project-id", prepared.projectId,
        ...commandOptions,
      ], cwd)
    } catch {
      return failure("ADA_CLI_EXECUTION_FAILED")
    } finally {
      if (directory) await removeTemporaryDirectory(directory, tempRoot)
    }
  }

  return Object.freeze({
    analysisBegin(input, context) {
      const options = [
        "--test", requiredText(input.targetTest, "targetTest"),
        "--context-mode", contextMode(input.contextMode),
      ]
      appendOptional(options, "--case-id", input.caseId)
      appendOptional(options, "--adapter", input.adapterId)
      return invokeWithTextFile(
        { command: ["case", "open"], options, fileOptionIndex: 2 }, context,
        "--question-file", "question.txt", input.question, QUESTION_LIMIT_BYTES,
      )
    },
    caseInspect(input, context) {
      return invoke(command(["case", "inspect"], "--case-id", input.caseId), context)
    },
    runTest(input, context) {
      return invoke(caseAnalysisCommand(["run", "execute"], input), context)
    },
    staticAnalyze(input, context) {
      return invoke(caseAnalysisCommand(["static", "analyze"], input), context)
    },
    codePathPlanCreate(input, context) {
      return invokePlan(["plan", "codepath", "create"], input, context)
    },
    codePathCollect(input, context) {
      return invoke(collectionCommand(["collection", "codepath", "execute"], input), context)
    },
    jdwpPlanCreate(input, context) {
      return invokePlan(["plan", "jdwp", "create"], input, context)
    },
    jdwpCollect(input, context) {
      return invoke(collectionCommand(["collection", "jdwp", "execute"], input), context)
    },
    artifactRead(input, context) {
      const offset = integerInRange(input.offsetBytes ?? 0, "offsetBytes", 0, Number.MAX_SAFE_INTEGER)
      const maximum = integerInRange(
        input.maxBytes ?? DEFAULT_ARTIFACT_BYTES, "maxBytes", 1, MAXIMUM_ARTIFACT_BYTES,
      )
      return invoke({
        command: ["artifact", "read"],
        options: [
          "--case-id", requiredText(input.caseId, "caseId"),
          "--artifact-id", requiredText(input.artifactId, "artifactId"),
          "--offset-bytes", String(offset), "--max-bytes", String(maximum),
        ],
      }, context)
    },
    analysisComplete(input, context) {
      return invokeWithTextFile({
        command: ["analysis", "complete"],
        options: [
          "--case-id", requiredText(input.caseId, "caseId"),
          "--analysis-id", requiredText(input.analysisId, "analysisId"),
        ],
      }, context, "--result-file", "result.json", input.resultJson, RESULT_LIMIT_BYTES)
    },
  })

  function invokePlan(commandName, input, context) {
    return invokeWithTextFile({
      command: commandName,
      options: [
        "--case-id", requiredText(input.caseId, "caseId"),
        "--analysis-id", requiredText(input.analysisId, "analysisId"),
      ],
    }, context, "--request-file", "request.json", input.requestJson, REQUEST_LIMIT_BYTES)
  }
}

function command(name, option, value) {
  return { command: name, options: [option, requiredText(value, option)] }
}

function caseAnalysisCommand(name, input) {
  return {
    command: name,
    options: [
      "--case-id", requiredText(input.caseId, "caseId"),
      "--analysis-id", requiredText(input.analysisId, "analysisId"),
    ],
  }
}

function collectionCommand(name, input) {
  return {
    command: name,
    options: [
      "--case-id", requiredText(input.caseId, "caseId"),
      "--plan-id", requiredText(input.planId, "planId"),
    ],
  }
}

function workspacePath(environment, platform) {
  if (nonBlank(environment.ADA_WORKSPACE)) return environment.ADA_WORKSPACE
  if (platform === "win32") {
    const base = environment.LOCALAPPDATA ?? environment.USERPROFILE ?? homedir()
    return resolve(base, "algorithm-debug-agent", "workspace")
  }
  const base = environment.XDG_STATE_HOME
    ?? join(environment.HOME ?? homedir(), ".local", "state")
  return resolve(base, "algorithm-debug-agent", "workspace")
}

function successful(response) {
  return parseResponse(response)?.success === true
}

function parseResponse(response) {
  try {
    const value = JSON.parse(response)
    return value && typeof value === "object" && !Array.isArray(value) ? value : null
  } catch {
    return null
  }
}

function contextDirectory(context) {
  return requiredText(context?.directory, "context.directory")
}

function boundedText(value, name, maximumBytes) {
  const text = requiredText(value, name)
  if (Buffer.byteLength(text, "utf8") > maximumBytes) {
    throw new RangeError(`${name} 超过 ${maximumBytes} 字节`)
  }
  return text
}

function requiredText(value, name) {
  if (!nonBlank(value)) throw new TypeError(`${name} 必须为非空字符串`)
  return value
}

function contextMode(value) {
  const mode = value ?? "reuse"
  if (mode !== "reuse" && mode !== "new") throw new TypeError("contextMode 只能为 reuse 或 new")
  return mode
}

function integerInRange(value, name, minimum, maximum) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new RangeError(`${name} 超出允许范围`)
  }
  return value
}

function appendOptional(options, name, value) {
  if (value === undefined || value === null) return
  options.push(name, requiredText(value, name))
}

async function removeTemporaryDirectory(directory, root) {
  const child = resolve(directory)
  const pathFromRoot = relative(root, child)
  if (pathFromRoot === "" || pathFromRoot.startsWith("..") || isAbsolute(pathFromRoot)) return
  try {
    await rm(child, { recursive: true, force: true })
  } catch {
    // CLI 已经返回时，清理失败不能改写其确定性结果。
  }
}

function nonBlank(value) {
  return typeof value === "string" && value.trim().length > 0
}

function failure(code) {
  return JSON.stringify({
    schemaVersion: "2.0",
    success: false,
    code,
    message: "Algorithm Debug OpenCode adapter failed; inspect local Agent logs for details",
    data: null,
    artifacts: [],
  })
}
