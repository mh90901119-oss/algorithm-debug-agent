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
  description: "Create a Case or append an analysis round for one Java/Maven target UT; returns the installed Agent result JSON directory plus an answerContext block that must be copied verbatim into the final answer, and does not run the UT.",
  args: {
    question: tool.schema.string().describe("The user's current debugging question"),
    targetTest: tool.schema.string().describe("Target test as fully.qualified.Class#method"),
    caseId: tool.schema.string().optional().describe("Existing Case to continue; omit for a new Case"),
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
  description: "Locate the one supported first-level target-UT String literal ending with input.json or input_.json, archive its original file name once per Case, and verify reuse in later analyses. Stop on zero, multiple, computed, missing, invalid, or changed inputs.",
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
    methods: tool.schema.array(tool.schema.object({
      methodKey: tool.schema.string()
        .describe("Exact class#method(descriptor) key selected from the current Method Catalog"),
      projections: tool.schema.array(tool.schema.object({
        name: tool.schema.string().describe("Stable evidence field name"),
        path: tool.schema.string().describe("Scalar path with literal brackets: arg[0], arg[1].field, return, or return.field; arg0 is invalid"),
        required: tool.schema.boolean().describe("Whether an unreadable value is an evidence gap"),
      })).max(32),
    })).min(1).max(50)
      .describe("Exact methods and bounded scalar projections needed to answer this plan question"),
    scopeMethodKey: tool.schema.string().optional()
      .describe("Optional selected method whose repeated invocations should be grouped into path variants"),
    rationale: tool.schema.string().describe("The concrete unresolved runtime-path question"),
    questionToAnswer: tool.schema.string().describe("The single question this collection must answer"),
    hypothesis: tool.schema.string().describe("The hypothesis this collection must verify or reject"),
    basedOnEvidenceIds: tool.schema.array(tool.schema.string()).max(20)
      .describe("Prior same-Case Evidence IDs that justify this plan"),
    expectedObservations: tool.schema.array(tool.schema.string()).min(1).max(20)
      .describe("Runtime observations that can distinguish the hypothesis"),
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
      maxObservedHits: tool.schema.number().int().min(1).max(100000).default(1000)
        .describe("Maximum breakpoint observations before this tracepoint is disabled"),
      maxCapturedHits: tool.schema.number().int().min(1).max(200).default(20)
        .describe("Maximum full snapshots written after condition matching"),
      captureFirstMatchedHits: tool.schema.number().int().min(0).max(200).optional()
        .describe("Capture the first N condition-matched hits consecutively"),
      captureEveryMatchedHits: tool.schema.number().int().min(0).max(100000).optional()
        .describe("After the first group, capture every Nth matched hit; zero disables periodic sampling"),
      conditions: tool.schema.array(tool.schema.object({
        valuePath: tool.schema.string()
          .describe("Exact top-frame value path, for example candidate.wafer.id or this.state"),
        operator: tool.schema.literal("EQUALS").default("EQUALS"),
        expectedType: tool.schema.enum([
          "STRING", "LONG", "DOUBLE", "BOOLEAN", "CHAR", "ENUM", "NULL",
        ]),
        expectedValue: tool.schema.string().optional()
          .describe("Typed scalar literal; omit only when expectedType is NULL"),
      })).max(4).default([])
        .describe("Optional AND conditions evaluated before a snapshot is captured"),
      capture: tool.schema.object({
        stack: tool.schema.boolean().default(true),
        maxFrames: tool.schema.number().int().min(1).max(64).default(8),
        maxStringLength: tool.schema.number().int().min(16).max(1024).default(256),
        valuePaths: tool.schema.array(tool.schema.string()).max(128).default([])
          .describe("Exact scalar or enum paths to read; complex values return REFERENCE_ONLY"),
      }).optional(),
    })).min(1).max(20),
    rationale: tool.schema.string().describe("The concrete unresolved runtime-state question"),
    questionToAnswer: tool.schema.string().describe("The single question this collection must answer"),
    hypothesis: tool.schema.string().describe("The hypothesis this collection must verify or reject"),
    basedOnEvidenceIds: tool.schema.array(tool.schema.string()).max(20)
      .describe("Prior same-Case Evidence IDs that justify this plan"),
    expectedObservations: tool.schema.array(tool.schema.string()).min(1).max(20)
      .describe("Runtime observations that can distinguish the hypothesis"),
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

export const evidence_query = tool({
  description: "Query a verified CODEPATH_INVOCATIONS or JDWP_SNAPSHOT_SUMMARY artifact without loading the full dataset. Filters are exact structural matches and do not infer business meaning.",
  args: {
    caseId: tool.schema.string(),
    artifactId: tool.schema.string(),
    methodRef: tool.schema.string().optional()
      .describe("Exact CodePath methodRef; not valid for JDWP summaries"),
    tracepointId: tool.schema.string().optional()
      .describe("Exact JDWP tracepointId; not valid for CodePath invocations"),
    valueName: tool.schema.string().optional()
      .describe("CodePath projection name or JDWP normalized valuePath"),
    scalarValue: tool.schema.string().optional()
      .describe("Exact scalar text matched in the same value entry as valueName"),
    valueStatus: tool.schema.enum([
      "VALUE", "NULL", "UNAVAILABLE", "TRUNCATED",
      "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "OBJECT", "ARRAY",
      "CAPTURED", "REFERENCE_ONLY",
    ]).optional(),
    sequenceFrom: tool.schema.number().int().positive().optional(),
    sequenceTo: tool.schema.number().int().positive().optional(),
    offset: tool.schema.number().int().min(0).default(0),
    limit: tool.schema.number().int().positive().max(50).default(20),
    maxBytes: tool.schema.number().int().positive().max(65536).default(16384),
  },
  execute: (args, context) => runtime.evidenceQuery(args, context),
})
