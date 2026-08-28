import { tool } from "@opencode-ai/plugin"
import { runAdaCommand } from "../lib/ada-cli.mjs"
import {
  defaultLauncher,
  dfxDirectory,
  dfxEnabled,
  resultJsonDirectory,
  workspaceDirectory,
} from "../lib/installation.mjs"
import { createCaseInteractionRecorder } from "../lib/case-interaction-recorder.mjs"
import { createAlgorithmDebugRuntime } from "../lib/tool-runtime.mjs"

const configuredLauncher = process.env.ADA_CLI?.trim() || defaultLauncher
const configuredWorkspace = process.env.ADA_EVAL_WORKSPACE?.trim() || workspaceDirectory
const interactionRecorder = createCaseInteractionRecorder({
  enabled: dfxEnabled,
  workspaceDirectory: configuredWorkspace,
  fallbackDirectory: dfxDirectory,
})
const runtime = createAlgorithmDebugRuntime({
  workspaceDirectory: configuredWorkspace,
  resultJsonDirectory,
  interactionRecorder,
  execute: (args: string[], cwd: string) => runAdaCommand(args, cwd, Bun.spawn, {
    executable: configuredLauncher,
    environment: dfxEnabled ? { ADA_DFX_DIRECTORY: dfxDirectory } : {},
  }),
})

export const analysis_begin = tool({
  description: "Create a Case or append an analysis round for one Java/Maven target UT; returns the installed Agent result JSON directory and does not run the UT.",
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

export const algorithm_input_capture = tool({
  description: "Locate the one supported first-level target-UT String literal ending with input.json, copy and register it for this Analysis, and report input consistency with the previous Analysis. Stop on zero, multiple, computed, missing, or invalid inputs.",
  args: {
    caseId: tool.schema.string(),
    analysisId: tool.schema.string(),
  },
  execute: (args, context) => runtime.algorithmInputCapture(args, context),
})

export const case_audit = tool({
  description: "Read-only audit of Case control files, Artifact integrity, interaction JSONL and empty directories.",
  args: { caseId: tool.schema.string() },
  execute: (args, context) => runtime.caseAudit(args, context),
})

export const gantt_inspect = tool({
  description: "Read a bounded structural summary or slice of a registered Gantt JSON Artifact without business interpretation.",
  args: {
    caseId: tool.schema.string(),
    artifactId: tool.schema.string(),
    operation: tool.schema.enum(["summary", "slice"]).default("summary"),
    jsonPointer: tool.schema.string().optional(),
    offset: tool.schema.number().int().min(0).default(0),
    limit: tool.schema.number().int().min(1).max(100).default(100),
  },
  execute: (args, context) => runtime.ganttInspect(args, context),
})

export const run_test = tool({
  description: "Run the Case target UT once and archive objective process/test facts plus raw artifact references; a failed UT is evidence, not a Tool crash.",
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
  description: "Validate and archive a method-level CodePath collection plan from structured method intent; the Adapter supplies plan identity, time, and default budgets.",
  args: {
    caseId: tool.schema.string(),
    analysisId: tool.schema.string(),
    selectedMethodKeys: tool.schema.array(tool.schema.string()).min(1).max(100)
      .describe("Exact class#method(descriptor) keys selected from the current Method Catalog"),
    scopeMethodKey: tool.schema.string().optional()
      .describe("Optional selected method whose repeated invocations should be grouped into path variants"),
    rationale: tool.schema.string().describe("The concrete unresolved runtime-path question"),
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
  description: "Validate and archive a bounded JDWP plan from structured tracepoint intent; the Adapter supplies identities, time, and default budgets.",
  args: {
    caseId: tool.schema.string(),
    analysisId: tool.schema.string(),
    tracepoints: tool.schema.array(tool.schema.object({
      methodKey: tool.schema.string()
        .describe("Exact class#method(descriptor) key from the current Method Catalog"),
      line: tool.schema.number().int().positive()
        .describe("Current executable source line inside the selected method anchor"),
      maxHits: tool.schema.number().int().min(1).max(20).default(3),
      captureOnHits: tool.schema.array(tool.schema.number().int().min(1).max(20)).max(20)
        .optional().describe("Strictly increasing hit ordinals to snapshot; omit to capture every hit"),
      capture: tool.schema.object({
        locals: tool.schema.boolean().default(true),
        stack: tool.schema.boolean().default(true),
        maxFrames: tool.schema.number().int().min(1).max(64).default(8),
        maxDepth: tool.schema.number().int().min(0).max(2).default(1),
        maxItems: tool.schema.number().int().min(1).max(100).default(20),
        maxStringLength: tool.schema.number().int().min(16).max(1024).default(256),
        localNames: tool.schema.array(tool.schema.string()).max(64).default([]),
        fieldPaths: tool.schema.array(tool.schema.string()).max(128).default([]),
      }).optional(),
    })).min(1).max(20),
    rationale: tool.schema.string().describe("The concrete unresolved runtime-state question"),
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
  description: "Append the final answer, graded claims, and explicit evidence references once. CONFIRMED_FACT, VALIDATOR_CONCLUSION, and SOURCE_INFERENCE require at least one evidenceReferenceId. On rejection correct this same Analysis payload once; never open a replacement Analysis or submit a dummy result.",
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
        .describe("Evidence IDs supporting the conclusion; required and non-empty for CONFIRMED_FACT, VALIDATOR_CONCLUSION, and SOURCE_INFERENCE; do not put artifact paths here"),
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
