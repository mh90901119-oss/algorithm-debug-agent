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
  assert.doesNotMatch(agent, /analysis_complete/u)
  assert.doesNotMatch(skill, /analysis_complete/u)
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
  assert.doesNotMatch(toolSource, /captureFirstMatchedHits:[\s\S]{0,100}default\(/u)
  assert.doesNotMatch(toolSource, /locals:[\s\S]{0,80}default\(false\)/u)
  assert.doesNotMatch(skill, /requestJson/u)
  assert.doesNotMatch(toolSource, /contextMode|contextId/u)
  assert.doesNotMatch(runtimeSource, /--context-mode|contextMode|contextId/u)
  assert.match(runtimeSource, /codePathPlanRequest/u)
  assert.match(runtimeSource, /jdwpPlanRequest/u)
})

test("evidence query exposes every normalized JDWP projection status", async () => {
  const toolSource = await readUtf8("integrations/opencode/tools/algorithm-debug.ts")

  for (const status of ["CAPTURED", "TRUNCATED", "REFERENCE_ONLY", "UNAVAILABLE"]) {
    assert.match(toolSource, new RegExp(`valueStatus:[\\s\\S]{0,300}"${status}"`, "u"))
  }
  assert.match(toolSource, /artifactId:[\s\S]{0,300}artifactType is exactly CODEPATH_INVOCATIONS or JDWP_SNAPSHOT_SUMMARY/su)
  assert.match(toolSource, /Never pass Method Catalog, Evidence Bundle, Raw Trace, Run, or Manifest Artifact IDs/su)
  assert.match(toolSource, /maxBytes:[\s\S]{0,200}Increase this value when one selected record does not fit/su)
})

test("Skill enforces input-first causal search and current conditional JDWP fields", async () => {
  const [skill, agent, toolSource] = await Promise.all([
    readUtf8("skills/algorithm-debug/SKILL.md"),
    readUtf8("integrations/opencode/agents/algorithm-debug.md"),
    readUtf8("integrations/opencode/tools/algorithm-debug.ts"),
  ])

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
  assert.match(skill, /captureFirstMatchedHits/u)
  assert.match(skill, /captureEveryMatchedHits/u)
  assert.match(skill, /observed.*matched.*captured.*unavailable/su)
  assert.match(skill, /Do not call.*analysis_begin.*clarification/su)
  assert.match(skill, /needs fresh deterministic work.*prior `caseId`/su)
  assert.match(skill, /explicitly requests.*runtime method path.*CodePath/su)
  assert.match(skill, /JDWP.*does not replace.*method-path/su)
  assert.match(skill, /arg\[0\].*arg0.*invalid/su)
  assert.match(toolSource, /arg\[0\].*arg0 is invalid/su)
  assert.match(skill, /full exact.*Run.*Collection.*Evidence.*Artifact.*never abbreviate/su)
  assert.match(agent, /full exact.*Run.*Collection.*Evidence.*Artifact.*never abbreviate/su)
  assert.match(agent, /runtime method path.*CodePath/su)
  for (const instructions of [skill, agent]) {
    assert.match(instructions, /After every successful `analysis_begin`.*case_audit.*early exit/su)
    assert.match(instructions, /Stop.*target execution.*not.*audit/su)
  }
  assert.doesNotMatch(skill, /for every user question|new Context/iu)
  assert.doesNotMatch(skill, /wafer-demo|wafer-demo-v1|captureOnHits|captureOnMatchedHits|`maxHits`/iu)
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

test("installer and JDWP verification cover every current deterministic boundary", async () => {
  const [installer, verifier] = await Promise.all([
    readUtf8("scripts/install-opencode.ps1"),
    readUtf8("scripts/verify-jdwp-loopback.ps1"),
  ])

  assert.match(installer, /algorithm-debug_algorithm_input_capture/u)
  assert.match(verifier, /conditionResult/u)
  assert.match(verifier, /observedHitCounts/u)
  assert.match(verifier, /matchedHitCounts/u)
  assert.match(verifier, /capturedHitCounts/u)
})

test("Eval documentation uses the target Maven module working directory without a Project path argument", async () => {
  const [evalReadme, installationGuide] = await Promise.all([
    readUtf8("agent-evals/README.md"),
    readUtf8("docs/testing/target-algorithm-environment-installation.md"),
  ])

  assert.doesNotMatch(`${evalReadme}\n${installationGuide}`, /`-Project`/u)
  assert.match(evalReadme, /目标.*Maven.*当前工作目录/su)
  assert.match(installationGuide, /目标.*Maven.*当前工作目录/su)
})
