import assert from "node:assert/strict"
import { mkdtemp, readFile, rm, stat } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import test from "node:test"

import { createCaseInteractionRecorder } from "../lib/case-interaction-recorder.mjs"

const fixedNow = () => new Date("2026-08-22T10:01:01.123Z")

test("buffers a new interaction until Case identity is known", async t => {
  const root = await mkdtemp(join(tmpdir(), "ada-case-dfx-"))
  t.after(() => rm(root, { recursive: true, force: true }))
  const workspaceDirectory = join(root, "workspace")
  const fallbackDirectory = join(root, "diagnostics")
  const recorder = createCaseInteractionRecorder({
    enabled: true, workspaceDirectory, fallbackDirectory, now: fixedNow,
    newId: () => "invocation-1",
  })

  const scope = recorder.beginTool({
    sessionId: "session-1", messageId: "message-1", agent: "algorithm-debug",
    toolName: "analysis_begin", question: "must not be logged",
  })
  await scope.cliStarted("workspace init")
  await scope.cliCompleted({ code: "OK" })
  await scope.bindProject("project-1")
  await scope.cliStarted("case open")
  await scope.cliCompleted({ code: "OK" })
  await scope.bindCase({
    caseId: "case-1", analysisId: "analysis-1", targetTest: "demo.Test#case1",
  })
  await scope.toolCompleted({ code: "OK" })

  const logPath = join(
    workspaceDirectory, "projects", "project-1", "cases", "case-1", "interaction.jsonl")
  const events = await readEvents(logPath)
  assert.deepEqual(events.map(event => event.eventType), [
    "TOOL_CALL_STARTED", "CLI_PROCESS_STARTED", "CLI_PROCESS_COMPLETED",
    "CLI_PROCESS_STARTED", "CLI_PROCESS_COMPLETED", "CASE_INTERACTION_STARTED",
    "TOOL_CALL_COMPLETED",
  ])
  assert.ok(events.every(event => event.projectId === "project-1"))
  assert.ok(events.every(event => event.caseId === "case-1"))
  assert.ok(events.every(event => event.analysisId === "analysis-1"))
  assert.doesNotMatch(await readFile(logPath, "utf8"), /must not be logged/u)
  await assert.rejects(stat(join(fallbackDirectory, "unassigned", "session-1.jsonl")))
})

test("physically isolates Cases even when they share one OpenCode session", async t => {
  const root = await mkdtemp(join(tmpdir(), "ada-multi-case-dfx-"))
  t.after(() => rm(root, { recursive: true, force: true }))
  const workspaceDirectory = join(root, "workspace")
  const recorder = createCaseInteractionRecorder({
    enabled: true, workspaceDirectory, fallbackDirectory: join(root, "diagnostics"),
    now: fixedNow, newId: (() => { let value = 0; return () => `invocation-${++value}` })(),
  })

  for (const caseId of ["case-1", "case-2"]) {
    const scope = recorder.beginTool({ sessionId: "session-1", toolName: "run_test" })
    await scope.bindProject("project-1")
    await scope.bindCase({ caseId, analysisId: `analysis-${caseId.at(-1)}` })
    await scope.toolCompleted({ code: "OK", runId: `run-${caseId.at(-1)}` })
  }

  const first = await readFile(join(
    workspaceDirectory, "projects", "project-1", "cases", "case-1", "interaction.jsonl"), "utf8")
  const second = await readFile(join(
    workspaceDirectory, "projects", "project-1", "cases", "case-2", "interaction.jsonl"), "utf8")
  assert.match(first, /"caseId":"case-1"/u)
  assert.doesNotMatch(first, /case-2/u)
  assert.match(second, /"caseId":"case-2"/u)
  assert.doesNotMatch(second, /case-1/u)
})

test("writes only an unassigned fallback when Case creation fails", async t => {
  const root = await mkdtemp(join(tmpdir(), "ada-unassigned-dfx-"))
  t.after(() => rm(root, { recursive: true, force: true }))
  const fallbackDirectory = join(root, "diagnostics")
  const recorder = createCaseInteractionRecorder({
    enabled: true, workspaceDirectory: join(root, "workspace"), fallbackDirectory,
    now: fixedNow, newId: () => "invocation-1",
  })

  const scope = recorder.beginTool({ sessionId: "session-1", toolName: "analysis_begin" })
  await scope.bindProject("project-1")
  await scope.toolFailed({ code: "CASE_OPEN_FAILED" })

  const events = await readEvents(join(fallbackDirectory, "unassigned", "session-1.jsonl"))
  assert.deepEqual(events.map(event => event.eventType), [
    "TOOL_CALL_STARTED", "TOOL_CALL_FAILED",
  ])
  await assert.rejects(stat(join(root, "workspace", "projects", "project-1", "cases")))
})

test("keeps recorder failures and disabled logging outside business behavior", async t => {
  const root = await mkdtemp(join(tmpdir(), "ada-disabled-dfx-"))
  t.after(() => rm(root, { recursive: true, force: true }))
  let errors = 0
  const recorder = createCaseInteractionRecorder({
    enabled: true, workspaceDirectory: join(root, "workspace"),
    fallbackDirectory: join(root, "diagnostics"), now: fixedNow,
    newId: () => "invocation-1", appendLine: async () => { throw new Error("disk failed") },
    onError: () => { errors++ },
  })
  const scope = recorder.beginTool({ sessionId: "session-1", toolName: "run_test" })
  await scope.bindProject("project-1")
  await scope.bindCase({ caseId: "case-1", analysisId: "analysis-1" })
  await scope.toolCompleted({ code: "OK" })
  assert.equal(errors, 1)

  const disabled = createCaseInteractionRecorder({
    enabled: false, workspaceDirectory: join(root, "disabled-workspace"),
    fallbackDirectory: join(root, "disabled-diagnostics"),
  })
  const disabledScope = disabled.beginTool({ sessionId: "session-2", toolName: "run_test" })
  await disabledScope.bindProject("project-1")
  await disabledScope.bindCase({ caseId: "case-1", analysisId: "analysis-1" })
  await disabledScope.toolCompleted({ code: "OK" })
  await assert.rejects(stat(join(root, "disabled-workspace")))
  await assert.rejects(stat(join(root, "disabled-diagnostics")))
})

async function readEvents(path) {
  return (await readFile(path, "utf8")).trim().split(/\r?\n/u).map(line => JSON.parse(line))
}
