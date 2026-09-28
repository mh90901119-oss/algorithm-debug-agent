import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { renderHostConfig } from "../scripts/render-host-config.mjs";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const adapterManifest = path.join(repositoryRoot, "integrations", "qwen-cli", "adapter-manifest.json");

function containsSchemaCopy(value) {
  if (Array.isArray(value)) return value.some(containsSchemaCopy);
  if (value && typeof value === "object") {
    return Object.entries(value).some(([key, child]) =>
      ["inputSchema", "outputSchema", "$schema", "$defs"].includes(key) || containsSchemaCopy(child));
  }
  return false;
}

test("generated host profile references one MCP server and no copied tool schema", async () => {
  const rendered = await renderHostConfig({ adapterManifest, repositoryRoot });
  const extension = JSON.parse(rendered.files.get("qwen-extension.json"));
  const agent = rendered.files.get("agents/algorithm-debug-agent.md");

  assert.deepEqual(Object.keys(extension.mcpServers), ["algorithm-debug"]);
  assert.equal(containsSchemaCopy(extension), false);
  assert.doesNotMatch(agent, /inputSchema|outputSchema|\$defs/);
  assert.match(agent, /canonical-prompt-sha256: [a-f0-9]{64}/);
});

test("knowledge directory is optional and absence does not fail generation", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "ada-no-knowledge-"));
  try {
    const missingKnowledge = path.join(temporary, "not-created");
    const rendered = await renderHostConfig({
      adapterManifest,
      repositoryRoot,
      knowledgeDirectory: missingKnowledge,
    });

    assert.equal(rendered.knowledge.status, "ABSENT_OPTIONAL");
    assert.ok(rendered.files.has("agents/algorithm-debug-agent.md"));
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("renderer rejects nested manifest fields outside the versioned contract", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "ada-invalid-adapter-"));
  try {
    const manifest = JSON.parse(await readFile(adapterManifest, "utf8"));
    manifest.templates.evidencePolicy = "templates/policy.json";
    const invalidManifest = path.join(temporary, "adapter-manifest.json");
    await writeFile(invalidManifest, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");

    await assert.rejects(
      renderHostConfig({ adapterManifest: invalidManifest, repositoryRoot }),
      /Host adapter manifest is invalid/,
    );
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
