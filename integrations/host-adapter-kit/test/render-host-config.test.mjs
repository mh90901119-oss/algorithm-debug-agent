import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
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
  const manifest = JSON.parse(await readFile(
    path.join(repositoryRoot, "agent-definition", "capability-manifest-v1.json"), "utf8"));

  assert.deepEqual(Object.keys(extension.mcpServers), ["algorithm-debug"]);
  assert.deepEqual(extension.mcpServers["algorithm-debug"].includeTools, manifest.tools);
  assert.equal(containsSchemaCopy(extension), false);
  assert.doesNotMatch(agent, /inputSchema|outputSchema|\$defs/);
  assert.match(agent, /canonical-prompt-sha256: [a-f0-9]{64}/);
});

test("knowledge markdown is included in stable relative-path order with provenance", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "ada-knowledge-"));
  try {
    await mkdir(path.join(temporary, "nested"));
    await writeFile(path.join(temporary, "z.md"), "zeta knowledge", "utf8");
    await writeFile(path.join(temporary, "nested", "a.md"), "alpha knowledge", "utf8");

    const rendered = await renderHostConfig({
      adapterManifest,
      repositoryRoot,
      knowledgeDirectory: temporary,
    });
    const agent = rendered.files.get("agents/algorithm-debug-agent.md");
    const alphaHash = createHash("sha256").update("alpha knowledge").digest("hex");

    assert.equal(rendered.knowledge.status, "INCLUDED");
    assert.deepEqual(rendered.knowledge.files.map((file) => file.relativePath), [
      "nested/a.md", "z.md",
    ]);
    assert.equal(rendered.knowledge.files[0].sha256, alphaHash);
    assert.equal(rendered.knowledge.totalBytes, 29);
    assert.ok(agent.indexOf("alpha knowledge") < agent.indexOf("zeta knowledge"));
    assert.match(agent, new RegExp(`nested/a\\.md.*${alphaHash}`, "s"));
    assert.doesNotMatch(agent, new RegExp(temporary.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("an empty knowledge directory is a valid no-hint profile", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "ada-empty-knowledge-"));
  try {
    const rendered = await renderHostConfig({
      adapterManifest,
      repositoryRoot,
      knowledgeDirectory: temporary,
    });
    assert.deepEqual(rendered.knowledge, {
      status: "AVAILABLE_EMPTY",
      files: [],
      totalBytes: 0,
    });
    assert.doesNotMatch(rendered.files.get("agents/algorithm-debug-agent.md"), /BEGIN KNOWLEDGE_HINT/);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("knowledge loader rejects non UTF-8 markdown and symbolic links", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "ada-invalid-knowledge-"));
  try {
    await writeFile(path.join(temporary, "invalid.md"), Buffer.from([0xc3, 0x28]));
    await assert.rejects(renderHostConfig({
      adapterManifest, repositoryRoot, knowledgeDirectory: temporary,
    }), /UTF-8/);

    await rm(path.join(temporary, "invalid.md"));
    const realDirectory = path.join(temporary, "real");
    await mkdir(realDirectory);
    await writeFile(path.join(realDirectory, "hint.md"), "hint", "utf8");
    await symlink(realDirectory, path.join(temporary, "linked"), "junction");
    await assert.rejects(renderHostConfig({
      adapterManifest, repositoryRoot, knowledgeDirectory: temporary,
    }), /symbolic link/i);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("knowledge loader enforces depth file count per-file and total byte budgets", async () => {
  const roots = [];
  try {
    const tooDeep = await mkdtemp(path.join(os.tmpdir(), "ada-knowledge-depth-"));
    roots.push(tooDeep);
    const depthFive = path.join(tooDeep, "1", "2", "3", "4", "5");
    await mkdir(depthFive, { recursive: true });
    await writeFile(path.join(depthFive, "hint.md"), "hint", "utf8");
    await assert.rejects(renderHostConfig({
      adapterManifest, repositoryRoot, knowledgeDirectory: tooDeep,
    }), /depth/i);

    const tooMany = await mkdtemp(path.join(os.tmpdir(), "ada-knowledge-count-"));
    roots.push(tooMany);
    await Promise.all(Array.from({ length: 33 }, (_, index) =>
      writeFile(path.join(tooMany, `${String(index).padStart(2, "0")}.md`), "x", "utf8")));
    await assert.rejects(renderHostConfig({
      adapterManifest, repositoryRoot, knowledgeDirectory: tooMany,
    }), /file count/i);

    const tooLarge = await mkdtemp(path.join(os.tmpdir(), "ada-knowledge-file-"));
    roots.push(tooLarge);
    await writeFile(path.join(tooLarge, "large.md"), "x".repeat(65537), "utf8");
    await assert.rejects(renderHostConfig({
      adapterManifest, repositoryRoot, knowledgeDirectory: tooLarge,
    }), /file byte budget/i);

    const tooMuch = await mkdtemp(path.join(os.tmpdir(), "ada-knowledge-total-"));
    roots.push(tooMuch);
    await Promise.all(Array.from({ length: 5 }, (_, index) =>
      writeFile(path.join(tooMuch, `${index}.md`), "x".repeat(65536), "utf8")));
    await assert.rejects(renderHostConfig({
      adapterManifest, repositoryRoot, knowledgeDirectory: tooMuch,
    }), /total byte budget/i);
  } finally {
    await Promise.all(roots.map((root) => rm(root, { recursive: true, force: true })));
  }
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
