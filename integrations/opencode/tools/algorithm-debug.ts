import { tool } from "@opencode-ai/plugin"
import { runAdaCommand } from "../lib/ada-cli.mjs"

type ToolContext = { directory: string }

// 这些 Tool 名称描述最终 OpenCode 协作契约。当前 Java CLI 尚未提供全部对应命令，安装器完成命令映射、
// 外部 Workspace 和 projectId 注入并通过锁定版本端到端验证前，不得把本文件直接登记为可用工具。

async function runAda(args: string[], context: ToolContext): Promise<string> {
  return runAdaCommand(args, context.directory, Bun.spawn)
}

export const analysis_begin = tool({
  description: "Begin or resume an algorithm-debug analysis for one target Maven/JUnit test.",
  args: {
    question: tool.schema.string().describe("The user's current debugging question"),
    targetTest: tool.schema.string().describe("Target test as fully.qualified.Class#method"),
  },
  execute: (args, context) => runAda([
    "analysis", "begin", "--project", context.directory,
    "--target-test", args.targetTest, "--question", args.question,
  ], context),
})

export const run_test = tool({
  description: "Run the target UT and return its structured RunOutcomeSummary with artifact references.",
  args: {
    caseId: tool.schema.string(),
    analysisId: tool.schema.string(),
    targetTest: tool.schema.string().describe("Target test as fully.qualified.Class#method"),
  },
  execute: (args, context) => runAda([
    "run", "test", "--project", context.directory,
    "--case-id", args.caseId, "--analysis-id", args.analysisId,
    "--target-test", args.targetTest,
  ], context),
})

export const artifact_read = tool({
  description: "Read a bounded excerpt from an immutable artifact reference.",
  args: {
    caseId: tool.schema.string(),
    runId: tool.schema.string(),
    artifactId: tool.schema.string(),
    maxBytes: tool.schema.number().int().positive().max(1048576).default(65536),
  },
  execute: (args, context) => runAda([
    "artifact", "read", "--project", context.directory,
    "--case-id", args.caseId, "--run-id", args.runId,
    "--artifact-id", args.artifactId, "--max-bytes", String(args.maxBytes),
  ], context),
})

export const analysis_complete = tool({
  description: "Append the final answer and cited evidence for the current analysis round.",
  args: {
    caseId: tool.schema.string(),
    analysisId: tool.schema.string(),
    answer: tool.schema.string(),
  },
  execute: (args, context) => runAda([
    "analysis", "complete", "--project", context.directory,
    "--case-id", args.caseId, "--analysis-id", args.analysisId,
    "--answer", args.answer,
  ], context),
})
