import { mkdtemp, rm, writeFile } from "node:fs/promises"
import { randomUUID } from "node:crypto"
import { tmpdir } from "node:os"
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
 *   workspaceDirectory: string,
 *   resultJsonDirectory: string,
 *   temporaryRoot?: string,
 *   now?: () => Date,
 *   createId?: (prefix: string) => string,
 *   interactionRecorder?: { beginTool: (identity: object) => object }
 * }} dependencies 可替换的进程与环境依赖
 */
export function createAlgorithmDebugRuntime({
  execute,
  workspaceDirectory,
  resultJsonDirectory,
  temporaryRoot = tmpdir(),
  now = () => new Date(),
  createId = prefix => `${prefix}-${randomUUID()}`,
  interactionRecorder = NOOP_RECORDER,
}) {
  if (typeof execute !== "function") throw new TypeError("execute must be a function")
  if (typeof createId !== "function") throw new TypeError("createId must be a function")
  const workspace = requiredText(workspaceDirectory, "workspaceDirectory")
  const resultDirectory = requiredText(resultJsonDirectory, "resultJsonDirectory")
  const tempRoot = resolve(requiredText(temporaryRoot, "temporaryRoot"))

  const recorder = interactionRecorder && typeof interactionRecorder.beginTool === "function"
    ? interactionRecorder : NOOP_RECORDER
  let activeTargetExecution = null

  async function runTargetExecution(toolName, operation) {
    if (activeTargetExecution !== null) {
      return failure("ADA_TARGET_EXECUTION_SEQUENCE_VIOLATION")
    }
    activeTargetExecution = toolName
    try {
      return await operation()
    } finally {
      activeTargetExecution = null
    }
  }

  async function invoke(args, context, scope) {
    const cwd = contextDirectory(context)
    let prepared
    try {
      prepared = await prepare(cwd, scope)
    } catch {
      return failure("ADA_PROJECT_PREPARATION_FAILED")
    }
    if (!prepared.success) return prepared.response
    await observe(scope, "bindProject", prepared.projectId)
    if (args.identity?.caseId) {
      await observe(scope, "bindCase", args.identity)
    }
    try {
      const response = await executeObserved(scope, args.command.join(" "), [
        ...args.command,
        "--workspace", workspace,
        "--project-id", prepared.projectId,
        ...args.options,
      ], cwd)
      const facts = responseFacts(response)
      if (facts.caseId) {
        await observe(scope, "bindCase", {
          ...args.identity, caseId: facts.caseId, analysisId: facts.analysisId,
        })
      }
      return response
    } catch {
      return failure("ADA_CLI_EXECUTION_FAILED")
    }
  }

  async function prepare(cwd, scope) {
    const initialized = await executeObserved(
      scope, "workspace init", ["workspace", "init", "--root", workspace], cwd)
    if (!successful(initialized)) return { success: false, response: initialized }

    const registered = await executeObserved(scope, "project register", [
      "project", "register", "--workspace", workspace, "--project", cwd,
      "--result-directory", resultDirectory,
    ], cwd)
    const value = parseResponse(registered)
    if (!value?.success) return { success: false, response: registered }
    const projectId = value.data?.registration?.projectId
    if (!nonBlank(projectId)) {
      return { success: false, response: failure("ADA_PROJECT_REGISTRATION_INVALID_RESPONSE") }
    }
    return { success: true, projectId }
  }

  async function invokeWithTextFile(
    args, context, option, fileName, content, maximumBytes, scope) {
    const text = boundedText(content, fileName, maximumBytes)
    const cwd = contextDirectory(context)
    let prepared
    try {
      prepared = await prepare(cwd, scope)
    } catch {
      return failure("ADA_PROJECT_PREPARATION_FAILED")
    }
    if (!prepared.success) return prepared.response
    await observe(scope, "bindProject", prepared.projectId)
    if (args.identity?.caseId) {
      await observe(scope, "bindCase", args.identity)
    }

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
      const response = await executeObserved(scope, args.command.join(" "), [
        ...args.command,
        "--workspace", workspace,
        "--project-id", prepared.projectId,
        ...commandOptions,
      ], cwd)
      const facts = responseFacts(response)
      if (facts.caseId) {
        await observe(scope, "bindCase", {
          ...args.identity, caseId: facts.caseId, analysisId: facts.analysisId,
        })
      }
      return response
    } catch {
      return failure("ADA_CLI_EXECUTION_FAILED")
    } finally {
      if (directory) await removeTemporaryDirectory(directory, tempRoot)
    }
  }

  return Object.freeze({
    analysisBegin(input, context) {
      return runObservedTool("analysis_begin", input, context, async scope => {
        const targetTest = requiredText(input.targetTest, "targetTest")
        const options = [
          "--test", targetTest,
        ]
        appendOptional(options, "--case-id", input.caseId)
        appendOptional(options, "--adapter", input.adapterId)
        const response = await invokeWithTextFile(
          { command: ["case", "open"], options, fileOptionIndex: 2,
            identity: { caseId: input.caseId, targetTest } }, context,
          "--question-file", "question.txt", input.question, QUESTION_LIMIT_BYTES, scope,
        )
        return withAnalysisDirectories(response)
      })
    },
    caseInspect(input, context) {
      return runObservedTool("case_inspect", input, context, scope =>
        invoke(command(["case", "inspect"], "--case-id", input.caseId), context, scope))
    },
    algorithmInputCapture(input, context) {
      return runObservedTool("algorithm_input_capture", input, context, scope =>
        invoke(caseAnalysisCommand(["input", "capture"], input), context, scope))
    },
    caseAudit(input, context) {
      return runObservedTool("case_audit", input, context, scope =>
        invoke(command(["case", "audit"], "--case-id", input.caseId), context, scope))
    },
    ganttInspect(input, context) {
      return runObservedTool("gantt_inspect", input, context, scope => {
        const caseId = requiredText(input.caseId, "caseId")
        const options = [
          "--case-id", caseId, "--artifact-id", requiredText(input.artifactId, "artifactId"),
          "--operation", input.operation ?? "summary",
          "--offset", String(integerInRange(input.offset ?? 0, "offset", 0, Number.MAX_SAFE_INTEGER)),
          "--limit", String(integerInRange(input.limit ?? 100, "limit", 1, 100)),
        ]
        appendOptional(options, "--json-pointer", input.jsonPointer)
        return invoke({ command: ["gantt", "inspect"], identity: { caseId }, options }, context, scope)
      })
    },
    runTest(input, context) {
      return runObservedTool("run_test", input, context, scope =>
        runTargetExecution("run_test", () =>
          invoke(caseAnalysisCommand(["run", "execute"], input), context, scope)))
    },
    staticAnalyze(input, context) {
      return runObservedTool("static_analyze", input, context, scope =>
        invoke(caseAnalysisCommand(["static", "analyze"], input), context, scope))
    },
    codePathPlanCreate(input, context) {
      return runObservedTool("codepath_plan_create", input, context, scope => {
        const requestJson = JSON.stringify(codePathPlanRequest(input, now, createId))
        return invokePlan(
          ["plan", "codepath", "create"], input, requestJson, context, scope)
      })
    },
    codePathCollect(input, context) {
      return runObservedTool("codepath_collect", input, context, async scope =>
        runTargetExecution("codepath_collect", async () =>
          collectionFacingResponse(await invoke(
            collectionCommand(["collection", "codepath", "execute"], input), context, scope))))
    },
    jdwpPlanCreate(input, context) {
      return runObservedTool("jdwp_plan_create", input, context, scope => {
        const requestJson = JSON.stringify(jdwpPlanRequest(input, now, createId))
        return invokePlan(["plan", "jdwp", "create"], input, requestJson, context, scope)
      })
    },
    jdwpCollect(input, context) {
      return runObservedTool("jdwp_collect", input, context, async scope =>
        runTargetExecution("jdwp_collect", async () =>
          collectionFacingResponse(await invoke(
            collectionCommand(["collection", "jdwp", "execute"], input), context, scope))))
    },
    artifactRead(input, context) {
      return runObservedTool("artifact_read", input, context, scope => {
        const offset = integerInRange(
          input.offsetBytes ?? 0, "offsetBytes", 0, Number.MAX_SAFE_INTEGER)
        const maximum = integerInRange(
          input.maxBytes ?? DEFAULT_ARTIFACT_BYTES, "maxBytes", 1, MAXIMUM_ARTIFACT_BYTES,
        )
        const caseId = requiredText(input.caseId, "caseId")
        return invoke({
          command: ["artifact", "read"], identity: { caseId },
          options: [
            "--case-id", caseId,
            "--artifact-id", requiredText(input.artifactId, "artifactId"),
            "--offset-bytes", String(offset), "--max-bytes", String(maximum),
          ],
        }, context, scope)
      })
    },
    evidenceQuery(input, context) {
      return runObservedTool("evidence_query", input, context, scope => {
        const caseId = requiredText(input.caseId, "caseId")
        const options = [
          "--case-id", caseId,
          "--artifact-id", requiredText(input.artifactId, "artifactId"),
        ]
        for (const [field, option] of [
          ["methodRef", "--method-ref"], ["tracepointId", "--tracepoint-id"],
          ["valueName", "--value-name"], ["scalarValue", "--scalar-value"],
          ["valueStatus", "--value-status"],
        ]) {
          if (input[field] !== undefined && input[field] !== null) {
            options.push(option, requiredText(input[field], field))
          }
        }
        for (const [field, option] of [
          ["sequenceFrom", "--sequence-from"], ["sequenceTo", "--sequence-to"],
        ]) {
          if (input[field] !== undefined && input[field] !== null) {
            options.push(option, String(integerInRange(
              input[field], field, 1, Number.MAX_SAFE_INTEGER)))
          }
        }
        options.push(
          "--offset", String(integerInRange(input.offset ?? 0, "offset", 0, Number.MAX_SAFE_INTEGER)),
          "--limit", String(integerInRange(input.limit ?? 20, "limit", 1, 50)),
          "--max-bytes", String(integerInRange(
            input.maxBytes ?? DEFAULT_ARTIFACT_BYTES, "maxBytes", 1, MAXIMUM_ARTIFACT_BYTES)),
        )
        return invoke({
          command: ["evidence", "query"], identity: { caseId }, options,
        }, context, scope)
      })
    },
  })

  function invokePlan(commandName, input, requestJson, context, scope) {
    return invokeWithTextFile({
      command: commandName,
      identity: {
        caseId: requiredText(input.caseId, "caseId"),
        analysisId: requiredText(input.analysisId, "analysisId"),
      },
      options: [
        "--case-id", requiredText(input.caseId, "caseId"),
        "--analysis-id", requiredText(input.analysisId, "analysisId"),
      ],
    }, context, "--request-file", "request.json", requestJson, REQUEST_LIMIT_BYTES, scope)
  }

  async function runObservedTool(toolName, input, context, operation) {
    let scope = NOOP_SCOPE
    try {
      scope = recorder.beginTool({
        sessionId: context?.sessionID ?? "unknown-session",
        messageId: context?.messageID,
        agent: context?.agent,
        toolName,
        targetTest: input?.targetTest,
      }) ?? NOOP_SCOPE
    } catch {
      scope = NOOP_SCOPE
    }
    try {
      const response = await operation(scope)
      const facts = responseFacts(response)
      await observe(scope, facts.success ? "toolCompleted" : "toolFailed", facts)
      return response
    } catch (error) {
      await observe(scope, "toolFailed", { code: "ADA_TOOL_RUNTIME_EXCEPTION" })
      throw error
    }
  }

  async function executeObserved(scope, commandName, args, cwd) {
    await observe(scope, "cliStarted", commandName)
    try {
      const response = await execute(args, cwd)
      const facts = responseFacts(response)
      await observe(scope, facts.success ? "cliCompleted" : "cliFailed", facts)
      return response
    } catch (error) {
      await observe(scope, "cliFailed", { code: "ADA_CLI_PROCESS_FAILED" })
      throw error
    }
  }
}

function withAnalysisDirectories(response) {
  try {
    const value = JSON.parse(response)
    if (value?.success !== true || !value.data) return response
    const caseId = identifierText(value.data.caseId)
    const analysisId = identifierText(value.data.analysisId)
    const projectId = identifierText(value.data.digest?.projectId ?? value.data.projectId)
    if (!caseId || !analysisId || !projectId) return response
    const caseDirectory = `projects/${projectId}/cases/${caseId}`
    const analysisDirectory = `${caseDirectory}/analyses/${analysisId}`
    value.data.caseDirectory = caseDirectory
    value.data.analysisDirectory = analysisDirectory
    value.data.answerContext = [
      `Case directory: ${caseDirectory}`,
      `Analysis directory: ${analysisDirectory}`,
    ].join("\n")
    return JSON.stringify(value)
  } catch {
    return response
  }
}

function identifierText(value) {
  if (typeof value === "string" && value.trim() !== "") return value
  if (typeof value?.value === "string" && value.value.trim() !== "") return value.value
  return null
}

function codePathPlanRequest(input, now, createId) {
  if (!Array.isArray(input.methods) || input.methods.length < 1 || input.methods.length > 50) {
    throw new RangeError("methods must contain between 1 and 50 entries")
  }
  const methodKeys = new Set()
  const methods = input.methods.map((method, methodIndex) => {
    const methodKey = requiredText(method?.methodKey, `methods[${methodIndex}].methodKey`)
    if (methodKeys.has(methodKey)) throw new TypeError("methods must not contain duplicate methodKey")
    methodKeys.add(methodKey)
    if (!Array.isArray(method?.projections) || method.projections.length > 32) {
      throw new RangeError(`methods[${methodIndex}].projections must contain at most 32 entries`)
    }
    const names = new Set()
    const projections = method.projections.map((projection, projectionIndex) => {
      const name = requiredText(projection?.name,
        `methods[${methodIndex}].projections[${projectionIndex}].name`)
      if (names.has(name)) throw new TypeError(`Duplicate projection name for ${methodKey}: ${name}`)
      names.add(name)
      if (typeof projection?.required !== "boolean") {
        throw new TypeError(`methods[${methodIndex}].projections[${projectionIndex}].required must be boolean`)
      }
      return {
        name,
        path: requiredText(projection.path,
          `methods[${methodIndex}].projections[${projectionIndex}].path`),
        required: projection.required,
      }
    })
    return { methodKey, projections }
  })
  const scopeMethodKey = input.scopeMethodKey === undefined
    ? undefined : requiredText(input.scopeMethodKey, "scopeMethodKey")
  if (scopeMethodKey && !methodKeys.has(scopeMethodKey)) {
    throw new TypeError("scopeMethodKey must be included in methods")
  }
  const request = {
    planId: requiredText(createId("codepath-plan"), "generated codepath planId"),
    methods,
  }
  if (scopeMethodKey) request.scopeMethodKey = scopeMethodKey
  request.rationale = requiredText(input.rationale, "rationale")
  request.intent = investigationIntent(input)
  request.budget = { maxEvents: 100_000, maxBytes: 16_777_216, timeoutMillis: 300_000 }
  request.requestedAt = timestamp(now)
  return request
}

function jdwpPlanRequest(input, now, createId) {
  if (!Array.isArray(input.tracepoints)
      || input.tracepoints.length < 1 || input.tracepoints.length > 20) {
    throw new TypeError("tracepoints must contain between 1 and 20 entries")
  }
  const tracepoints = input.tracepoints.map((point, index) => {
    if (!point || typeof point !== "object" || Array.isArray(point)) {
      throw new TypeError(`tracepoints[${index}] must be an object`)
    }
    const maxObservedHits = integerInRange(
      point.maxObservedHits ?? 1_000,
      `tracepoints[${index}].maxObservedHits`, 1, 100_000)
    const maxCapturedHits = integerInRange(
      point.maxCapturedHits ?? 20,
      `tracepoints[${index}].maxCapturedHits`, 1, 200)
    const captureFirstMatchedHits = integerInRange(
      point.captureFirstMatchedHits ?? Math.min(5, maxCapturedHits),
      `tracepoints[${index}].captureFirstMatchedHits`, 0, maxCapturedHits)
    const captureEveryMatchedHits = integerInRange(
      point.captureEveryMatchedHits ?? Math.min(5, maxObservedHits),
      `tracepoints[${index}].captureEveryMatchedHits`, 0, maxObservedHits)
    if (captureFirstMatchedHits === 0 && captureEveryMatchedHits === 0) {
      throw new RangeError(`tracepoints[${index}] must select at least one matched hit`)
    }
    const capture = point.capture ?? {}
    if (!capture || typeof capture !== "object" || Array.isArray(capture)) {
      throw new TypeError(`tracepoints[${index}].capture must be an object`)
    }
    const localNames = distinctTextArray(
      capture.localNames ?? [], `tracepoints[${index}].capture.localNames`, 64, true)
    const fieldPaths = distinctTextArray(
      capture.fieldPaths ?? [], `tracepoints[${index}].capture.fieldPaths`, 128, true)
    const locals = booleanValue(capture.locals ?? (localNames.length > 0),
      `tracepoints[${index}].capture.locals`)
    const stack = booleanValue(capture.stack ?? true, `tracepoints[${index}].capture.stack`)
    if (!locals && !stack) {
      throw new TypeError(`tracepoints[${index}].capture must enable locals or stack`)
    }
    if (locals && localNames.length === 0) {
      throw new TypeError(`tracepoints[${index}].capture.localNames is required when locals is true`)
    }
    if (!locals && (localNames.length > 0 || fieldPaths.length > 0)) {
      throw new TypeError(`tracepoints[${index}].capture projections require locals`)
    }
    for (const path of fieldPaths) {
      const root = path.includes(".") ? path.slice(0, path.indexOf(".")) : path
      if (!localNames.includes(root)) {
        throw new TypeError(`tracepoints[${index}].capture.fieldPaths root must be in localNames`)
      }
    }
    const tracepoint = {
      tracepointId: `tracepoint-${index + 1}`,
      methodKey: requiredText(point.methodKey, `tracepoints[${index}].methodKey`),
      line: integerInRange(point.line, `tracepoints[${index}].line`, 1, Number.MAX_SAFE_INTEGER),
      maxObservedHits,
      maxCapturedHits,
      captureFirstMatchedHits,
      captureEveryMatchedHits,
      capture: {
        locals,
        stack,
        maxFrames: integerInRange(
          capture.maxFrames ?? 8, `tracepoints[${index}].capture.maxFrames`, 1, 64),
        maxDepth: integerInRange(
          capture.maxDepth ?? 1, `tracepoints[${index}].capture.maxDepth`, 0, 2),
        maxItems: integerInRange(
          capture.maxItems ?? 20, `tracepoints[${index}].capture.maxItems`, 1, 100),
        maxStringLength: integerInRange(
          capture.maxStringLength ?? 256,
          `tracepoints[${index}].capture.maxStringLength`, 16, 1_024),
        localNames,
        fieldPaths,
      },
    }
    if (point.condition !== undefined) {
      const condition = point.condition
      if (!condition || typeof condition !== "object" || Array.isArray(condition)) {
        throw new TypeError(`tracepoints[${index}].condition must be an object`)
      }
      const expectedType = requiredText(
        condition.expectedType, `tracepoints[${index}].condition.expectedType`)
      if (!["STRING", "LONG", "DOUBLE", "BOOLEAN", "CHAR", "ENUM", "NULL"]
        .includes(expectedType)) {
        throw new TypeError(`tracepoints[${index}].condition.expectedType is unsupported`)
      }
      tracepoint.condition = {
        localName: requiredText(
          condition.localName, `tracepoints[${index}].condition.localName`),
        fieldPath: distinctTextArray(
          condition.fieldPath ?? [], `tracepoints[${index}].condition.fieldPath`, 8, true),
        operator: condition.operator ?? "EQUALS",
        expectedType,
        expectedValue: expectedType === "NULL" ? null : requiredText(
          condition.expectedValue, `tracepoints[${index}].condition.expectedValue`),
      }
      if (tracepoint.condition.operator !== "EQUALS") {
        throw new TypeError(`tracepoints[${index}].condition.operator must be EQUALS`)
      }
    }
    return tracepoint
  })
  return {
    planId: requiredText(createId("jdwp-plan"), "generated jdwp planId"),
    tracepoints,
    budget: {
      maxEvents: 500, maxBytes: 33_554_432, timeoutMillis: 300_000,
      idleTimeoutMillis: 120_000,
    },
    rationale: requiredText(input.rationale, "rationale"),
    intent: investigationIntent(input),
    requestedAt: timestamp(now),
  }
}

function investigationIntent(input) {
  const rationale = requiredText(input.rationale, "rationale")
  return {
    questionToAnswer: requiredText(
      input.questionToAnswer ?? rationale, "questionToAnswer"),
    hypothesis: requiredText(input.hypothesis ?? rationale, "hypothesis"),
    basedOnEvidenceIds: distinctTextArray(
      input.basedOnEvidenceIds ?? [], "basedOnEvidenceIds", 20, true),
    expectedObservations: distinctTextArray(
      input.expectedObservations ?? [rationale], "expectedObservations", 20, false),
  }
}

function timestamp(now) {
  const value = now()
  if (!(value instanceof Date) || Number.isNaN(value.getTime())) {
    throw new TypeError("now must return a valid Date")
  }
  return value.toISOString()
}

function distinctTextArray(value, name, maximum, allowEmpty) {
  const values = textArray(value, name)
  if ((!allowEmpty && values.length === 0) || values.length > maximum) {
    throw new RangeError(`${name} has an invalid number of entries`)
  }
  if (new Set(values).size !== values.length) {
    throw new TypeError(`${name} must not contain duplicates`)
  }
  return values
}

function integerArray(value, name, maximum) {
  if (!Array.isArray(value) || value.length > maximum) {
    throw new TypeError(`${name} must be an array with at most ${maximum} entries`)
  }
  return value.map((item, index) => integerInRange(
    item, `${name}[${index}]`, 1, Number.MAX_SAFE_INTEGER))
}

function booleanValue(value, name) {
  if (typeof value !== "boolean") throw new TypeError(`${name} must be a boolean`)
  return value
}

function textArray(value, name) {
  const values = value ?? []
  if (!Array.isArray(values)) throw new TypeError(`${name} must be an array`)
  return values.map((item, index) => requiredText(item, `${name}[${index}]`))
}

function command(name, option, value) {
  const required = requiredText(value, option)
  return {
    command: name,
    options: [option, required],
    identity: option === "--case-id" ? { caseId: required } : undefined,
  }
}

function caseAnalysisCommand(name, input) {
  const caseId = requiredText(input.caseId, "caseId")
  const analysisId = requiredText(input.analysisId, "analysisId")
  return {
    command: name,
    identity: { caseId, analysisId },
    options: [
      "--case-id", caseId,
      "--analysis-id", analysisId,
    ],
  }
}

function collectionCommand(name, input) {
  const caseId = requiredText(input.caseId, "caseId")
  const planId = requiredText(input.planId, "planId")
  return {
    command: name,
    identity: { caseId, planId },
    options: [
      "--case-id", caseId,
      "--plan-id", planId,
    ],
  }
}

async function observe(scope, method, value) {
  try {
    if (typeof scope?.[method] === "function") await scope[method](value)
  } catch {
    // DFX 必须与 ToolResponse 隔离。
  }
}

function responseFacts(response) {
  const value = parseResponse(response)
  if (!value) return { success: false, code: "INVALID_TOOL_RESPONSE" }
  const facts = {
    success: value.success === true,
    code: nonBlank(value.code) ? value.code : undefined,
  }
  collectIdentifiers(value.data, facts, 0, { count: 0 })
  const artifactIds = []
  collectArtifactIds(value.artifacts, artifactIds)
  collectArtifactIds(value.data?.artifactIds, artifactIds)
  if (artifactIds.length > 0) facts.artifactIds = [...new Set(artifactIds)].slice(0, 64)
  return facts
}

function collectIdentifiers(value, facts, depth, budget) {
  if (!value || typeof value !== "object" || depth > 4 || budget.count++ > 128) return
  if (Array.isArray(value)) {
    for (const item of value) collectIdentifiers(item, facts, depth + 1, budget)
    return
  }
  for (const key of ["caseId", "analysisId", "runId", "planId", "collectionId", "evidenceId"]) {
    if (!facts[key] && nonBlank(value[key])) facts[key] = value[key]
  }
  for (const child of Object.values(value)) {
    collectIdentifiers(child, facts, depth + 1, budget)
  }
}

function collectArtifactIds(value, result) {
  if (!Array.isArray(value)) return
  for (const item of value.slice(0, 64)) {
    if (nonBlank(item)) result.push(item)
    else if (nonBlank(item?.artifactId)) result.push(item.artifactId)
    else if (nonBlank(item?.id)) result.push(item.id)
  }
}

function successful(response) {
  return parseResponse(response)?.success === true
}

function collectionFacingResponse(response) {
  const value = parseResponse(response)
  if (!value?.success || !value.data || typeof value.data !== "object"
      || Array.isArray(value.data) || !nonBlank(value.data.runId)) {
    return response
  }
  const data = { ...value.data, collectorExecutionRunId: value.data.runId }
  delete data.runId
  return JSON.stringify({ ...value, data })
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
    throw new RangeError(`${name} exceeds ${maximumBytes} bytes`)
  }
  return text
}

function requiredText(value, name) {
  if (!nonBlank(value)) throw new TypeError(`${name} must be a non-empty string`)
  return value
}

function integerInRange(value, name, minimum, maximum) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new RangeError(`${name} is outside the allowed range`)
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

const NOOP_SCOPE = Object.freeze({
  bindProject: async () => {}, bindCase: async () => {},
  cliStarted: async () => {}, cliCompleted: async () => {}, cliFailed: async () => {},
  toolCompleted: async () => {}, toolFailed: async () => {},
})
const NOOP_RECORDER = Object.freeze({ beginTool: () => NOOP_SCOPE })
