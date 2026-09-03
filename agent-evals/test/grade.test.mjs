import assert from "node:assert/strict"
import test from "node:test"

import { gradeCase, parseOpenCodeJsonl } from "../grade.mjs"

const toolResponse = data => JSON.stringify({
  schemaVersion: "2.0", success: true, code: "OK", message: "Success", data,
})

function toolEvent(tool, input, output, callID) {
  return JSON.stringify({
    type: "tool_use",
    part: {
      type: "tool", tool: `algorithm-debug_${tool}`, ...(callID ? { callID } : {}),
      state: { status: "completed", input, output },
    },
  })
}

const beginData = {
  caseId: "case-1", analysisId: "analysis-1",
  caseDirectory: "projects/demo/cases/case-1",
  analysisDirectory: "projects/demo/cases/case-1/analyses/analysis-1",
}

const contextualAnswer = body => [
  "Case directory: projects/demo/cases/case-1",
  "Analysis directory: projects/demo/cases/case-1/analyses/analysis-1",
  "Agent capabilities used: algorithm input, run_test.",
  body,
].join("\n")

function assertionTrace() {
  return [
    toolEvent("analysis_begin", {}, toolResponse(beginData)),
    toolEvent("algorithm_input_capture", {}, toolResponse({
      comparison: "FIRST_CAPTURE", artifact: { artifactId: "algorithm-input-analysis-1" },
    })),
    toolEvent("run_test", {}, toolResponse({
      processOutcome: "FAILED", testOutcome: "FAILED", ganttOutcome: "PRESENT",
      targetFailure: {
        exceptionClass: "org.opentest4j.AssertionFailedError",
        normalizedMessage: "expected: <164> but was: <165>",
      },
      artifacts: [{ artifactId: "run-1-surefire" }],
    })),
    JSON.stringify({
      type: "text", part: { type: "text", text: contextualAnswer(
        "CONFIRMED_FACT: The assertion expected 164 but actual was 165. Evidence: run-1-surefire.") },
    }),
  ].join("\n")
}

const assertionCase = {
  id: "assertion-failure",
  requiredTools: ["analysis_begin", "algorithm_input_capture", "run_test"],
  forbiddenTools: ["analysis_complete"],
  expectedProcessOutcome: "FAILED", expectedTestOutcome: "FAILED",
  expectedGanttOutcome: "PRESENT",
  expectedExceptionClass: "org.opentest4j.AssertionFailedError",
  requiredAnswerPatterns: ["164", "165", "expectation|assertion"],
  forbiddenAnswerPatterns: ["input is missing"],
  requireAnswerContext: true, requireEvidenceReferences: true,
  allowCodePath: false, allowJdwp: false, maxTargetTestExecutions: 1,
}

test("deduplicates repeated OpenCode snapshots for the same tool call", () => {
  const first = toolEvent("run_test", {}, toolResponse({ runId: "run-old" }), "call-1")
  const latest = toolEvent("run_test", {}, toolResponse({ runId: "run-final" }), "call-1")
  const trace = parseOpenCodeJsonl(`${first}\n${latest}`)
  assert.equal(trace.toolCalls.length, 1)
  assert.equal(trace.toolCalls[0].response.data.runId, "run-final")
})

test("grades a direct evidence-backed assertion diagnosis", () => {
  const trace = parseOpenCodeJsonl(assertionTrace())
  const grade = gradeCase(assertionCase, trace, { openCodeExitCode: 0, sourceModified: false })
  assert.equal(grade.passed, true)
  assert.deepEqual(trace.toolCalls.map(call => call.name), [
    "analysis_begin", "algorithm_input_capture", "run_test",
  ])
})

test("accepts a natural capabilities summary with an intervening qualifier", () => {
  const raw = assertionTrace().replace(
    "Agent capabilities used: algorithm input, run_test.",
    "Major capabilities actually used: algorithm input, run_test.",
  )
  const grade = gradeCase(assertionCase, parseOpenCodeJsonl(raw), {
    openCodeExitCode: 0, sourceModified: false,
  })
  assert.equal(grade.passed, true)
})

test("rejects a final answer that omits its evidence identifier", () => {
  const raw = assertionTrace().replace("Evidence: run-1-surefire.", "Evidence was inspected.")
  const grade = gradeCase(assertionCase, parseOpenCodeJsonl(raw), {
    openCodeExitCode: 0, sourceModified: false,
  })
  assert.equal(grade.passed, false)
  assert.match(grade.evidenceFailures.join("\n"), /does not cite/iu)
})

test("accepts a statically confirmed missing target without a target execution", () => {
  const raw = [
    toolEvent("analysis_begin", {}, toolResponse(beginData)),
    toolEvent("algorithm_input_capture", {}, toolResponse({ status: "TARGET_TEST_NOT_FOUND" })),
    toolEvent("case_audit", {}, toolResponse({ passed: true })),
    JSON.stringify({ type: "text", part: { type: "text", text: contextualAnswer(
      "MISSING_EVIDENCE: The requested target UT does not exist.") } }),
  ].join("\n")
  const evalCase = {
    id: "missing-ut",
    requiredTools: ["analysis_begin", "algorithm_input_capture", "case_audit"],
    forbiddenTools: ["run_test", "codepath_collect", "jdwp_collect", "analysis_complete"],
    requiredAnswerPatterns: ["does not exist|not found"], forbiddenAnswerPatterns: [],
    requireAnswerContext: true, requireEvidenceReferences: false,
    allowCodePath: false, allowJdwp: false, maxTargetTestExecutions: 0,
  }
  assert.equal(gradeCase(evalCase, parseOpenCodeJsonl(raw), {
    openCodeExitCode: 0, sourceModified: false,
  }).passed, true)
})

test("requires configured dynamic collection completion", () => {
  const lines = assertionTrace().split("\n")
  lines.splice(3, 0, toolEvent("codepath_collect", {}, toolResponse({
    collectionId: "collection-1", completion: "SUCCESS",
  })))
  lines[lines.length - 1] = lines.at(-1).replace(
    "run-1-surefire.", "run-1-surefire and collection-1.")
  const evalCase = {
    ...assertionCase, allowCodePath: true, expectedCollectionCompletion: "SUCCESS",
  }
  assert.equal(gradeCase(evalCase, parseOpenCodeJsonl(lines.join("\n")), {
    openCodeExitCode: 0, sourceModified: false,
  }).passed, true)

  lines[3] = toolEvent("codepath_collect", {}, JSON.stringify({
    schemaVersion: "2.0", success: false, code: "COLLECTION_FAILED",
  }))
  const failed = gradeCase(evalCase, parseOpenCodeJsonl(lines.join("\n")), {
    openCodeExitCode: 0, sourceModified: false,
  })
  assert.equal(failed.passed, false)
})

test("rejects current-source analysis before the first target run", () => {
  const lines = assertionTrace().split("\n")
  lines.splice(2, 0, toolEvent("static_analyze", {}, toolResponse({ status: "COMPLETE" })))
  const grade = gradeCase(assertionCase, parseOpenCodeJsonl(lines.join("\n")), {
    openCodeExitCode: 0, sourceModified: false,
  })
  assert.equal(grade.passed, false)
  assert.match(grade.correctnessFailures.join("\n"), /before run_test/iu)
})

test("grades causal Plan lineage and conditional JDWP", () => {
  const lines = assertionTrace().split("\n")
  lines.splice(3, 0,
    toolEvent("static_analyze", {}, toolResponse({ status: "COMPLETE" })),
    toolEvent("codepath_plan_create", {
      questionToAnswer: "Which ordered path ran?", hypothesis: "W1 ran before W2",
      basedOnEvidenceIds: [], expectedObservations: ["Ordered invocations"],
    }, toolResponse({ planId: "codepath-plan-1" })),
    toolEvent("codepath_collect", {}, toolResponse({
      collectionId: "collection-codepath", evidenceId: "evidence-codepath", completion: "SUCCESS",
    })),
    toolEvent("jdwp_plan_create", {
      questionToAnswer: "When is W2 selected?", hypothesis: "W2 follows W1",
      basedOnEvidenceIds: ["evidence-codepath"], expectedObservations: ["W2 state"],
      tracepoints: [{
        maxObservedHits: 20, maxCapturedHits: 5,
        captureFirstMatchedHits: 2, captureEveryMatchedHits: 3,
        condition: { expectedValue: "W2" },
      }],
    }, toolResponse({ planId: "jdwp-plan-1" })),
    toolEvent("jdwp_collect", {}, toolResponse({
      collectionId: "collection-jdwp", evidenceId: "evidence-jdwp", completion: "SUCCESS",
    })),
  )
  lines[lines.length - 1] = JSON.stringify({
    type: "text", part: { type: "text", text: contextualAnswer(
      "Capabilities used: static analysis, CodePath, JDWP. W2 follows W1. Evidence: evidence-codepath and evidence-jdwp.") },
  })
  const evalCase = {
    ...assertionCase,
    requiredTools: [...assertionCase.requiredTools, "static_analyze", "codepath_plan_create",
      "codepath_collect", "jdwp_plan_create", "jdwp_collect"],
    allowCodePath: true, allowJdwp: true, expectedCollectionCompletion: "SUCCESS",
    requirePlanIntent: true, minimumPlanEvidenceReferences: 1,
    requireSequentialDynamicRefinement: true,
    requireJdwpCondition: true, requiredJdwpConditionValuePatterns: ["W2"],
    requiredAnswerPatterns: ["W1", "W2"], maxTargetTestExecutions: 3,
  }
  const grade = gradeCase(evalCase, parseOpenCodeJsonl(lines.join("\n")), {
    openCodeExitCode: 0, sourceModified: false,
  })
  assert.equal(grade.passed, true)
})

test("rejects a JDWP plan created before CodePath evidence in sequential refinement", () => {
  const lines = assertionTrace().split("\n")
  lines.splice(3, 0,
    toolEvent("codepath_plan_create", {
      questionToAnswer: "Which path ran?", hypothesis: "W1 preceded W2",
      basedOnEvidenceIds: [], expectedObservations: ["Runtime path"],
    }, toolResponse({ planId: "codepath-plan-1" })),
    toolEvent("jdwp_plan_create", {
      questionToAnswer: "Which value selected W2?", hypothesis: "W2 follows W1",
      basedOnEvidenceIds: [], expectedObservations: ["W2 state"],
      tracepoints: [{
        maxObservedHits: 20, maxCapturedHits: 5,
        condition: { expectedValue: "W2" },
      }],
    }, toolResponse({ planId: "jdwp-plan-1" })),
    toolEvent("codepath_collect", {}, toolResponse({
      collectionId: "collection-codepath", evidenceId: "evidence-codepath", completion: "SUCCESS",
    })),
    toolEvent("jdwp_collect", {}, toolResponse({
      collectionId: "collection-jdwp", evidenceId: "evidence-jdwp", completion: "SUCCESS",
    })),
  )
  lines[lines.length - 1] = JSON.stringify({
    type: "text", part: { type: "text", text: contextualAnswer(
      "Capabilities used: CodePath and JDWP. The assertion expected 164 but actual was 165. "
      + "Evidence: run-1-surefire, evidence-codepath and evidence-jdwp.") },
  })
  const evalCase = {
    ...assertionCase,
    requiredTools: [...assertionCase.requiredTools, "codepath_plan_create", "codepath_collect",
      "jdwp_plan_create", "jdwp_collect"],
    allowCodePath: true, allowJdwp: true, expectedCollectionCompletion: "SUCCESS",
    requireSequentialDynamicRefinement: true, maxTargetTestExecutions: 3,
  }
  const grade = gradeCase(evalCase, parseOpenCodeJsonl(lines.join("\n")), {
    openCodeExitCode: 0, sourceModified: false,
  })
  assert.equal(grade.passed, false)
  assert.match(grade.correctnessFailures.join("\n"), /JDWP Plan.*CodePath Evidence/iu)
})

test("rejects legacy analysis_complete even when the direct answer is valid", () => {
  const lines = assertionTrace().split("\n")
  lines.splice(3, 0, toolEvent("analysis_complete", {}, toolResponse({ analysisId: "analysis-1" })))
  const grade = gradeCase(assertionCase, parseOpenCodeJsonl(lines.join("\n")), {
    openCodeExitCode: 0, sourceModified: false,
  })
  assert.equal(grade.passed, false)
  assert.match(grade.correctnessFailures.join("\n"), /must not be called/iu)
})
