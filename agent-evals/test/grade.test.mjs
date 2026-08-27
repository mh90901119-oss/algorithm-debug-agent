import assert from "node:assert/strict"
import test from "node:test"

import { gradeCase, parseOpenCodeJsonl } from "../grade.mjs"

const toolResponse = (data) => JSON.stringify({
  schemaVersion: "2.0",
  success: true,
  code: "OK",
  message: "Success",
  data,
})

function toolEvent(tool, input, output, callID) {
  return JSON.stringify({
    type: "tool_use",
    part: {
      type: "tool",
      tool: `algorithm-debug_${tool}`,
      ...(callID ? { callID } : {}),
      state: { status: "completed", input, output },
    },
  })
}

test("deduplicates repeated OpenCode snapshots for the same tool call", () => {
  const first = toolEvent("analysis_complete", { finalAnswer: "old" },
    toolResponse({ analysisId: "analysis-1" }), "call-1")
  const latest = toolEvent("analysis_complete", { finalAnswer: "final" },
    toolResponse({ analysisId: "analysis-1" }), "call-1")

  const trace = parseOpenCodeJsonl(`${first}\n${latest}`)

  assert.equal(trace.toolCalls.length, 1)
  assert.equal(trace.toolCalls[0].input.finalAnswer, "final")
})

function assertionTrace() {
  const lines = [
    toolEvent("analysis_begin", {}, toolResponse({
      caseId: "case-1",
      contextId: "context-1",
      analysisId: "analysis-1",
    })),
    toolEvent("algorithm_input_capture", {}, toolResponse({
      comparison: "FIRST_CAPTURE",
      artifact: { artifactId: "algorithm-input-analysis-1" },
    })),
    toolEvent("run_test", {}, toolResponse({
      processOutcome: "FAILED",
      testOutcome: "FAILED",
      ganttOutcome: "PRESENT",
      targetFailure: {
        exceptionClass: "org.opentest4j.AssertionFailedError",
        normalizedMessage: "expected: <164> but was: <165>",
      },
      artifacts: [{ artifactId: "run-1-surefire" }],
    })),
    toolEvent("analysis_complete", {
      conclusions: [{
        classification: "CONFIRMED_FACT",
        statement: "The assertion expected 164 but the algorithm returned 165.",
        evidenceReferenceIds: ["run-1-surefire"],
      }],
    }, toolResponse({ analysisId: "analysis-1" })),
    JSON.stringify({
      type: "text",
      part: { type: "text", text: "The test expectation is wrong: expected 164 but actual was 165." },
    }),
  ]
  return lines.join("\n")
}

const assertionCase = {
  id: "assertion-failure",
  requiredTools: ["analysis_begin", "algorithm_input_capture", "run_test", "analysis_complete"],
  forbiddenTools: [],
  expectedProcessOutcome: "FAILED",
  expectedTestOutcome: "FAILED",
  expectedGanttOutcome: "PRESENT",
  expectedExceptionClass: "org.opentest4j.AssertionFailedError",
  requiredAnswerPatterns: ["164", "165", "expectation|assertion"],
  forbiddenAnswerPatterns: ["input is missing"],
  requireAnalysisComplete: true,
  requireEvidenceReferences: true,
  allowCodePath: false,
  allowJdwp: false,
  maxTargetTestExecutions: 1,
}

test("parses OpenCode JSONL tool events and grades an evidence-backed assertion diagnosis", () => {
  const trace = parseOpenCodeJsonl(assertionTrace())
  const grade = gradeCase(assertionCase, trace, {
    openCodeExitCode: 0,
    sourceModified: false,
  })

  assert.deepEqual(trace.toolCalls.map((call) => call.name), [
    "analysis_begin",
    "algorithm_input_capture",
    "run_test",
    "analysis_complete",
  ])
  assert.equal(trace.runOutcomes[0].targetFailure.exceptionClass, "org.opentest4j.AssertionFailedError")
  assert.equal(trace.finalAnswer, "The test expectation is wrong: expected 164 but actual was 165.")
  assert.equal(grade.passed, true)
  assert.deepEqual(grade.correctnessFailures, [])
  assert.deepEqual(grade.evidenceFailures, [])
})

test("rejects a completed trace that omits evidence references and uses forbidden JDWP", () => {
  const raw = assertionTrace()
    .replace('"evidenceReferenceIds":["run-1-surefire"]', '"evidenceReferenceIds":[]')
    .replace(
      /\n\{"type":"text"/u,
      `\n${toolEvent("jdwp_collect", {}, toolResponse({ collectionId: "collection-1" }))}\n{"type":"text"`,
    )
  const grade = gradeCase(assertionCase, parseOpenCodeJsonl(raw), {
    openCodeExitCode: 0,
    sourceModified: false,
  })

  assert.equal(grade.passed, false)
  assert.match(grade.evidenceFailures.join("\n"), /evidence reference/i)
  assert.match(grade.correctnessFailures.join("\n"), /JDWP/i)
})

test("accepts a statically confirmed missing target without forcing a test run", () => {
  const raw = [
    toolEvent("analysis_begin", {}, toolResponse({
      caseId: "case-1",
      contextId: "context-1",
      analysisId: "analysis-1",
    })),
    toolEvent("algorithm_input_capture", {}, toolResponse({ status: "TARGET_TEST_NOT_FOUND" })),
    toolEvent("case_audit", {}, toolResponse({ passed: true })),
    toolEvent("analysis_complete", {
      conclusions: [{
        classification: "MISSING_EVIDENCE",
        statement: "The requested target UT does not exist in the current source tree.",
        evidenceReferenceIds: [],
      }],
    }, toolResponse({ analysisId: "analysis-1" })),
    JSON.stringify({
      type: "text",
      part: { type: "text", text: "The requested target UT does not exist." },
    }),
  ].join("\n")
  const evalCase = {
    id: "missing-ut",
    requiredTools: ["analysis_begin", "algorithm_input_capture", "case_audit", "analysis_complete"],
    forbiddenTools: ["codepath_collect", "jdwp_collect"],
    requiredAnswerPatterns: ["does not exist|not found"],
    requireAnalysisComplete: true,
    requireEvidenceReferences: false,
    maxTargetTestExecutions: 0,
  }

  const grade = gradeCase(evalCase, parseOpenCodeJsonl(raw), {
    openCodeExitCode: 0,
    sourceModified: false,
  })

  assert.equal(grade.passed, true)
  assert.deepEqual(grade.correctnessFailures, [])
})

test("requires the configured dynamic collection completion instead of only a tool call", () => {
  const lines = assertionTrace().split("\n")
  lines.splice(3, 0, toolEvent("codepath_collect", {}, toolResponse({ completion: "SUCCESS" })))
  const evalCase = {
    ...assertionCase,
    allowCodePath: true,
    expectedCollectionCompletion: "SUCCESS",
  }

  const passed = gradeCase(evalCase, parseOpenCodeJsonl(lines.join("\n")), {
    openCodeExitCode: 0,
    sourceModified: false,
  })
  assert.equal(passed.passed, true)

  lines[3] = toolEvent("codepath_collect", {}, JSON.stringify({
    schemaVersion: "2.0",
    success: false,
    code: "COLLECTION_FAILED",
    message: "Collection failed",
  }))
  const failed = gradeCase(evalCase, parseOpenCodeJsonl(lines.join("\n")), {
    openCodeExitCode: 0,
    sourceModified: false,
  })

  assert.equal(failed.passed, false)
  assert.match(failed.correctnessFailures.join("\n"), /completion SUCCESS.*none/iu)
})

test("rejects current-source or dynamic evidence collection before the first target run", () => {
  const lines = assertionTrace().split("\n")
  lines.splice(2, 0, toolEvent("static_analyze", {}, toolResponse({ status: "COMPLETE" })))

  const grade = gradeCase(assertionCase, parseOpenCodeJsonl(lines.join("\n")), {
    openCodeExitCode: 0,
    sourceModified: false,
  })

  assert.equal(grade.passed, false)
  assert.match(grade.correctnessFailures.join("\n"), /static_analyze was called before run_test/)
})
