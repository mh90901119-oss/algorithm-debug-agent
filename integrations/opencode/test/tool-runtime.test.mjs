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
    if (args[0] === "case" && args[1] === "open") {
      return success({
        caseId: "case-1", analysisId: "analysis-1",
        digest: { projectId: "demo-project" },
      })
    }
    return success({ command: args.slice(0, 3).join(" ") })
  }
  const runtime = createAlgorithmDebugRuntime({
    execute, workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results", temporaryRoot,
    now: () => new Date("2026-08-19T00:00:00Z"),
    createId: prefix => `${prefix}-1`,
  })
  const context = { directory: "D:/large-system/algorithm-module" }

  const begin = JSON.parse(await runtime.analysisBegin({
    question: "why did it fail?", targetTest: "a.b.Test#case1",
  }, context))
  assert.equal(begin.data.caseDirectory, "projects/demo-project/cases/case-1")
  assert.equal(
    begin.data.analysisDirectory,
    "projects/demo-project/cases/case-1/analyses/analysis-1",
  )
  assert.equal(
    begin.data.answerContext,
    [
      "Case directory: projects/demo-project/cases/case-1",
      "Analysis directory: projects/demo-project/cases/case-1/analyses/analysis-1",
    ].join("\n"),
  )
  await runtime.algorithmInputCapture({ caseId: "case-1", analysisId: "analysis-1" }, context)
  await runtime.caseInspect({ caseId: "case-1" }, context)
  await runtime.caseAudit({ caseId: "case-1" }, context)
  await runtime.ganttInspect({
    caseId: "case-1", artifactId: "gantt-1", operation: "slice",
    jsonPointer: "/tasks", offset: 10, limit: 20,
  }, context)
  await runtime.runTest({ caseId: "case-1", analysisId: "analysis-1" }, context)
  await runtime.staticAnalyze({ caseId: "case-1", analysisId: "analysis-1" }, context)
  await runtime.codePathPlanCreate({
    caseId: "case-1", analysisId: "analysis-1",
    methods: [{
      methodKey: "fixture.Algorithm#schedule()V",
      projections: [{ name: "waferId", path: "arg[0].waferId", required: true }],
    }],
    scopeMethodKey: "fixture.Algorithm#schedule()V",
    rationale: "Observe invocation path variants",
    questionToAnswer: "Which invocation path executed?",
    hypothesis: "One path variant was selected",
    basedOnEvidenceIds: [],
    expectedObservations: ["Observed method path"],
  }, context)
  await runtime.codePathCollect({ caseId: "case-1", planId: "cp-1" }, context)
  await runtime.jdwpPlanCreate({
    caseId: "case-1", analysisId: "analysis-1",
    tracepoints: [{
      methodKey: "fixture.Algorithm#schedule()V", line: 12,
      maxObservedHits: 500, maxCapturedHits: 2,
      captureFirstMatchedHits: 2, captureEveryMatchedHits: 0,
      conditions: [{
        valuePath: "candidate.wafer.id",
        expectedType: "STRING", expectedValue: "WAFER-1",
      }],
      capture: { valuePaths: ["state.current"] },
    }],
    rationale: "Observe selected state transitions",
    questionToAnswer: "Which state selected the branch?",
    hypothesis: "The state value selected this branch",
    basedOnEvidenceIds: ["evidence-1"],
    expectedObservations: ["Runtime state value"],
  }, context)
  await runtime.jdwpCollect({ caseId: "case-1", planId: "jdwp-1" }, context)
  await runtime.artifactRead({
    caseId: "case-1", artifactId: "run-1-stdout", offsetBytes: 7, maxBytes: 1024,
  }, context)
  await runtime.evidenceQuery({
    caseId: "case-1", artifactId: "codepath-invocations",
    methodRef: "fixture.Algorithm#schedule()V", valueName: "waferId",
    scalarValue: "WAFER-1", valueStatus: "VALUE",
    sequenceFrom: 2, sequenceTo: 8, offset: 1, limit: 10, maxBytes: 32768,
  }, context)

  const businessCalls = calls.filter(call => !["workspace", "project"].includes(call.args[0]))
  assert.deepEqual(businessCalls.map(call => withoutTemporaryPath(call.args)), [
    ["case", "open", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--test", "a.b.Test#case1", "--question-file", "<temp>"],
    ["input", "capture", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1", "--analysis-id", "analysis-1"],
    ["case", "inspect", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1"],
    ["case", "audit", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1"],
    ["gantt", "inspect", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1", "--artifact-id", "gantt-1", "--operation", "slice",
      "--offset", "10", "--limit", "20", "--json-pointer", "/tasks"],
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
    ["evidence", "query", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
      "--case-id", "case-1", "--artifact-id", "codepath-invocations",
      "--method-ref", "fixture.Algorithm#schedule()V", "--value-name", "waferId",
      "--scalar-value", "WAFER-1", "--value-status", "VALUE",
      "--sequence-from", "2", "--sequence-to", "8",
      "--offset", "1", "--limit", "10", "--max-bytes", "32768"],
  ])
  assert.deepEqual(temporaryFiles.map(value => value.content), [
    "why did it fail?",
    JSON.stringify({
      planId: "codepath-plan-1",
      methods: [{
        methodKey: "fixture.Algorithm#schedule()V",
        projections: [{ name: "waferId", path: "arg[0].waferId", required: true }],
      }],
      scopeMethodKey: "fixture.Algorithm#schedule()V",
      rationale: "Observe invocation path variants",
      intent: {
        questionToAnswer: "Which invocation path executed?",
        hypothesis: "One path variant was selected",
        basedOnEvidenceIds: [],
        expectedObservations: ["Observed method path"],
      },
      budget: { maxEvents: 100000, maxBytes: 16777216, timeoutMillis: 300000 },
      requestedAt: "2026-08-19T00:00:00.000Z",
    }),
    JSON.stringify({
      planId: "jdwp-plan-1",
      tracepoints: [{
        tracepointId: "tracepoint-1",
        methodKey: "fixture.Algorithm#schedule()V", line: 12,
        maxObservedHits: 500, maxCapturedHits: 2,
        captureFirstMatchedHits: 2, captureEveryMatchedHits: 0,
        capture: {
          stack: true, maxFrames: 8, maxStringLength: 256,
          valuePaths: ["state.current"],
        },
        conditions: [{
          valuePath: "candidate.wafer.id", operator: "EQUALS",
          expectedType: "STRING", expectedValue: "WAFER-1",
        }],
      }],
      budget: {
        maxEvents: 500, maxBytes: 33554432, timeoutMillis: 300000,
        idleTimeoutMillis: 120000,
      },
      rationale: "Observe selected state transitions",
      intent: {
        questionToAnswer: "Which state selected the branch?",
        hypothesis: "The state value selected this branch",
        basedOnEvidenceIds: ["evidence-1"],
        expectedObservations: ["Runtime state value"],
      },
      requestedAt: "2026-08-19T00:00:00.000Z",
    }),
  ])
  for (const file of temporaryFiles) {
    await assert.rejects(stat(file.path))
  }
  assert.ok(calls.every(call => call.cwd === context.directory))
})

test("labels collection execution ids without presenting them as archived Run ids", async () => {
  const runtime = createAlgorithmDebugRuntime({
    execute: async args => {
      if (args[0] === "workspace") return success({ created: true })
      if (args[0] === "project") {
        return success({ registration: { projectId: "demo-project" }, created: false })
      }
      return success({
        caseId: "case-1", analysisId: "analysis-1", runId: "collector-run-1",
        planId: "plan-1", collectionId: "collection-1", completion: "SUCCESS",
      })
    },
    workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results",
  })

  const response = JSON.parse(await runtime.codePathCollect(
    { caseId: "case-1", planId: "plan-1" }, { directory: "D:/project" }))

  assert.equal(response.data.runId, undefined)
  assert.equal(response.data.collectorExecutionRunId, "collector-run-1")
  assert.equal(response.data.collectionId, "collection-1")
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
    workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results", temporaryRoot: tmpdir(),
  })

  const response = await runtime.caseInspect(
    { caseId: "case-1" }, { directory: "D:/not-maven" })

  assert.equal(response, failure)
  assert.equal(calls.length, 2)
})

test("passes explicit Case and Adapter identity", async t => {
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
    workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results", temporaryRoot,
  })

  await runtime.analysisBegin({
    question: "continue", targetTest: "a.b.Test#case1", caseId: "case-1", adapterId: "maven-junit",
  }, { directory: "D:/module" })

  assert.deepEqual(calls.at(-2), [
    "project", "register", "--workspace", "D:/ada-workspace", "--project", "D:/module",
    "--result-directory", "D:/algorithm-results",
  ])
  assert.deepEqual(withoutTemporaryPath(calls.at(-1)), [
    "case", "open", "--workspace", "D:/ada-workspace", "--project-id", "demo-project",
    "--test", "a.b.Test#case1", "--question-file", "<temp>",
    "--case-id", "case-1", "--adapter", "maven-junit",
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
    workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results", temporaryRoot,
  })

  const response = await runtime.codePathPlanCreate({
    caseId: "case-1", analysisId: "analysis-1",
    methods: [{ methodKey: "fixture.Algorithm#schedule()V", projections: [] }], rationale: "Observe path",
  }, { directory: "D:/module" })

  assert.equal(JSON.parse(response).code, "ADA_CLI_EXECUTION_FAILED")
  assert.doesNotMatch(response, /private/)
  await assert.rejects(stat(requestPath))
})

test("returns a structured failure for an oversized plan before project preparation", async () => {
  let calls = 0
  const runtime = createAlgorithmDebugRuntime({
    execute: async () => { calls += 1; return success({}) },
    workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results", temporaryRoot: tmpdir(),
  })

  const response = JSON.parse(await runtime.codePathPlanCreate({
    caseId: "case-1", analysisId: "analysis-1",
    methods: [{ methodKey: "fixture.Algorithm#schedule()V", projections: [] }],
    rationale: "x".repeat(65_537),
  }, { directory: "D:/module" }))
  assert.equal(response.success, false)
  assert.equal(response.code, "ADA_TOOL_INPUT_INVALID")
  assert.match(response.message, /request\.json exceeds 65536 bytes/u)
  assert.equal(calls, 0)
})

test("returns a structured failure for an empty JDWP sampling policy", async () => {
  let calls = 0
  const runtime = createAlgorithmDebugRuntime({
    execute: async () => { calls += 1; return success({}) },
    workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results", temporaryRoot: tmpdir(),
  })

  const response = JSON.parse(await runtime.jdwpPlanCreate({
    caseId: "case-1", analysisId: "analysis-1",
    tracepoints: [{
      methodKey: "fixture.Algorithm#schedule()V", line: 12,
      captureFirstMatchedHits: 0, captureEveryMatchedHits: 0,
    }],
    rationale: "Observe state",
  }, { directory: "D:/module" }))
  assert.equal(response.success, false)
  assert.equal(response.code, "ADA_TOOL_INPUT_INVALID")
  assert.match(response.message, /select at least one matched hit/u)
  assert.equal(calls, 0)
})

test("derives JDWP sampling and locals defaults from explicit budgets and projections", async t => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), "ada-jdwp-defaults-test-"))
  t.after(() => rm(temporaryRoot, { recursive: true, force: true }))
  let request
  const runtime = createAlgorithmDebugRuntime({
    execute: async args => {
      if (args[0] === "workspace") return success({ created: false })
      if (args[0] === "project") {
        return success({ registration: { projectId: "demo-project" }, created: false })
      }
      request = JSON.parse(await readFile(args[args.indexOf("--request-file") + 1], "utf8"))
      return success({ planId: "jdwp-plan-1" })
    },
    workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results", temporaryRoot,
  })

  await runtime.jdwpPlanCreate({
    caseId: "case-1", analysisId: "analysis-1",
    tracepoints: [{
      methodKey: "fixture.Algorithm#schedule()V", line: 12,
      maxObservedHits: 3, maxCapturedHits: 1,
      capture: { valuePaths: ["state"] },
    }],
    rationale: "Observe one state",
  }, { directory: "D:/module" })

  assert.equal(request.tracepoints[0].captureFirstMatchedHits, 1)
  assert.equal(request.tracepoints[0].captureEveryMatchedHits, 3)
  assert.deepEqual(request.tracepoints[0].capture.valuePaths, ["state"])
})

test("rejects a JDWP collection while a CodePath target execution is active", async () => {
  let releaseCodePath
  let codePathStarted
  const release = new Promise(resolve => { releaseCodePath = resolve })
  const started = new Promise(resolve => { codePathStarted = resolve })
  const businessCalls = []
  const runtime = createAlgorithmDebugRuntime({
    execute: async args => {
      if (args[0] === "workspace") return success({ created: false })
      if (args[0] === "project") {
        return success({ registration: { projectId: "demo-project" }, created: false })
      }
      businessCalls.push(args.slice(0, 3).join(" "))
      if (args[1] === "codepath") {
        codePathStarted()
        await release
      }
      return success({
        caseId: "case-1", analysisId: "analysis-1", runId: "collector-run-1",
        planId: "plan-1", collectionId: "collection-1", completion: "SUCCESS",
      })
    },
    workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results",
  })
  const context = { directory: "D:/project" }

  const codePath = runtime.codePathCollect(
    { caseId: "case-1", planId: "codepath-plan-1" }, context)
  await started
  const jdwp = JSON.parse(await runtime.jdwpCollect(
    { caseId: "case-1", planId: "jdwp-plan-1" }, context))
  releaseCodePath()
  await codePath

  assert.equal(jdwp.success, false)
  assert.equal(jdwp.code, "ADA_TARGET_EXECUTION_SEQUENCE_VIOLATION")
  assert.match(jdwp.message, /jdwp_collect cannot start while codepath_collect is active/u)
  assert.doesNotMatch(jdwp.message, /inspect local Agent logs/u)
  assert.deepEqual(businessCalls, ["collection codepath execute"])
})

test("rejects a CodePath collection while an uninstrumented target run is active", async () => {
  let releaseRun
  let runStarted
  const release = new Promise(resolve => { releaseRun = resolve })
  const started = new Promise(resolve => { runStarted = resolve })
  const businessCalls = []
  const runtime = createAlgorithmDebugRuntime({
    execute: async args => {
      if (args[0] === "workspace") return success({ created: false })
      if (args[0] === "project") {
        return success({ registration: { projectId: "demo-project" }, created: false })
      }
      businessCalls.push(args.slice(0, 3).join(" "))
      if (args[0] === "run") {
        runStarted()
        await release
      }
      return success({ completion: "SUCCESS" })
    },
    workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results",
  })
  const context = { directory: "D:/project" }

  const run = runtime.runTest({ caseId: "case-1", analysisId: "analysis-1" }, context)
  await started
  const codePath = JSON.parse(await runtime.codePathCollect(
    { caseId: "case-1", planId: "codepath-plan-1" }, context))
  releaseRun()
  await run

  assert.equal(codePath.code, "ADA_TARGET_EXECUTION_SEQUENCE_VIOLATION")
  assert.match(codePath.message, /codepath_collect cannot start while run_test is active/u)
  assert.deepEqual(businessCalls, ["run execute --workspace"])
})

test("releases target execution state after a CLI failure response", async () => {
  const businessCalls = []
  const runtime = createAlgorithmDebugRuntime({
    execute: async args => {
      if (args[0] === "workspace") return success({ created: false })
      if (args[0] === "project") {
        return success({ registration: { projectId: "demo-project" }, created: false })
      }
      businessCalls.push(args.slice(0, 3).join(" "))
      if (args[1] === "codepath") throw new Error("collector failed")
      return success({ completion: "SUCCESS" })
    },
    workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results",
  })
  const context = { directory: "D:/project" }

  const codePath = JSON.parse(await runtime.codePathCollect(
    { caseId: "case-1", planId: "codepath-plan-1" }, context))
  const jdwp = JSON.parse(await runtime.jdwpCollect(
    { caseId: "case-1", planId: "jdwp-plan-1" }, context))

  assert.equal(codePath.code, "ADA_CLI_EXECUTION_FAILED")
  assert.equal(jdwp.success, true)
  assert.deepEqual(businessCalls, [
    "collection codepath execute",
    "collection jdwp execute",
  ])
})

function withoutTemporaryPath(args) {
  const copy = [...args]
  for (const option of ["--question-file", "--request-file", "--result-file"]) {
    const index = copy.indexOf(option)
    if (index >= 0) copy[index + 1] = "<temp>"
  }
  return copy
}
