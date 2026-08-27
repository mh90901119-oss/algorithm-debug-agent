import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

import {
  agentJavaHome,
  defaultLauncher,
  dfxDirectory,
  dfxEnabled,
  evalDirectory,
  mavenExecutable,
  openCodeConfigDirectory,
  resultJsonDirectory,
  targetJavaHome,
  workspaceDirectory,
} from "../lib/installation.mjs"

test("keeps every user-editable path explicit in the repository settings", async () => {
  const settingsUrl = new URL("../../../config/agent-settings.json", import.meta.url)
  const settings = JSON.parse(await readFile(settingsUrl, "utf8"))

  assert.deepEqual(Object.keys(settings).sort(), [
    "agentJavaHome",
    "dfxDirectory",
    "dfxEnabled",
    "evalDirectory",
    "mavenExecutable",
    "openCodeConfigDirectory",
    "resultJsonDirectory",
    "schemaVersion",
    "targetJavaHome",
    "workspaceDirectory",
  ])
  assert.equal(settings.schemaVersion, "1.0")
  assert.equal(settings.openCodeConfigDirectory, "%USERPROFILE%\\.config\\opencode")
  assert.equal(settings.workspaceDirectory, "%LOCALAPPDATA%\\algorithm-debug-agent\\workspace")
  assert.equal(settings.dfxDirectory, "%LOCALAPPDATA%\\algorithm-debug-agent\\diagnostics")
  assert.equal(settings.evalDirectory, "%LOCALAPPDATA%\\algorithm-debug-agent\\evals")
  assert.equal(settings.resultJsonDirectory, "D:\\log\\scheduler\\${runDate}\\gant")
  assert.equal(settings.dfxEnabled, true)
  assert.equal(settings.agentJavaHome, "")
  assert.equal(settings.targetJavaHome, "")
  assert.equal(settings.mavenExecutable, "")
})

test("exports a complete installation contract for direct repository execution", () => {
  for (const value of [
    defaultLauncher,
    openCodeConfigDirectory,
    workspaceDirectory,
    dfxDirectory,
    evalDirectory,
    resultJsonDirectory,
  ]) {
    assert.equal(typeof value, "string")
    assert.notEqual(value.trim(), "")
  }
  assert.equal(typeof dfxEnabled, "boolean")
  assert.equal(agentJavaHome, "")
  assert.equal(targetJavaHome, "")
  assert.equal(mavenExecutable, "")
})

test("keeps installer path selection in agent-settings.json instead of path parameters", async () => {
  const installerUrl = new URL("../../../scripts/install-opencode.ps1", import.meta.url)
  const installer = await readFile(installerUrl, "utf8")

  assert.doesNotMatch(installer, /\$ConfigRoot\b/u)
  assert.doesNotMatch(installer, /\$RepositoryRoot\b/u)
  assert.match(installer, /config[\\/]agent-settings\.json/u)
  assert.match(installer, /OPEN_CODE_CONFIG_DIRECTORY=/u)
  assert.match(installer, /WORKSPACE_DIRECTORY=/u)
  assert.match(installer, /RESULT_JSON_DIRECTORY=/u)
  assert.match(installer, /DFX_DIRECTORY=/u)
  assert.match(installer, /EVAL_DIRECTORY=/u)
  assert.match(installer, /AGENT_JAVA_HOME=/u)
  assert.match(installer, /TARGET_JAVA_HOME=/u)
  assert.match(installer, /MAVEN_EXECUTABLE=/u)
  assert.doesNotMatch(installer, /\.ada-backup-/u)
  assert.match(installer, /install-manifest\.json/u)
  assert.match(installer, /\$global:LASTEXITCODE = 0/u)
})

test("keeps Eval user paths in installed settings and isolates only its internal workspace", async () => {
  const wrapper = await readFile(
    new URL("../../../scripts/run-agent-evals.ps1", import.meta.url), "utf8")
  const runner = await readFile(new URL("../../../agent-evals/run.mjs", import.meta.url), "utf8")

  assert.doesNotMatch(wrapper, /\$TargetModule\b/u)
  assert.doesNotMatch(wrapper, /\$OutputRoot\b/u)
  assert.doesNotMatch(wrapper, /--target-module|--output-root/u)
  assert.doesNotMatch(runner, /ADA_WORKSPACE/u)
  assert.match(runner, /ADA_EVAL_WORKSPACE/u)
  assert.doesNotMatch(runner, /"\.algorithm-debug-agent\.json"/u)
})

test("keeps bundled collector paths independent from user overrides", async () => {
  const launcher = await readFile(new URL("../../../bin/ada.cmd", import.meta.url), "utf8")
  const verifier = await readFile(
    new URL("../../../scripts/verify-ada-launcher.ps1", import.meta.url), "utf8")

  assert.doesNotMatch(launcher, /ada\.local\.cmd/u)
  assert.doesNotMatch(launcher, /if not defined ADA_(?:CODEPATH_LAUNCHER|JDWP_COLLECTOR)_JAR/iu)
  assert.doesNotMatch(verifier, /\$env:ADA_JDWP_COLLECTOR_JAR/u)
})

test("keeps source-build and JDWP verification scripts in the repository", async () => {
  const build = await readFile(new URL("../../../scripts/build-agent.ps1", import.meta.url), "utf8")
  const verify = await readFile(
    new URL("../../../scripts/verify-jdwp-loopback.ps1", import.meta.url), "utf8")
  const pom = await readFile(new URL("../../../pom.xml", import.meta.url), "utf8")

  assert.match(build, /agentJavaHome/u)
  assert.match(build, /-Pcodepath-launcher/u)
  assert.match(verify, /JdwpLoopbackProbe/u)
  assert.match(verify, /marker/u)
  assert.match(pom, /third-party\/maven-repository/u)
})

test("does not expose DFX or workspace paths as Custom Tool arguments", async () => {
  const tools = await readFile(
    new URL("../tools/algorithm-debug.ts", import.meta.url), "utf8")

  assert.doesNotMatch(tools, /\b(?:logPath|dfxPath|workspacePath|outputPath)\s*:/u)
})

test("launcher verification uses the current target module without a bundled Demo path", async () => {
  const verifier = await readFile(
    new URL("../../../scripts/verify-ada-launcher.ps1", import.meta.url), "utf8")

  assert.doesNotMatch(verifier, /hellomvn/iu)
  assert.doesNotMatch(verifier, /DemoProject/u)
  assert.match(verifier, /Get-Location/u)
  assert.match(verifier, /Target Maven module was not found in the current directory/u)
})

test("keeps a repository-owned manifest-based uninstall entry point", async () => {
  const installer = await readFile(
    new URL("../../../scripts/install-opencode.ps1", import.meta.url), "utf8")
  const uninstaller = await readFile(
    new URL("../../../scripts/uninstall-opencode.ps1", import.meta.url), "utf8")

  assert.match(installer, /install-manifest\.json/u)
  assert.match(installer, /ValidateSet\("Install", "Check", "Uninstall"\)/u)
  assert.match(installer, /Managed OpenCode asset was modified after installation/u)
  assert.match(uninstaller, /-Mode Uninstall/u)
  assert.doesNotMatch(uninstaller, /ConfigRoot|ProjectRoot|Workspace/u)
})
