import { tool } from "@opencode-ai/plugin"
import { runAdaCommand } from "../lib/ada-cli.mjs"
import { defaultLauncher } from "../lib/installation.mjs"
import { createAlgorithmDebugRuntime } from "../lib/tool-runtime.mjs"

const configuredLauncher = process.env.ADA_CLI?.trim() || defaultLauncher
const runtime = createAlgorithmDebugRuntime({
  execute: (args: string[], cwd: string) => runAdaCommand(args, cwd, Bun.spawn, {
    executable: configuredLauncher,
  }),
})

export const analysis_begin = tool({
  description: "Create a Case or append an analysis round for one Java/Maven target UT; this does not run the UT.",
  args: {
    question: tool.schema.string().describe("The user's current debugging question"),
    targetTest: tool.schema.string().describe("Target test as fully.qualified.Class#method"),
    caseId: tool.schema.string().optional().describe("Existing Case to continue; omit for a new Case"),
    contextMode: tool.schema.enum(["reuse", "new"]).default("reuse")
      .describe("Reuse the current Context unless a deliberate target change requires a new one"),
    adapterId: tool.schema.string().optional().describe("Optional target-algorithm Adapter id"),
  },
  execute: (args, context) => runtime.analysisBegin(args, context),
})

export const case_inspect = tool({
  description: "Read the bounded current Case digest and immutable historical references without running the UT.",
  args: {
    caseId: tool.schema.string(),
  },
  execute: (args, context) => runtime.caseInspect(args, context),
})

export const run_test = tool({
  description: "Run the Case target UT once and archive its structured outcome plus raw artifact references.",
  args: {
    caseId: tool.schema.string(),
    analysisId: tool.schema.string(),
  },
  execute: (args, context) => runtime.runTest(args, context),
})

export const static_analyze = tool({
  description: "Build the bounded static Method Catalog and source anchors for the current analysis round.",
  args: {
    caseId: tool.schema.string(),
    analysisId: tool.schema.string(),
  },
  execute: (args, context) => runtime.staticAnalyze(args, context),
})

export const codepath_plan_create = tool({
  description: "Validate and archive a method-level CodePath collection plan; this does not collect yet.",
  args: {
    caseId: tool.schema.string(),
    analysisId: tool.schema.string(),
    requestJson: tool.schema.string().describe("Strict CodePathPlanRequest JSON based on Method Catalog entries"),
  },
  execute: (args, context) => runtime.codePathPlanCreate(args, context),
})

export const codepath_collect = tool({
  description: "Execute one archived CodePath plan and return its bounded collection summary and artifacts.",
  args: {
    caseId: tool.schema.string(),
    planId: tool.schema.string(),
  },
  execute: (args, context) => runtime.codePathCollect(args, context),
})

export const jdwp_plan_create = tool({
  description: "Validate and archive a bounded JDWP plan for named methods or variables; this does not collect yet.",
  args: {
    caseId: tool.schema.string(),
    analysisId: tool.schema.string(),
    requestJson: tool.schema.string().describe("Strict JdwpPlanRequest JSON based on current source anchors"),
  },
  execute: (args, context) => runtime.jdwpPlanCreate(args, context),
})

export const jdwp_collect = tool({
  description: "Execute one archived JDWP plan and return its bounded collection summary and artifacts.",
  args: {
    caseId: tool.schema.string(),
    planId: tool.schema.string(),
  },
  execute: (args, context) => runtime.jdwpCollect(args, context),
})

export const artifact_read = tool({
  description: "Read a verified UTF-8 excerpt using an artifactIds entry from a Run or Collection summary; relative paths are not artifact ids.",
  args: {
    caseId: tool.schema.string(),
    artifactId: tool.schema.string(),
    offsetBytes: tool.schema.number().int().min(0).default(0),
    maxBytes: tool.schema.number().int().positive().max(65536).default(16384),
  },
  execute: (args, context) => runtime.artifactRead(args, context),
})

export const analysis_complete = tool({
  description: "Append the final answer, graded claims, and explicit evidence references; schema identity and completion time are added deterministically.",
  args: {
    caseId: tool.schema.string(),
    contextId: tool.schema.string(),
    analysisId: tool.schema.string(),
    finalAnswer: tool.schema.string().describe("Final user-facing answer; do not include hidden reasoning"),
    conclusions: tool.schema.array(tool.schema.object({
      classification: tool.schema.enum([
        "CONFIRMED_FACT", "VALIDATOR_CONCLUSION", "SOURCE_INFERENCE",
        "LLM_HYPOTHESIS", "MISSING_EVIDENCE",
      ]),
      statement: tool.schema.string(),
      evidenceReferenceIds: tool.schema.array(tool.schema.string()).default([])
        .describe("Evidence IDs supporting the conclusion; do not put artifact paths here"),
    })).default([]),
    referencedRunIds: tool.schema.array(tool.schema.string()).default([])
      .describe("Archived target Run IDs from recentRuns, not collector execution run IDs"),
    referencedCollectionIds: tool.schema.array(tool.schema.string()).default([])
      .describe("Collection IDs cited by the answer"),
    referencedEvidenceIds: tool.schema.array(tool.schema.string()).default([])
      .describe("Evidence IDs from recentEvidence; do not put Artifact IDs here"),
    referencedArtifactIds: tool.schema.array(tool.schema.string()).default([])
      .describe("Registered Artifact IDs from artifactIds; do not put relative paths here"),
    missingEvidence: tool.schema.array(tool.schema.string()).default([]),
  },
  execute: (args, context) => runtime.analysisComplete(args, context),
})
