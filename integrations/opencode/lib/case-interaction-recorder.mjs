import { createHash, randomUUID } from "node:crypto"
import { appendFile, mkdir, stat } from "node:fs/promises"
import { dirname, isAbsolute, join, relative, resolve } from "node:path"

const DEFAULT_MAXIMUM_EVENT_BYTES = 8 * 1024
const DEFAULT_MAXIMUM_FILE_BYTES = 16 * 1024 * 1024
const OPTIONAL_DROP_ORDER = [
  "messageId", "agent", "targetTest", "artifactIds", "evidenceId", "collectionId",
  "planId", "runId", "durationMillis", "code", "commandName", "analysisId",
]
const NOOP_SCOPE = Object.freeze({
  bindProject: async () => {}, bindCase: async () => {},
  cliStarted: async () => {}, cliCompleted: async () => {}, cliFailed: async () => {},
  toolCompleted: async () => {}, toolFailed: async () => {},
})

/**
 * 创建按 Case 追加的有界诊断记录器。记录器只接受白名单元数据，任何写入失败均在内部降级。
 *
 * @param {{
 *   enabled: boolean,
 *   workspaceDirectory: string,
 *   fallbackDirectory: string,
 *   now?: () => Date,
 *   newId?: () => string,
 *   appendLine?: (path: string, line: string) => Promise<void>,
 *   onError?: (error: unknown) => void,
 *   maximumEventBytes?: number,
 *   maximumFileBytes?: number
 * }} options DFX 开关、已解析目录和可替换测试端口
 */
export function createCaseInteractionRecorder({
  enabled,
  workspaceDirectory,
  fallbackDirectory,
  now = () => new Date(),
  newId = randomUUID,
  appendLine = appendJsonLine,
  onError = () => {},
  maximumEventBytes = DEFAULT_MAXIMUM_EVENT_BYTES,
  maximumFileBytes = DEFAULT_MAXIMUM_FILE_BYTES,
}) {
  if (!enabled) return Object.freeze({ beginTool: () => NOOP_SCOPE })
  const workspace = resolveRoot(workspaceDirectory, "workspaceDirectory")
  const fallback = resolveRoot(fallbackDirectory, "fallbackDirectory")
  const fileQueues = new Map()
  const truncatedFiles = new Set()
  let errorReported = false

  return Object.freeze({ beginTool })

  function beginTool(identity = {}) {
    const sessionId = safeIdentifier(identity.sessionId ?? "unknown-session")
    const invocationId = safeIdentifier(newId())
    const base = compact({
      sessionId,
      invocationId,
      messageId: safeOptionalIdentifier(identity.messageId),
      agent: safeOptionalIdentifier(identity.agent),
      toolName: safeOptionalIdentifier(identity.toolName),
    })
    const state = {
      projectId: undefined,
      caseId: undefined,
      analysisId: undefined,
      targetTest: safeText(identity.targetTest, 512),
      destination: undefined,
      pending: [descriptor("TOOL_CALL_STARTED", "STARTED", {}, now)],
      currentCli: undefined,
      chain: Promise.resolve(),
    }

    return Object.freeze({
      bindProject(projectId) {
        return schedule(() => { state.projectId = safeIdentifier(projectId) })
      },
      bindCase(caseIdentity = {}) {
        return schedule(async () => {
          const projectId = state.projectId
          const caseId = safeIdentifier(caseIdentity.caseId)
          if (!projectId) throw new TypeError("projectId must be bound before caseId")
          const firstBinding = state.destination === undefined
          state.caseId = caseId
          state.analysisId = safeOptionalIdentifier(caseIdentity.analysisId) ?? state.analysisId
          state.targetTest = safeText(caseIdentity.targetTest, 512) ?? state.targetTest
          const destination = caseLogPath(workspace, projectId, caseId)
          if (state.destination && state.destination !== destination) {
            throw new Error("one Tool invocation cannot be rebound to another Case")
          }
          state.destination = destination
          if (firstBinding) {
            await flushPending()
            await writeDescriptor(descriptor(
              "CASE_INTERACTION_STARTED", "STARTED", {}, now))
          }
        })
      },
      cliStarted(commandName) {
        return schedule(async () => {
          state.currentCli = {
            commandName: safeIdentifier(commandName),
            startedAt: timestamp(now).getTime(),
          }
          await record(descriptor("CLI_PROCESS_STARTED", "STARTED", {
            commandName: state.currentCli.commandName,
          }, now))
        })
      },
      cliCompleted(result = {}) {
        return schedule(async () => {
          const current = state.currentCli
          state.currentCli = undefined
          await record(descriptor("CLI_PROCESS_COMPLETED", "SUCCEEDED", {
            ...safeFacts(result),
            commandName: current?.commandName,
            durationMillis: duration(current?.startedAt, now),
          }, now))
        })
      },
      cliFailed(result = {}) {
        return schedule(async () => {
          const current = state.currentCli
          state.currentCli = undefined
          await record(descriptor("CLI_PROCESS_FAILED", "FAILED", {
            ...safeFacts(result),
            commandName: current?.commandName,
            durationMillis: duration(current?.startedAt, now),
          }, now))
        })
      },
      toolCompleted(result = {}) {
        return terminal("TOOL_CALL_COMPLETED", "SUCCEEDED", result)
      },
      toolFailed(result = {}) {
        return terminal("TOOL_CALL_FAILED", "FAILED", result)
      },
    })

    function terminal(eventType, outcome, result) {
      return schedule(async () => {
        const item = descriptor(eventType, outcome, safeFacts(result), now)
        if (!state.destination) {
          state.destination = fallbackLogPath(fallback, sessionId)
          state.pending.push(item)
          await flushPending()
          return
        }
        await writeDescriptor(item)
      })
    }

    function schedule(action) {
      state.chain = state.chain.then(action).catch(reportOnce)
      return state.chain
    }

    async function record(item) {
      if (!state.destination) {
        state.pending.push(item)
        return
      }
      await writeDescriptor(item)
    }

    async function flushPending() {
      const items = state.pending
      state.pending = []
      for (const item of items) await writeDescriptor(item)
    }

    async function writeDescriptor(item) {
      const event = compact({
        schemaVersion: "1.0",
        timestamp: item.timestamp,
        level: levelFor(item.eventType),
        eventType: item.eventType,
        source: "OPENCODE_TOOL_RUNTIME",
        outcome: item.outcome,
        ...base,
        projectId: state.projectId,
        caseId: state.caseId,
        analysisId: state.analysisId,
        targetTest: state.targetTest,
        ...item.fields,
      })
      await enqueue(state.destination, event)
    }
  }

  async function enqueue(path, event) {
    const previous = fileQueues.get(path) ?? Promise.resolve()
    const current = previous.catch(() => {}).then(() => appendBounded(path, event))
    fileQueues.set(path, current)
    await current
  }

  async function appendBounded(path, event) {
    const line = boundedLine(event, maximumEventBytes)
    if (!line) return
    const currentBytes = await fileSize(path)
    const lineBytes = Buffer.byteLength(line, "utf8")
    if (currentBytes + lineBytes <= maximumFileBytes) {
      await appendLine(path, line)
      return
    }
    if (truncatedFiles.has(path)) return
    truncatedFiles.add(path)
    const truncated = boundedLine({
      ...event,
      timestamp: timestamp(now).toISOString(),
      level: "WARN",
      eventType: "LOG_TRUNCATED",
      outcome: "TRUNCATED",
      commandName: undefined,
      durationMillis: undefined,
      code: undefined,
      artifactIds: undefined,
    }, maximumEventBytes)
    if (truncated && currentBytes + Buffer.byteLength(truncated, "utf8") <= maximumFileBytes) {
      await appendLine(path, truncated)
    }
  }

  function reportOnce(error) {
    if (errorReported) return
    errorReported = true
    try { onError(error) } catch { /* DFX error reporting is also non-blocking. */ }
  }
}

function descriptor(eventType, outcome, fields, now) {
  return { eventType, outcome, fields: compact(fields), timestamp: timestamp(now).toISOString() }
}

function safeFacts(value) {
  return compact({
    code: safeOptionalIdentifier(value.code),
    runId: safeOptionalIdentifier(value.runId),
    planId: safeOptionalIdentifier(value.planId),
    collectionId: safeOptionalIdentifier(value.collectionId),
    evidenceId: safeOptionalIdentifier(value.evidenceId),
    artifactIds: safeIdentifierArray(value.artifactIds),
  })
}

function boundedLine(event, maximumBytes) {
  const candidate = { ...event }
  for (const field of [undefined, ...OPTIONAL_DROP_ORDER]) {
    if (field) delete candidate[field]
    const line = `${JSON.stringify(compact(candidate))}\n`
    if (Buffer.byteLength(line, "utf8") <= maximumBytes) return line
  }
  return undefined
}

async function appendJsonLine(path, line) {
  await mkdir(dirname(path), { recursive: true })
  await appendFile(path, line, { encoding: "utf8" })
}

async function fileSize(path) {
  try { return (await stat(path)).size } catch (error) {
    if (error?.code === "ENOENT") return 0
    throw error
  }
}

function caseLogPath(workspace, projectId, caseId) {
  return inside(workspace, join(
    workspace, "projects", safeSegment(projectId), "cases", safeSegment(caseId),
    "interaction.jsonl"))
}

function fallbackLogPath(fallback, sessionId) {
  return inside(fallback, join(fallback, "unassigned", `${safeSegment(sessionId)}.jsonl`))
}

function inside(root, path) {
  const candidate = resolve(path)
  const fromRoot = relative(root, candidate)
  if (fromRoot === "" || fromRoot.startsWith("..") || isAbsolute(fromRoot)) {
    throw new Error("DFX path escaped its configured root")
  }
  return candidate
}

function resolveRoot(value, name) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError(`${name} must be a non-empty string`)
  }
  return resolve(value)
}

function safeSegment(value) {
  const identifier = safeIdentifier(value)
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u.test(identifier)
      || identifier === "." || identifier === "..") {
    return `id-${createHash("sha256").update(identifier).digest("hex").slice(0, 24)}`
  }
  return identifier
}

function safeIdentifier(value) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError("diagnostic identifier must be a non-empty string")
  }
  return value.trim().replace(/[\u0000-\u001f\u007f]/gu, "").slice(0, 128)
}

function safeOptionalIdentifier(value) {
  if (value === undefined || value === null || value === "") return undefined
  return safeIdentifier(value)
}

function safeIdentifierArray(value) {
  if (!Array.isArray(value)) return undefined
  const values = value.slice(0, 64).map(safeOptionalIdentifier).filter(Boolean)
  return values.length === 0 ? undefined : values
}

function safeText(value, maximumLength) {
  if (typeof value !== "string" || value.trim() === "") return undefined
  return value.trim().replace(/[\u0000-\u001f\u007f]/gu, "").slice(0, maximumLength)
}

function timestamp(now) {
  const value = now()
  if (!(value instanceof Date) || Number.isNaN(value.getTime())) {
    throw new TypeError("now must return a valid Date")
  }
  return value
}

function duration(startedAt, now) {
  if (!Number.isFinite(startedAt)) return undefined
  return Math.max(0, timestamp(now).getTime() - startedAt)
}

function levelFor(eventType) {
  if (eventType.endsWith("_FAILED")) return "ERROR"
  if (eventType === "LOG_TRUNCATED") return "WARN"
  return "INFO"
}

function compact(value) {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined))
}
