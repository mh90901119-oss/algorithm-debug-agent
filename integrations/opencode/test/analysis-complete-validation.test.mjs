import assert from "node:assert/strict"
import test from "node:test"

import { createAlgorithmDebugRuntime } from "../lib/tool-runtime.mjs"

test("rejects confirmed conclusions without evidence before invoking the CLI", async () => {
  let executions = 0
  const runtime = createAlgorithmDebugRuntime({
    workspaceDirectory: "D:/ada-workspace",
    resultJsonDirectory: "D:/algorithm-results",
    execute: async () => {
      executions += 1
      throw new Error("CLI should not be invoked")
    },
    now: () => new Date("2026-08-20T00:00:00.000Z"),
  })

  assert.throws(
    () => runtime.analysisComplete({
      caseId: "case-1",
      contextId: "context-1",
      analysisId: "analysis-1",
      finalAnswer: "answer",
      conclusions: [{
        classification: "CONFIRMED_FACT",
        statement: "observed",
        evidenceReferenceIds: [],
      }],
      referencedRunIds: ["run-1"],
      referencedCollectionIds: [],
      referencedEvidenceIds: [],
      referencedArtifactIds: ["artifact-1"],
      missingEvidence: [],
    }, { directory: "D:/project" }),
    /conclusions\[0\]\.evidenceReferenceIds must contain at least one evidence reference for CONFIRMED_FACT/,
  )
  assert.equal(executions, 0)
})
