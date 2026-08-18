import test from "node:test"
import assert from "node:assert/strict"
import { mkdtemp, readFile, rm, stat } from "node:fs/promises"
import { join } from "node:path"
import { tmpdir } from "node:os"

import { createAlgorithmDebugRuntime } from "../lib/tool-runtime.mjs"

const success = data => JSON.stringify({
  schemaVersion: "2.0", success: true, code: "OK", message: "Success", data, artifacts: [],
})

test("maps every OpenCode action to the real CLI and removes temporary files", async t => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), "ada-tool-runtime-test-"))
  t.after(() => rm(temporaryRoot, { recursive: true, force: true }))
  const calls = []
  const temporaryFiles = []
  const execute = async (args, cwd) => {
    calls.push({ args: [...args], cwd })
    if (args[0] === "workspace") return success({ created: true })
    if (args[0] === "project") {
      return success({ registration: { projectId: "demo-project" }, created: false })
    }
    for (const option of ["--question-file", "--request-file", "--result-file"]) {
      const index = args.indexOf(option)
      if (index >= 0) {
        const path = args[index + 1]
        temporaryFiles.push({ path, content: await readFile(path, "utf8") })
      }
    }
    return success({ command: args.slice(0, 3).join(" ") })
  }
  const runtime = createAlgorithmDebugRuntime({
    execute, environment: { ADA_WORKSPACE: "D:/ada-workspace" }, temporaryRoot,
  })
  const context = { directory: "D:/large-system/algorithm-module" }

  await runtime.analysisBegin({
    question: "why did it fail?", targetTest: "a.b.Test#case1", contextMode: "reuse",
  }, context)
  await runtime.caseInspect({ caseId: "case-1" }, context)
  await runtime.runTest({ caseId: "case-1", analysisId: "analysis-1" }, context)
  await runtime.staticAnalyze({ caseId: "case-1", analysisId: "analysis-1" }, context)
  await runtime.codePathPlanCreate({
    caseId: "case-1", analysisId: "analysis-1", requestJson: "{\"planId\":\"cp-1\"}",
  }, context)
  await runtime.codePathCollect({ caseId: "case-1", planId: "cp-1" }, context)
  await runtime.jdwpPlanCreate({
    caseId: "case-1", analysisId: "analysis-1", requestJson: "{\"planId\":\"jdwp-1\"}",
  }, context)
  await runtime.jdwpCollect({ caseId: "case-1", planId: "jdwp-1" }, context)
  await runtime.artifactRead({
    caseId: "case-1", artifactId: "run-1-stdout", offsetBytes: 7, maxBytes: 1024,
  }, context)
  await runtime.analysisComplete({
    caseId: "case-1", analysisId: "analysis-1", resultJson: "{\"finalAnswer\":\"answer\"}",
  }, context)

  const businessCalls = calls.filter(call => !["workspace", "project"].includes(call.args[0]))
  assert.deepEqual(businessCalls.map(call => withoutTemporaryPath(call.args)), [
    ["case", "open", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--test", "a.b.Test#case1", "--question-file", "<temp>", "--context-mode", "reuse"],
    ["case", "inspect", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1"],
    ["run", "execute", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1", "--analysis-id", "analysis-1"],
    ["static", "analyze", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1", "--analysis-id", "analysis-1"],
    ["plan", "codepath", "create", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1", "--analysis-id", "analysis-1", "--request-file", "<temp>"],
    ["collection", "codepath", "execute", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1", "--plan-id", "cp-1"],
    ["plan", "jdwp", "create", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1", "--analysis-id", "analysis-1", "--request-file", "<temp>"],
    ["collection", "jdwp", "execute", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1", "--plan-id", "jdwp-1"],
    ["artifact", "read", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1", "--artifact-id", "run-1-stdout", "--offset-bytes", "7",
      "--max-bytes", "1024"],
    ["analysis", "complete", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1", "--analysis-id", "analysis-1", "--result-file", "<temp>"],
  ])
  assert.deepEqual(temporaryFiles.map(value => value.content), [
    "why did it fail?", "{\"planId\":\"cp-1\"}", "{\"planId\":\"jdwp-1\"}",
    "{\"finalAnswer\":\"answer\"}",
  ])
  for (const file of temporaryFiles) {
    await assert.rejects(stat(file.path))
  }
  assert.ok(calls.every(call => call.cwd === context.directory))
})

test("stops before the business command when project preparation fails", async () => {
  const calls = []
  const failure = JSON.stringify({
    schemaVersion: "2.0", success: false, code: "PROJECT_NOT_MAVEN",
    message: "Project is not Maven", data: null, artifacts: [],
  })
  const runtime = createAlgorithmDebugRuntime({
    execute: async args => {
      calls.push([...args])
      return args[0] === "workspace" ? success({ created: false }) : failure
    },
    environment: { ADA_WORKSPACE: "D:/ada-workspace" }, temporaryRoot: tmpdir(),
  })

  const response = await runtime.caseInspect(
    { caseId: "case-1" }, { directory: "D:/not-maven" })

  assert.equal(response, failure)
  assert.equal(calls.length, 2)
})

test("passes explicit Case and Adapter identity while defaulting to Context reuse", async t => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), "ada-tool-runtime-options-test-"))
  t.after(() => rm(temporaryRoot, { recursive: true, force: true }))
  const calls = []
  const runtime = createAlgorithmDebugRuntime({
    execute: async args => {
      calls.push([...args])
      if (args[0] === "workspace") return success({ created: false })
      if (args[0] === "project") return success({ registration: { projectId: "demo-project" } })
      return success({ opened: true })
    },
    environment: { ADA_WORKSPACE: "D:/ada-workspace" }, temporaryRoot,
  })

  await runtime.analysisBegin({
    question: "continue", targetTest: "a.b.Test#case1", caseId: "case-1", adapterId: "wafer-demo",
  }, { directory: "D:/module" })

  assert.deepEqual(withoutTemporaryPath(calls.at(-1)), [
    "case", "open", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
    "--test", "a.b.Test#case1", "--question-file", "<temp>", "--context-mode", "reuse",
    "--case-id", "case-1", "--adapter", "wafer-demo",
  ])
})

test("removes a temporary request when CLI execution throws", async t => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), "ada-tool-runtime-cleanup-test-"))
  t.after(() => rm(temporaryRoot, { recursive: true, force: true }))
  let requestPath
  const runtime = createAlgorithmDebugRuntime({
    execute: async args => {
      if (args[0] === "workspace") return success({ created: false })
      if (args[0] === "project") return success({ registration: { projectId: "demo-project" } })
      requestPath = args[args.indexOf("--request-file") + 1]
      throw new Error("boom at C:/private/path")
    },
    environment: { ADA_WORKSPACE: "D:/ada-workspace" }, temporaryRoot,
  })

  const response = await runtime.codePathPlanCreate({
    caseId: "case-1", analysisId: "analysis-1", requestJson: "{}",
  }, { directory: "D:/module" })

  assert.equal(JSON.parse(response).code, "ADA_CLI_EXECUTION_FAILED")
  assert.doesNotMatch(response, /private/)
  await assert.rejects(stat(requestPath))
})

test("rejects an oversized plan before project preparation", async () => {
  let calls = 0
  const runtime = createAlgorithmDebugRuntime({
    execute: async () => { calls += 1; return success({}) },
    environment: { ADA_WORKSPACE: "D:/ada-workspace" }, temporaryRoot: tmpdir(),
  })

  await assert.rejects(runtime.codePathPlanCreate({
    caseId: "case-1", analysisId: "analysis-1", requestJson: "x".repeat(65_537),
  }, { directory: "D:/module" }), RangeError)
  assert.equal(calls, 0)
})

function withoutTemporaryPath(args) {
  const copy = [...args]
  for (const option of ["--question-file", "--request-file", "--result-file"]) {
    const index = copy.indexOf(option)
    if (index >= 0) copy[index + 1] = "<temp>"
  }
  return copy
}
