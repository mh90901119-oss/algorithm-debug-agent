import assert from "node:assert/strict"
import { mkdtemp, readFile, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import test from "node:test"

import { createCaseInteractionRecorder } from "../lib/case-interaction-recorder.mjs"
import { createAlgorithmDebugRuntime } from "../lib/tool-runtime.mjs"

const success = data => JSON.stringify({
  schemaVersion: "2.0", success: true, code: "OK", message: "Success", data, artifacts: [],
})

test("records the real analysis_begin Tool and CLI order in its new Case", async t => {
  const root = await mkdtemp(join(tmpdir(), "ada-runtime-dfx-"))
  t.after(() => rm(root, { recursive: true, force: true }))
  const workspaceDirectory = join(root, "workspace")
  const recorder = createCaseInteractionRecorder({
    enabled: true, workspaceDirectory, fallbackDirectory: join(root, "diagnostics"),
    now: () => new Date("2026-08-22T10:01:01.123Z"), newId: () => "invocation-1",
  })
  const runtime = createAlgorithmDebugRuntime({
    workspaceDirectory, resultJsonDirectory: join(root, "results"),
    temporaryRoot: root, interactionRecorder: recorder,
    execute: async args => {
      if (args[0] === "workspace") return success({ created: true })
      if (args[0] === "project") {
        return success({ registration: { projectId: "project-1" } })
      }
      return success({
        caseId: "case-1", analysisId: "analysis-1",
      })
    },
  })

  await runtime.analysisBegin({
    question: "why", targetTest: "demo.Test#case1",
  }, {
    directory: join(root, "target"), sessionID: "session-1",
    messageID: "message-1", agent: "algorithm-debug",
  })

  const events = await readEvents(join(
    workspaceDirectory, "projects", "project-1", "cases", "case-1", "interaction.jsonl"))
  assert.deepEqual(events.map(event => `${event.eventType}:${event.commandName ?? event.toolName}`), [
    "TOOL_CALL_STARTED:analysis_begin",
    "CLI_PROCESS_STARTED:workspace init",
    "CLI_PROCESS_COMPLETED:workspace init",
    "CLI_PROCESS_STARTED:project register",
    "CLI_PROCESS_COMPLETED:project register",
    "CLI_PROCESS_STARTED:case open",
    "CLI_PROCESS_COMPLETED:case open",
    "CASE_INTERACTION_STARTED:analysis_begin",
    "TOOL_CALL_COMPLETED:analysis_begin",
  ])
})

test("records a failed target UT as a completed Tool call", async t => {
  const root = await mkdtemp(join(tmpdir(), "ada-runtime-ut-dfx-"))
  t.after(() => rm(root, { recursive: true, force: true }))
  const workspaceDirectory = join(root, "workspace")
  const recorder = createCaseInteractionRecorder({
    enabled: true, workspaceDirectory, fallbackDirectory: join(root, "diagnostics"),
    newId: () => "invocation-1",
  })
  const runtime = createAlgorithmDebugRuntime({
    workspaceDirectory, resultJsonDirectory: join(root, "results"),
    interactionRecorder: recorder,
    execute: async args => {
      if (args[0] === "workspace") return success({ created: false })
      if (args[0] === "project") return success({ registration: { projectId: "project-1" } })
      return success({ runId: "run-1", testOutcome: "FAILED" })
    },
  })

  const response = await runtime.runTest({
    caseId: "case-1", analysisId: "analysis-1",
  }, { directory: join(root, "target"), sessionID: "session-1" })

  assert.equal(JSON.parse(response).success, true)
  const events = await readEvents(join(
    workspaceDirectory, "projects", "project-1", "cases", "case-1", "interaction.jsonl"))
  assert.equal(events.at(-1).eventType, "TOOL_CALL_COMPLETED")
  assert.equal(events.at(-1).runId, "run-1")
  assert.ok(events.every(event => event.eventType !== "TOOL_CALL_FAILED"))
})

async function readEvents(path) {
  return (await readFile(path, "utf8")).trim().split(/\r?\n/u).map(line => JSON.parse(line))
}
