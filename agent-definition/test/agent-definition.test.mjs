import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const definitionPath = path.join(repositoryRoot, "agent-definition", "algorithm-debug-agent-v1.json");

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, "utf8"));
}

async function sha256(filePath) {
  return createHash("sha256").update(await readFile(filePath)).digest("hex");
}

test("prompt and completion contract hashes match the agent definition", async () => {
  const definition = await readJson(definitionPath);
  const promptPath = path.resolve(repositoryRoot, definition.prompt.path);
  const completionPath = path.resolve(repositoryRoot, definition.completionContract.path);

  assert.equal(definition.prompt.sha256, await sha256(promptPath));
  assert.equal(definition.completionContract.sha256, await sha256(completionPath));
  assert.equal(definition.capabilityManifest.sha256,
    await sha256(path.resolve(repositoryRoot, definition.capabilityManifest.path)));
});

test("canonical prompt states every convergence boundary", async () => {
  const definition = await readJson(definitionPath);
  const prompt = await readFile(path.resolve(repositoryRoot, definition.prompt.path), "utf8");

  for (const requiredText of [
    "KNOWLEDGE_HINT",
    "源码关系不是运行事实",
    "Gap / Hypothesis / Predicate",
    "FALSE",
    "UNKNOWN",
    "已接受的 CausalChain",
    "只返回一个有效 JSON 对象",
    "`claims`",
    "`causalChains`",
    "`consideredHypothesisIds`",
    "`refutedHypothesisIds`",
    "`missingEvidence`",
    "`limitations`",
    "`capabilitiesUsed`",
  ]) {
    assert.match(prompt, new RegExp(requiredText.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
});

test("completion contract has one canonical body", async () => {
  const definition = await readJson(definitionPath);
  const contract = await readJson(path.resolve(repositoryRoot, definition.completionContract.path));
  const agentSchemaFiles = await readdir(path.join(repositoryRoot, "schemas", "agent"));

  assert.equal(contract.$id, "https://algorithm-debug-agent.local/schemas/agent-completion-v1.schema.json");
  assert.ok(!agentSchemaFiles.some((name) => name.includes("completion")));
  assert.deepEqual(contract.required, [
    "caseId", "analysisId", "status", "claims", "causalChains",
    "consideredHypothesisIds", "refutedHypothesisIds", "missingEvidence",
    "limitations", "capabilitiesUsed",
  ]);
});

test("legacy skill is a hash-controlled compatibility copy of the canonical prompt", async () => {
  const definition = await readJson(definitionPath);
  const prompt = (await readFile(path.resolve(repositoryRoot, definition.prompt.path), "utf8"))
    .replaceAll("\r\n", "\n").trimEnd();
  const skill = (await readFile(path.join(repositoryRoot, "skills", "algorithm-debug", "SKILL.md"), "utf8"))
    .replaceAll("\r\n", "\n");
  const marker = `<!-- canonical-prompt-sha256: ${definition.prompt.sha256} -->`;
  const markerIndex = skill.indexOf(marker);

  assert.ok(markerIndex >= 0);
  assert.equal(skill.slice(markerIndex + marker.length).trim(), prompt);
});
