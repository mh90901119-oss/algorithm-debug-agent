import assert from "node:assert/strict";
import { cp, mkdtemp, mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const integrationRoot = path.join(repositoryRoot, "integrations", "qwen-cli");

function runScript(name, profileRoot, options = {}) {
  const result = invokeScript(name, profileRoot, options);
  assert.equal(result.status, 0, `${name} failed:\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
}

function invokeScript(name, profileRoot, options = {}) {
  const effectiveRepositoryRoot = options.repositoryRoot ?? repositoryRoot;
  return spawnSync("powershell.exe", [
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", path.join(integrationRoot, name),
    "-RepositoryRoot", effectiveRepositoryRoot,
    "-Scope", options.scope ?? "TestProfile",
    "-ProfileRoot", profileRoot,
  ], { encoding: "utf8", env: options.env ?? process.env });
}

async function createTemporaryRepository(knowledgeDirectory) {
  const root = await mkdtemp(path.join(os.tmpdir(), "ada-qwen-repository-"));
  const profileRoot = path.join(root, "profile");
  const userProfile = path.join(root, "user-profile");
  await Promise.all([
    cp(path.join(repositoryRoot, "agent-definition"),
      path.join(root, "agent-definition"), { recursive: true }),
    cp(path.join(repositoryRoot, "integrations", "host-adapter-kit", "scripts"),
      path.join(root, "integrations", "host-adapter-kit", "scripts"), { recursive: true }),
    cp(path.join(repositoryRoot, "integrations", "qwen-cli", "adapter-manifest.json"),
      path.join(root, "integrations", "qwen-cli", "adapter-manifest.json")),
    cp(path.join(repositoryRoot, "integrations", "qwen-cli", "templates"),
      path.join(root, "integrations", "qwen-cli", "templates"), { recursive: true }),
    cp(path.join(repositoryRoot, "bin", "ada-mcp.cmd"),
      path.join(root, "bin", "ada-mcp.cmd")),
  ]);
  await mkdir(path.join(root, "config"), { recursive: true });
  const settings = JSON.parse(await readFile(
    path.join(repositoryRoot, "config", "mcp-agent-settings.json"), "utf8"));
  settings.knowledgeDirectory = knowledgeDirectory;
  await writeFile(path.join(root, "config", "mcp-agent-settings.json"),
    `${JSON.stringify(settings, null, 2)}\n`, "utf8");
  return { root, profileRoot, userProfile };
}

test("install check reinstall and uninstall are idempotent and preserve workspace", async () => {
  const profileRoot = await mkdtemp(path.join(os.tmpdir(), "ada-qwen-profile-"));
  const extensionRoot = path.join(profileRoot, "extensions", "algorithm-debug-agent");
  const workspaceRoot = path.join(profileRoot, "workspace");
  const sentinelPath = path.join(workspaceRoot, "sentinel.txt");
  const changedOwnedPath = path.join(extensionRoot, "agents", "algorithm-debug-agent.md");
  try {
    await mkdir(workspaceRoot, { recursive: true });
    await writeFile(sentinelPath, "keep", "utf8");

    assert.match(runScript("install.ps1", profileRoot), /QWEN_ADAPTER_INSTALL_OK/);
    assert.match(runScript("check.ps1", profileRoot), /QWEN_ADAPTER_CHECK_OK/);
    assert.match(runScript("install.ps1", profileRoot), /QWEN_ADAPTER_INSTALL_OK/);
    assert.match(runScript("check.ps1", profileRoot), /QWEN_ADAPTER_CHECK_OK/);

    await writeFile(changedOwnedPath, "user-owned change", "utf8");
    assert.match(runScript("uninstall.ps1", profileRoot), /QWEN_ADAPTER_UNINSTALL_OK/);
    assert.equal(await readFile(sentinelPath, "utf8"), "keep");
    assert.equal(await readFile(changedOwnedPath, "utf8"), "user-owned change");
    await assert.rejects(stat(path.join(extensionRoot, "qwen-extension.json")), { code: "ENOENT" });

    assert.match(runScript("uninstall.ps1", profileRoot), /QWEN_ADAPTER_UNINSTALL_OK/);
  } finally {
    await rm(profileRoot, { recursive: true, force: true });
  }
});

test("PowerShell lifecycle and MCP launcher scripts parse without errors", () => {
  const scripts = [
    path.join(integrationRoot, "install.ps1"),
    path.join(integrationRoot, "check.ps1"),
    path.join(integrationRoot, "uninstall.ps1"),
    path.join(integrationRoot, "AdapterLifecycle.psm1"),
    path.join(repositoryRoot, "scripts", "run-mcp.ps1"),
    path.join(repositoryRoot, "scripts", "build-agent.ps1"),
  ];
  for (const script of scripts) {
    const command = [
      "$tokens=$null; $errors=$null;",
      `[System.Management.Automation.Language.Parser]::ParseFile('${script.replaceAll("'", "''")}',[ref]$tokens,[ref]$errors) | Out-Null;`,
      "if($errors.Count -gt 0){ $errors | ForEach-Object { Write-Error $_.Message }; exit 1 }",
    ].join(" ");
    const result = spawnSync("powershell.exe", ["-NoProfile", "-Command", command], { encoding: "utf8" });
    assert.equal(result.status, 0, `${path.basename(script)} parse failed:\n${result.stderr}`);
  }
});

test("reinstall refuses unowned extension files instead of overwriting them", async () => {
  const profileRoot = await mkdtemp(path.join(os.tmpdir(), "ada-qwen-unowned-"));
  const unownedPath = path.join(profileRoot, "extensions", "algorithm-debug-agent", "user-note.txt");
  try {
    runScript("install.ps1", profileRoot);
    await writeFile(unownedPath, "preserve", "utf8");

    const reinstall = invokeScript("install.ps1", profileRoot);
    assert.notEqual(reinstall.status, 0);
    assert.match(reinstall.stderr, /unowned assets/i);
    assert.equal(await readFile(unownedPath, "utf8"), "preserve");

    const check = invokeScript("check.ps1", profileRoot);
    assert.notEqual(check.status, 0);
    assert.match(check.stderr, /unowned assets/i);

    runScript("uninstall.ps1", profileRoot);
    assert.equal(await readFile(unownedPath, "utf8"), "preserve");
  } finally {
    await rm(profileRoot, { recursive: true, force: true });
  }
});

test("installer expands the two supported environment variables in optional knowledge path", async () => {
  const temporary = await createTemporaryRepository("%USERPROFILE%\\knowledge");
  try {
    await mkdir(path.join(temporary.userProfile, "knowledge"), { recursive: true });

    const result = invokeScript("install.ps1", temporary.profileRoot, {
      repositoryRoot: temporary.root,
      env: { ...process.env, USERPROFILE: temporary.userProfile },
    });
    assert.equal(result.status, 0, `install failed:\n${result.stdout}\n${result.stderr}`);
  } finally {
    await rm(temporary.root, { recursive: true, force: true });
  }
});

test("installer rejects a configured placeholder when its environment variable is absent", async () => {
  const temporary = await createTemporaryRepository("%LOCALAPPDATA%\\knowledge");
  const environment = { ...process.env };
  delete environment.LOCALAPPDATA;
  try {
    const result = invokeScript("install.ps1", temporary.profileRoot, {
      repositoryRoot: temporary.root,
      env: environment,
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /LOCALAPPDATA is not configured/);
  } finally {
    await rm(temporary.root, { recursive: true, force: true });
  }
});

test("invalid knowledge leaves the previously installed profile unchanged", async () => {
  const temporary = await createTemporaryRepository("");
  const extensionAgent = path.join(temporary.profileRoot, "extensions",
    "algorithm-debug-agent", "agents", "algorithm-debug-agent.md");
  const oversizedKnowledge = path.join(temporary.root, "oversized-knowledge");
  try {
    assert.equal(invokeScript("install.ps1", temporary.profileRoot, {
      repositoryRoot: temporary.root,
    }).status, 0);
    const installedBefore = await readFile(extensionAgent, "utf8");

    await mkdir(oversizedKnowledge, { recursive: true });
    await writeFile(path.join(oversizedKnowledge, "too-large.md"), "x".repeat(65537), "utf8");
    const settingsPath = path.join(temporary.root, "config", "mcp-agent-settings.json");
    const settings = JSON.parse(await readFile(settingsPath, "utf8"));
    settings.knowledgeDirectory = oversizedKnowledge;
    await writeFile(settingsPath, `${JSON.stringify(settings, null, 2)}\n`, "utf8");

    const reinstall = invokeScript("install.ps1", temporary.profileRoot, {
      repositoryRoot: temporary.root,
    });
    assert.notEqual(reinstall.status, 0);
    assert.match(reinstall.stderr, /file byte budget/i);
    assert.equal(await readFile(extensionAgent, "utf8"), installedBefore);
    assert.match(runScript("check.ps1", temporary.profileRoot, {
      repositoryRoot: temporary.root,
    }), /QWEN_ADAPTER_CHECK_OK/);
  } finally {
    await rm(temporary.root, { recursive: true, force: true });
  }
});

test("MCP launcher rejects host overrides and exposes only the project path", async () => {
  const launcher = path.join(repositoryRoot, "bin", "ada-mcp.cmd");
  const launcherText = await readFile(launcher, "utf8");
  const runnerText = await readFile(path.join(repositoryRoot, "scripts", "run-mcp.ps1"), "utf8");
  const escapedLauncher = launcher.replaceAll("'", "''");
  const result = spawnSync("powershell.exe", ["-NoProfile", "-Command",
    `& '${escapedLauncher}' --workspace injected; exit $LASTEXITCODE`], {
    encoding: "utf8",
  });

  assert.equal(result.status, 2);
  assert.match(result.stderr, /ADA_MCP_INVALID_ARGUMENTS/);
  assert.doesNotMatch(launcherText, /%\*/);
  assert.doesNotMatch(runnerText.slice(0, runnerText.indexOf("$ErrorActionPreference")),
    /McpJar|MainClass|CollectorJar/i);
  assert.match(runnerText, /algorithm-debug-mcp-server\\target/);
  assert.match(runnerText,
    /\$targetJava\s*=\s*if\s*\(\[string\]::IsNullOrWhiteSpace\(\$settings\.targetJavaHome\)\)\s*\{\s*\$agentJava/s);
});

test("custom profile roots are restricted to isolated TestProfile scope", async () => {
  const profileRoot = await mkdtemp(path.join(os.tmpdir(), "ada-qwen-user-profile-"));
  try {
    const result = invokeScript("install.ps1", profileRoot, { scope: "User" });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /ProfileRoot is supported only with TestProfile scope/);
  } finally {
    await rm(profileRoot, { recursive: true, force: true });
  }
});
