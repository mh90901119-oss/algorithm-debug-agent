import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
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

test("profile 2.0 hashes only the prompt and capability manifest", async () => {
  const definition = await readJson(definitionPath);
  const promptPath = path.resolve(repositoryRoot, definition.prompt.path);

  assert.equal(definition.profileVersion, "2.0");
  assert.equal(definition.prompt.sha256, await sha256(promptPath));
  assert.equal(definition.capabilityManifest.sha256,
    await sha256(path.resolve(repositoryRoot, definition.capabilityManifest.path)));
  assert.deepEqual(definition.requiredCapabilities, { tools: true });
  assert.equal(Object.hasOwn(definition, "allowedToolGroups"), false);
  assert.equal(Object.hasOwn(definition, "completionContract"), false);
  assert.equal(Object.hasOwn(definition, "defaultModelHints"), false);
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
    "ConclusionFinalization",
    "decision=ALLOWED",
    "decision=REJECTED",
  ]) {
    assert.match(prompt, new RegExp(requiredText.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
  assert.doesNotMatch(prompt, /只返回一个有效 JSON 对象/);
});

test("capability manifest is the sole distributable tool permission list", async () => {
  const definition = await readJson(definitionPath);
  const manifest = await readJson(path.resolve(repositoryRoot, definition.capabilityManifest.path));

  assert.ok(manifest.tools.length > 0);
  assert.equal(new Set(manifest.tools).size, manifest.tools.length);
  assert.ok(manifest.tools.every((tool) => /^[a-z][a-z0-9_]*$/.test(tool)));
  assert.equal(JSON.stringify(definition).includes("toolNames"), false);
  assert.equal(JSON.stringify(definition).includes("analysis_finalize"), false);
  assert.deepEqual(definition.knowledge, {
    required: false,
    role: "KNOWLEDGE_HINT",
    delivery: "PROMPT_APPEND",
    limits: {
      maxFiles: 32,
      maxDepth: 4,
      maxFileBytes: 65536,
      maxTotalBytes: 262144,
    },
  });
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
