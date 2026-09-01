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

test("Skill enforces input-first causal search and current conditional JDWP fields", async () => {
  const skill = await readUtf8("skills/algorithm-debug/SKILL.md")

  const input = skill.indexOf("## 1. Capture and read the algorithm input")
  const run = skill.indexOf("## 2. Execute the target UT once")
  const staticAnalysis = skill.indexOf("## 3. Build causal hypotheses from input and source")
  const dynamic = skill.indexOf("## 4. Collect only discriminating runtime evidence")
  assert.ok(input >= 0 && input < run && run < staticAnalysis && staticAnalysis < dynamic)
  assert.match(skill, /input_\.json/u)
  assert.match(skill, /questionToAnswer/u)
  assert.match(skill, /basedOnEvidenceIds/u)
  assert.match(skill, /maxObservedHits/u)
  assert.match(skill, /maxCapturedHits/u)
  assert.match(skill, /captureOnMatchedHits/u)
  assert.match(skill, /observed.*matched.*captured.*unavailable/su)
  assert.doesNotMatch(skill, /wafer-demo|wafer-demo-v1|captureOnHits|`maxHits`/iu)
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
