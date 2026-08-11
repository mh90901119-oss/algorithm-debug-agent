import { tool } from "@opencode-ai/plugin"

type ToolContext = { directory: string }

async function runAda(args: string[], context: ToolContext): Promise<string> {
  const process = Bun.spawn(["ada", ...args], {
    cwd: context.directory,
    stdout: "pipe",
    stderr: "pipe",
  })
  const [stdout, stderr, exitCode] = await Promise.all([
    new Response(process.stdout).text(),
    new Response(process.stderr).text(),
    process.exited,
  ])
  if (exitCode === 0) return stdout.trim()
  return JSON.stringify({
    schemaVersion: "1.0",
    success: false,
    code: "ADA_CLI_EXITED_NONZERO",
    message: "Algorithm Debug CLI returned a nonzero exit code",
    exitCode,
    stderr: stderr.trim(),
    stdout: stdout.trim(),
  })
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
