import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import { fileURLToPath } from "node:url"

const repositoryRoot = fileURLToPath(new URL("../../..", import.meta.url))
const decoder = new TextDecoder("utf-8", { fatal: true })
const forbiddenControlCharacters = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u

async function readUtf8(relativePath) {
  const bytes = await readFile(new URL(relativePath, new URL(`file:///${repositoryRoot.replaceAll("\\", "/")}/`)))
  return decoder.decode(bytes)
}

test("Agent and Skill assets are valid UTF-8 without forbidden control characters", async () => {
  const [agent, skill] = await Promise.all([
    readUtf8("integrations/opencode/agents/algorithm-debug.md"),
    readUtf8("skills/algorithm-debug/SKILL.md"),
  ])

  assert.doesNotMatch(agent, forbiddenControlCharacters)
  assert.doesNotMatch(skill, forbiddenControlCharacters)
  assert.match(agent, /^---\r?\n[\s\S]*?\r?\n---/u)
  assert.match(skill, /^---\r?\n[\s\S]*?\r?\n---/u)
  assert.match(agent, /algorithm-debug/u)
  assert.match(agent, /analysis_complete/u)
  assert.match(agent, /bash:\s*deny/u)
  assert.match(agent, /Never call `bash`/u)
  assert.match(skill, /interaction\.jsonl/u)
  assert.match(skill, /must not be used as Evidence/u)
})

test("planning tools expose structured intent instead of raw request JSON", async () => {
  const [toolSource, runtimeSource, skill] = await Promise.all([
    readUtf8("integrations/opencode/tools/algorithm-debug.ts"),
    readUtf8("integrations/opencode/lib/tool-runtime.mjs"),
    readUtf8("skills/algorithm-debug/SKILL.md"),
  ])

  assert.doesNotMatch(toolSource, /requestJson/u)
  assert.doesNotMatch(skill, /requestJson/u)
  assert.match(runtimeSource, /codePathPlanRequest/u)
  assert.match(runtimeSource, /jdwpPlanRequest/u)
})

test("external workspace and ToolResponse validation literals are English", async () => {
  const externalBoundaryFiles = [
    "case-management/src/main/java/org/example/algorithmdebug/casecore/CaseWorkspaceAuditor.java",
    "case-management/src/main/java/org/example/algorithmdebug/casecore/GanttArtifactInspector.java",
    "case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectRegistry.java",
    "case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceException.java",
    "ada-contracts/src/main/java/org/example/algorithmdebug/contracts/AgentFailureDiagnostic.java",
    "ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ToolResponse.java",
  ]
  const offending = []

  for (const relativePath of externalBoundaryFiles) {
    const source = await readUtf8(relativePath)
    const literals = source.match(/"(?:\\.|[^"\\])*"/gu) ?? []
    for (const literal of literals) {
      if (/\p{Script=Han}/u.test(literal)) {
        offending.push(`${relativePath}: ${literal}`)
      }
    }
  }

  assert.deepEqual(offending, [])
})

test("Case interaction DFX schema is strict and contains only bounded diagnostic fields", async () => {
  const schema = JSON.parse(await readUtf8("schemas/dfx/case-interaction-event-v1.schema.json"))

  assert.equal(schema.$id, "https://algorithm-debug-agent/schemas/dfx/case-interaction-event-v1.schema.json")
  assert.equal(schema.additionalProperties, false)
  assert.deepEqual(schema.required, [
    "schemaVersion", "timestamp", "level", "eventType", "source", "outcome",
    "sessionId", "invocationId",
  ])
  assert.deepEqual(schema.properties.eventType.enum, [
    "CASE_INTERACTION_STARTED", "TOOL_CALL_STARTED", "TOOL_CALL_COMPLETED",
    "TOOL_CALL_FAILED", "CLI_PROCESS_STARTED", "CLI_PROCESS_COMPLETED",
    "CLI_PROCESS_FAILED", "LOG_TRUNCATED",
  ])
  for (const forbidden of ["details", "args", "response", "message"]) {
    assert.equal(schema.properties[forbidden], undefined)
  }
})
