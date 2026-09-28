import { createHash } from "node:crypto";
import { constants } from "node:fs";
import { access, mkdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HASH_ALGORITHM = "sha256";
const HASH_PATTERN = /^[a-f0-9]{64}$/;
const SAFE_OUTPUT_PATH = /^(?![A-Za-z]:)(?![/\\])(?!.*(?:^|[/\\])\.\.(?:[/\\]|$)).+$/;
const ADAPTER_ID_PATTERN = /^[a-z][a-z0-9-]*$/;
const MCP_SERVER_NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_-]*$/;
const SEMANTIC_VERSION_PATTERN = /^[0-9]+\.[0-9]+\.[0-9]+$/;
const TEMPLATE_TOKENS = Object.freeze({
  MCP_COMMAND_JSON: "{{MCP_COMMAND_JSON}}",
  MCP_SERVER_JSON: "{{MCP_SERVER_JSON}}",
  MCP_TOOLS_JSON: "{{MCP_TOOLS_JSON}}",
  QWEN_TOOLS_YAML: "{{QWEN_TOOLS_YAML}}",
  REPOSITORY_ROOT_JSON: "{{REPOSITORY_ROOT_JSON}}",
  SYSTEM_PROMPT: "{{SYSTEM_PROMPT}}",
  SYSTEM_PROMPT_SHA256: "{{SYSTEM_PROMPT_SHA256}}",
});

export async function renderHostConfig(options) {
  const adapterManifestPath = requiredAbsolutePath(options?.adapterManifest, "adapterManifest");
  const repositoryRoot = requiredAbsolutePath(options?.repositoryRoot, "repositoryRoot");
  const manifest = await readJson(adapterManifestPath);
  validateManifest(manifest);

  const adapterRoot = path.dirname(adapterManifestPath);
  const definitionPath = containedPath(repositoryRoot,
    path.resolve(repositoryRoot, manifest.agentDefinition), "agentDefinition");
  const definition = await readJson(definitionPath);
  const promptPath = containedPath(repositoryRoot,
    path.resolve(repositoryRoot, definition.prompt.path), "prompt.path");
  const completionPath = containedPath(repositoryRoot,
    path.resolve(repositoryRoot, definition.completionContract.path), "completionContract.path");
  const capabilitiesPath = containedPath(repositoryRoot,
    path.resolve(repositoryRoot, definition.capabilityManifest.path), "capabilityManifest.path");
  await verifyHash(promptPath, definition.prompt.sha256, "prompt");
  await verifyHash(completionPath, definition.completionContract.sha256, "completion contract");
  await verifyHash(capabilitiesPath, definition.capabilityManifest.sha256, "capability manifest");

  const capabilityManifest = await readJson(capabilitiesPath);
  const allowedTools = definition.allowedToolGroups.flatMap((group) => group.toolNames);
  if (!sameValues(allowedTools, capabilityManifest.tools)) {
    throw new Error("Agent tool permissions do not match the capability manifest");
  }

  const commandPath = path.join(repositoryRoot, "bin", "ada-mcp.cmd");
  const mcpTemplate = await loadTemplate(adapterRoot, manifest.templates.mcpServer);
  const mcpServer = JSON.parse(replaceTokens(mcpTemplate, new Map([
    [TEMPLATE_TOKENS.MCP_COMMAND_JSON, JSON.stringify(commandPath)],
    [TEMPLATE_TOKENS.MCP_TOOLS_JSON, JSON.stringify(allowedTools)],
    [TEMPLATE_TOKENS.REPOSITORY_ROOT_JSON, JSON.stringify(repositoryRoot)],
  ])));

  const extensionTemplate = await loadTemplate(adapterRoot, manifest.templates.extension);
  const extensionText = replaceTokens(extensionTemplate, new Map([
    [TEMPLATE_TOKENS.MCP_SERVER_JSON, JSON.stringify(mcpServer, null, 2)],
  ]));
  JSON.parse(extensionText);

  const prompt = await readFile(promptPath, "utf8");
  const qwenTools = allowedTools
    .map((tool) => `  - mcp__${manifest.mcpServerName}__${tool}`)
    .join("\n");
  const agentTemplate = await loadTemplate(adapterRoot, manifest.templates.agent);
  const agentText = replaceTokens(agentTemplate, new Map([
    [TEMPLATE_TOKENS.QWEN_TOOLS_YAML, qwenTools],
    [TEMPLATE_TOKENS.SYSTEM_PROMPT, prompt.trimEnd()],
    [TEMPLATE_TOKENS.SYSTEM_PROMPT_SHA256, definition.prompt.sha256],
  ]));

  const files = new Map([
    [safeRelativePath(manifest.outputFiles.extension), extensionText],
    [safeRelativePath(manifest.outputFiles.agent), agentText],
  ]);
  const knowledge = await inspectOptionalDirectory(options.knowledgeDirectory);
  if (options.outputDirectory) {
    await writeAtomically(requiredAbsolutePath(options.outputDirectory, "outputDirectory"), files);
  }
  return Object.freeze({ files, knowledge, agentDefinition: definition, capabilityManifest });
}

async function inspectOptionalDirectory(directory) {
  if (directory === undefined || directory === null || directory === "") {
    return Object.freeze({ status: "NOT_CONFIGURED" });
  }
  const absolute = requiredAbsolutePath(directory, "knowledgeDirectory");
  try {
    const details = await stat(absolute);
    if (!details.isDirectory()) throw new Error("knowledgeDirectory is not a directory");
    return Object.freeze({ status: "AVAILABLE_HINT_DIRECTORY", path: absolute });
  } catch (error) {
    if (error?.code === "ENOENT") return Object.freeze({ status: "ABSENT_OPTIONAL" });
    throw error;
  }
}

async function writeAtomically(outputDirectory, files) {
  const parent = path.dirname(outputDirectory);
  await mkdir(parent, { recursive: true });
  const staging = `${outputDirectory}.staging-${process.pid}`;
  const previous = `${outputDirectory}.previous-${process.pid}`;
  await rm(staging, { recursive: true, force: true });
  await rm(previous, { recursive: true, force: true });
  try {
    for (const [relative, content] of files) {
      const destination = containedPath(staging, path.resolve(staging, relative), "output file");
      await mkdir(path.dirname(destination), { recursive: true });
      await writeFile(destination, content, "utf8");
    }
    if (await exists(outputDirectory)) await rename(outputDirectory, previous);
    await rename(staging, outputDirectory);
    await rm(previous, { recursive: true, force: true });
  } catch (error) {
    if (!(await exists(outputDirectory)) && await exists(previous)) await rename(previous, outputDirectory);
    throw error;
  } finally {
    await rm(staging, { recursive: true, force: true });
  }
}

async function loadTemplate(adapterRoot, relative) {
  return readFile(containedPath(adapterRoot,
    path.resolve(adapterRoot, safeRelativePath(relative)), "template"), "utf8");
}

function replaceTokens(template, replacements) {
  let rendered = template;
  for (const [token, value] of replacements) rendered = rendered.split(token).join(value);
  const unresolved = rendered.match(/\{\{[A-Z0-9_]+\}\}/g);
  if (unresolved) throw new Error(`Unresolved adapter template token: ${unresolved[0]}`);
  return rendered;
}

function validateManifest(manifest) {
  const topLevelFields = ["adapterId", "agentDefinition", "host", "mcpServerName",
    "outputFiles", "schemaVersion", "templates"];
  const templateFields = ["agent", "extension", "mcpServer"];
  const outputFields = ["agent", "extension"];
  if (!hasExactFields(manifest, topLevelFields)
      || manifest.schemaVersion !== "1.0"
      || typeof manifest.adapterId !== "string"
      || !ADAPTER_ID_PATTERN.test(manifest.adapterId)
      || typeof manifest.mcpServerName !== "string"
      || !MCP_SERVER_NAME_PATTERN.test(manifest.mcpServerName)
      || !hasExactFields(manifest.host, ["minimumVersion", "name"])
      || typeof manifest.host.name !== "string"
      || manifest.host.name.length === 0
      || manifest.host.name.length > 64
      || typeof manifest.host.minimumVersion !== "string"
      || !SEMANTIC_VERSION_PATTERN.test(manifest.host.minimumVersion)
      || !hasExactFields(manifest.templates, templateFields)
      || !hasExactFields(manifest.outputFiles, outputFields)) {
    throw new Error("Host adapter manifest is invalid");
  }
  for (const relative of [manifest.agentDefinition, ...Object.values(manifest.templates),
    ...Object.values(manifest.outputFiles)]) safeRelativePath(relative);
}

function hasExactFields(value, expected) {
  return value !== null
    && typeof value === "object"
    && !Array.isArray(value)
    && JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...expected].sort());
}

function requiredAbsolutePath(value, field) {
  if (typeof value !== "string" || value.trim() !== value || !path.isAbsolute(value)) {
    throw new Error(`${field} must be an absolute path`);
  }
  return path.resolve(value);
}

function safeRelativePath(value) {
  if (typeof value !== "string" || !SAFE_OUTPUT_PATH.test(value)) {
    throw new Error("Adapter path must be a safe relative path");
  }
  return value;
}

function containedPath(root, candidate, field) {
  const relative = path.relative(path.resolve(root), path.resolve(candidate));
  if (relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative))) {
    return path.resolve(candidate);
  }
  throw new Error(`${field} escapes its allowed root`);
}

async function verifyHash(filePath, expected, label) {
  if (typeof expected !== "string" || !HASH_PATTERN.test(expected)) {
    throw new Error(`${label} hash is invalid`);
  }
  const actual = createHash(HASH_ALGORITHM).update(await readFile(filePath)).digest("hex");
  if (actual !== expected) throw new Error(`${label} hash does not match the Agent Definition`);
}

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, "utf8"));
}

function sameValues(left, right) {
  return Array.isArray(left) && Array.isArray(right)
    && left.length === right.length
    && left.every((value, index) => value === right[index]);
}

async function exists(filePath) {
  try {
    await access(filePath, constants.F_OK);
    return true;
  } catch {
    return false;
  }
}

async function main() {
  const values = new Map();
  for (let index = 2; index < process.argv.length; index += 2) {
    const key = process.argv[index];
    const value = process.argv[index + 1];
    if (!key?.startsWith("--") || value === undefined || values.has(key)) {
      throw new Error("Expected unique --name value arguments");
    }
    values.set(key, value);
  }
  const repositoryRoot = path.resolve(values.get("--repository") ?? ".");
  const defaultManifest = path.join(repositoryRoot, "integrations", "qwen-cli", "adapter-manifest.json");
  const rendered = await renderHostConfig({
    adapterManifest: path.resolve(values.get("--adapter") ?? defaultManifest),
    repositoryRoot,
    outputDirectory: values.has("--output") ? path.resolve(values.get("--output")) : undefined,
    knowledgeDirectory: values.get("--knowledge"),
  });
  process.stdout.write(`${JSON.stringify({
    files: [...rendered.files.keys()], knowledge: rendered.knowledge,
  })}\n`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    process.stderr.write(`HOST_ADAPTER_RENDER_FAILED: ${error.message}\n`);
    process.exitCode = 1;
  });
}
