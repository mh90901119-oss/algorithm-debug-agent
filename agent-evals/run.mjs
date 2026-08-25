import { spawn } from "node:child_process"
import { createHash, randomUUID } from "node:crypto"
import { existsSync } from "node:fs"
import { appendFile, mkdir, readFile, readdir, realpath, stat, writeFile } from "node:fs/promises"
import { delimiter, extname, isAbsolute, join, resolve } from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"

import { gradeCase, parseOpenCodeJsonl } from "./grade.mjs"
import { defaultLauncher, evalDirectory } from "../integrations/opencode/lib/installation.mjs"

const repositoryRoot = fileURLToPath(new URL("..", import.meta.url))
const MAX_CAPTURE_BYTES = 16 * 1024 * 1024
const DEFAULT_TIMEOUT_MILLIS = 10 * 60 * 1000

function requireNonBlank(value, label) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError(`${label} must be a non-empty string`)
  }
  return value.trim()
}

function requireStringArray(value, label) {
  if (value === undefined) {
    return
  }
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string" || item.trim() === "")) {
    throw new TypeError(`${label} must be an array of non-empty strings`)
  }
}

export function validateSuite(suite) {
  if (!suite || typeof suite !== "object" || Array.isArray(suite)) {
    throw new TypeError("Eval Suite must be an object")
  }
  if (Object.hasOwn(suite, "targetModule")) {
    throw new TypeError("targetModule is not allowed in a checked-in Eval Suite")
  }
  if (suite.schemaVersion !== "1.0") {
    throw new TypeError(`Unsupported Eval Suite schemaVersion: ${suite.schemaVersion}`)
  }
  requireNonBlank(suite.suiteId, "suiteId")
  requireNonBlank(suite.description, "description")
  if (!Array.isArray(suite.cases) || suite.cases.length === 0 || suite.cases.length > 100) {
    throw new TypeError("cases must contain between 1 and 100 Eval Cases")
  }

  const ids = new Set()
  for (const [index, item] of suite.cases.entries()) {
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      throw new TypeError(`cases[${index}] must be an object`)
    }
    if (Object.hasOwn(item, "targetModule")) {
      throw new TypeError(`cases[${index}].targetModule is not allowed`)
    }
    const id = requireNonBlank(item.id, `cases[${index}].id`)
    if (ids.has(id)) {
      throw new TypeError(`duplicate case id: ${id}`)
    }
    ids.add(id)
    requireNonBlank(item.question, `cases[${index}].question`)
    requireNonBlank(item.targetTest?.className, `cases[${index}].targetTest.className`)
    requireNonBlank(item.targetTest?.methodName, `cases[${index}].targetTest.methodName`)
    requireStringArray(item.requiredTools, `cases[${index}].requiredTools`)
    requireStringArray(item.forbiddenTools, `cases[${index}].forbiddenTools`)
    requireStringArray(item.requiredAnswerPatterns, `cases[${index}].requiredAnswerPatterns`)
    requireStringArray(item.forbiddenAnswerPatterns, `cases[${index}].forbiddenAnswerPatterns`)
    if (item.expectedGanttOutcome !== undefined
        && !["PRESENT", "ABSENT"].includes(item.expectedGanttOutcome)) {
      throw new TypeError(`cases[${index}].expectedGanttOutcome must be PRESENT or ABSENT`)
    }
    if (item.expectedCollectionCompletion !== undefined
        && !["SUCCESS", "TARGET_FAILED", "TRUNCATED"].includes(item.expectedCollectionCompletion)) {
      throw new TypeError(`cases[${index}].expectedCollectionCompletion is invalid`)
    }
    for (const pattern of [...(item.requiredAnswerPatterns ?? []), ...(item.forbiddenAnswerPatterns ?? [])]) {
      try {
        new RegExp(pattern, "iu")
      } catch (failure) {
        throw new TypeError(`cases[${index}] contains an invalid answer pattern: ${failure.message}`)
      }
    }
    if (item.maxTargetTestExecutions !== undefined
        && (!Number.isInteger(item.maxTargetTestExecutions) || item.maxTargetTestExecutions < 0)) {
      throw new TypeError(`cases[${index}].maxTargetTestExecutions must be a non-negative integer`)
    }
  }
  return suite
}

export function buildOpenCodeArguments({ targetModule, question, model }) {
  const args = [
    "run",
    "--agent", "algorithm-debug",
    "--format", "json",
    "--dir", requireNonBlank(targetModule, "targetModule"),
  ]
  if (model) {
    args.push("--model", requireNonBlank(model, "model"))
  }
  args.push(requireNonBlank(question, "question"))
  return args
}

export function prepareSpawn(command, args) {
  const extension = extname(command).toLowerCase()
  if (process.platform === "win32" && extension === ".ps1") {
    return {
      command: "powershell.exe",
      args: [
        "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-File", command,
        ...args,
      ],
    }
  }
  if (process.platform === "win32" && (extension === ".cmd" || extension === ".bat")) {
    return {
      command: process.env.ComSpec ?? "cmd.exe",
      args: ["/d", "/s", "/c", command, ...args],
    }
  }
  return { command, args }
}

async function resolveExecutable(command, environment) {
  if (process.platform !== "win32" || isAbsolute(command) || command.includes("/") || command.includes("\\")) {
    return command
  }
  const pathValue = environment.Path ?? environment.PATH ?? ""
  const extensions = [".exe", ".com", ".ps1", ".cmd", ".bat", ""]
  for (const directory of pathValue.split(delimiter).filter(Boolean)) {
    for (const extension of extensions) {
      const candidate = join(directory, `${command}${extension}`)
      if (existsSync(candidate)) {
        return candidate
      }
    }
  }
  return command
}

export async function runProcess(command, args, options = {}) {
  return await new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(command, args, {
      cwd: options.cwd,
      env: options.env,
      windowsHide: true,
      shell: false,
      stdio: ["ignore", "pipe", "pipe"],
    })
    let stdout = Buffer.alloc(0)
    let stderr = Buffer.alloc(0)
    let timedOut = false
    let outputLimitExceeded = false
    const timeout = setTimeout(() => {
      timedOut = true
      child.kill()
    }, options.timeoutMillis ?? DEFAULT_TIMEOUT_MILLIS)

    const append = (current, chunk) => {
      const next = Buffer.concat([current, chunk])
      if (next.length > MAX_CAPTURE_BYTES) {
        outputLimitExceeded = true
        child.kill()
        return next.subarray(0, MAX_CAPTURE_BYTES)
      }
      return next
    }
    child.stdout.on("data", (chunk) => { stdout = append(stdout, chunk) })
    child.stderr.on("data", (chunk) => { stderr = append(stderr, chunk) })
    child.once("error", (failure) => {
      clearTimeout(timeout)
      rejectPromise(failure)
    })
    child.once("close", (code, signal) => {
      clearTimeout(timeout)
      resolvePromise({
        exitCode: code ?? -1,
        signal,
        stdout: stdout.toString("utf8"),
        stderr: stderr.toString("utf8"),
        timedOut,
        outputLimitExceeded,
      })
    })
  })
}

async function runResolvedProcess(command, args, options = {}) {
  const environment = options.env ?? process.env
  const resolved = await resolveExecutable(command, environment)
  const invocation = prepareSpawn(resolved, args)
  return await runProcess(invocation.command, invocation.args, { ...options, env: environment })
}

async function readSuite(suiteArgument) {
  const candidate = suiteArgument && (isAbsolute(suiteArgument) || suiteArgument.endsWith(".json"))
    ? resolve(suiteArgument)
    : join(repositoryRoot, "agent-evals", "suites", `${(suiteArgument || "smoke").toLowerCase()}.json`)
  const source = await readFile(candidate, "utf8")
  return { path: candidate, source, suite: validateSuite(JSON.parse(source)) }
}

async function collectFiles(root, relative, output) {
  const absolute = join(root, relative)
  if (!existsSync(absolute)) {
    return
  }
  const information = await stat(absolute)
  if (information.isFile()) {
    output.push(relative.replaceAll("\\", "/"))
    return
  }
  if (!information.isDirectory()) {
    throw new Error(`Protected path is neither a regular file nor a directory: ${absolute}`)
  }
  const entries = await readdir(absolute, { withFileTypes: true })
  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    if (entry.isSymbolicLink()) {
      throw new Error(`Protected source tree contains a symbolic link: ${join(absolute, entry.name)}`)
    }
    await collectFiles(root, join(relative, entry.name), output)
  }
}

async function snapshotProtectedFiles(targetModule) {
  const files = []
  for (const relative of ["src/main", "src/test", "pom.xml"]) {
    await collectFiles(targetModule, relative, files)
  }
  const hash = createHash("sha256")
  for (const relative of files.sort()) {
    hash.update(relative)
    hash.update("\0")
    hash.update(await readFile(join(targetModule, relative)))
    hash.update("\0")
  }
  return { sha256: hash.digest("hex"), files }
}

async function sha256File(path) {
  if (!existsSync(path)) {
    return null
  }
  return createHash("sha256").update(await readFile(path)).digest("hex")
}

async function readVersion(command, args) {
  try {
    const result = await runResolvedProcess(command, args, { timeoutMillis: 20_000 })
    const text = `${result.stdout}\n${result.stderr}`.trim()
    return text.split(/\r?\n/u)[0] || `exit ${result.exitCode}`
  } catch (failure) {
    return `unavailable: ${failure.message}`
  }
}

function buildCasePrompt(item) {
  return [
    item.question.trim(),
    "",
    `Target UT: ${item.targetTest.className}#${item.targetTest.methodName}.`,
    "Use the installed algorithm-debug workflow and do not modify source files.",
  ].join("\n")
}

async function writeJson(path, value) {
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, "utf8")
}

function timestampId() {
  const time = new Date().toISOString().replace(/[-:.TZ]/gu, "").slice(0, 14)
  return `${time}-${randomUUID().slice(0, 8)}`
}

function markdownSummary(summary) {
  const rows = summary.cases.map((item) =>
    `| ${item.caseId} | ${item.passed ? "PASS" : "FAIL"} | ${item.correctnessFailures.length} | ${item.evidenceFailures.length} | ${item.efficiencyWarnings.length} |`)
  return [
    `# Agent Eval Summary: ${summary.suiteId}`,
    "",
    `- Passed: ${summary.passed}`,
    `- Failed: ${summary.failed}`,
    `- Output: ${summary.outputDirectory}`,
    "",
    "| Case | Status | Correctness failures | Evidence failures | Warnings |",
    "|---|---:|---:|---:|---:|",
    ...rows,
    "",
  ].join("\n")
}

function findIdentifier(value, name, depth = 0) {
  if (!value || typeof value !== "object" || depth > 6) return null
  if (typeof value[name] === "string" && value[name].trim()) return value[name]
  for (const child of Object.values(value)) {
    const found = findIdentifier(child, name, depth + 1)
    if (found) return found
  }
  return null
}

async function auditInteraction(path) {
  const issues = []
  const events = []
  if (!existsSync(path)) {
    issues.push("Case interaction.jsonl is missing")
  } else {
    const lines = (await readFile(path, "utf8")).split(/\r?\n/u).filter((line) => line.trim())
    for (const [index, line] of lines.entries()) {
      try {
        const event = JSON.parse(line)
        if (!event || typeof event !== "object" || Array.isArray(event)) throw new Error("not an object")
        events.push(event)
      } catch (failure) {
        issues.push(`Invalid interaction JSON at line ${index + 1}: ${failure.message}`)
      }
    }
    if (events.length === 0) issues.push("Case interaction.jsonl contains no events")
  }
  return { schemaVersion: "1.0", passed: issues.length === 0, eventCount: events.length, issues, events }
}

async function runWorkspaceAudit(agentWorkspace, identity, targetModule) {
  const caseId = findIdentifier(identity, "caseId")
  const projectId = findIdentifier(identity, "projectId")
  if (!caseId || !projectId) {
    return { response: null, audit: { schemaVersion: "1.0", passed: false,
      issues: ["analysis_begin did not expose caseId and projectId"] }, caseId, projectId }
  }
  const process = await runResolvedProcess(defaultLauncher, [
    "case", "audit", "--workspace", agentWorkspace,
    "--project-id", projectId, "--case-id", caseId,
  ], { cwd: targetModule, timeoutMillis: 60_000 })
  let response = null
  try { response = JSON.parse(process.stdout) } catch { /* represented below */ }
  const audit = response?.success === true ? response.data : {
    schemaVersion: "1.0", passed: false,
    issues: [`case audit CLI failed: exit=${process.exitCode} code=${response?.code ?? "INVALID_RESPONSE"}`],
  }
  return { response, audit, caseId, projectId }
}

async function firstRegisteredArtifact(caseRoot) {
  const directory = join(caseRoot, "artifacts")
  if (!existsSync(directory)) return null
  for (const name of (await readdir(directory)).filter((item) => item.endsWith(".json")).sort()) {
    const registration = JSON.parse(await readFile(join(directory, name), "utf8"))
    if (typeof registration?.artifact?.relativePath === "string") {
      return join(caseRoot, registration.artifact.relativePath)
    }
  }
  return null
}

function caseReview(item, trace, workspaceAudit, interactionAudit) {
  const nonSuccess = trace.toolCalls.filter((call) => call.response?.success !== true)
    .map((call) => `${call.name}:${call.response?.code ?? call.executionStatus}`)
  return [
    `# Eval Case Review: ${item.id}`, "",
    `- Workspace audit: ${workspaceAudit.passed ? "PASS" : "FAIL"}`,
    `- Interaction audit: ${interactionAudit.passed ? "PASS" : "FAIL"}`,
    `- Tool sequence: ${trace.toolCalls.map((call) => call.name).join(" -> ")}`,
    `- Non-success tools: ${nonSuccess.length ? nonSuccess.join(", ") : "none"}`,
    "",
  ].join("\n")
}

export async function runEvalSuite(options) {
  const targetModule = await realpath(options.targetModule ?? process.cwd())
  if (!existsSync(join(targetModule, "pom.xml"))) {
    throw new Error(`TargetModule does not contain pom.xml: ${targetModule}`)
  }
  const loaded = await readSuite(options.suite)
  const selectedCases = options.caseId
    ? loaded.suite.cases.filter((item) => item.id === options.caseId)
    : loaded.suite.cases
  if (selectedCases.length === 0) {
    throw new Error(`Eval Case was not found: ${options.caseId}`)
  }

  const outputRoot = resolve(options.outputRoot ?? evalDirectory)
  const runRoot = join(outputRoot, timestampId())
  const casesRoot = join(runRoot, "cases")
  const agentWorkspace = join(runRoot, "agent-workspace")
  await mkdir(casesRoot, { recursive: true })

  const environment = {
    schemaVersion: "1.0",
    suiteId: loaded.suite.suiteId,
    suiteSha256: createHash("sha256").update(loaded.source).digest("hex"),
    targetModule,
    model: options.model ?? null,
    nodeVersion: process.version,
    openCodeVersion: await readVersion(options.openCodeExecutable ?? "opencode", ["--version"]),
    javaVersion: await readVersion("java", ["--version"]),
    mavenVersion: await readVersion("mvn", ["--version"]),
    assetSha256: {
      skill: await sha256File(join(repositoryRoot, "skills", "algorithm-debug", "SKILL.md")),
      agent: await sha256File(join(repositoryRoot, "integrations", "opencode", "agents", "algorithm-debug.md")),
      tools: await sha256File(join(repositoryRoot, "integrations", "opencode", "tools", "algorithm-debug.ts")),
      runtime: await sha256File(join(repositoryRoot, "integrations", "opencode", "lib", "tool-runtime.mjs")),
    },
    startedAt: new Date().toISOString(),
  }
  await writeJson(join(runRoot, "environment.json"), environment)

  const grades = []
  for (const item of selectedCases) {
    const caseRoot = join(casesRoot, item.id)
    await mkdir(caseRoot, { recursive: true })
    const prompt = buildCasePrompt(item)
    await writeJson(join(caseRoot, "request.json"), {
      suiteId: loaded.suite.suiteId,
      case: item,
      targetModule,
      prompt,
    })

    const before = await snapshotProtectedFiles(targetModule)
    const started = Date.now()
    let processResult
    try {
      processResult = await runResolvedProcess(
        options.openCodeExecutable ?? "opencode",
        buildOpenCodeArguments({ targetModule, question: prompt, model: options.model }),
        {
          cwd: targetModule,
          env: { ...process.env, ADA_EVAL_WORKSPACE: agentWorkspace },
          timeoutMillis: options.timeoutMillis ?? DEFAULT_TIMEOUT_MILLIS,
        },
      )
    } catch (failure) {
      processResult = {
        exitCode: -1,
        signal: null,
        stdout: "",
        stderr: failure.stack ?? failure.message,
        timedOut: false,
        outputLimitExceeded: false,
      }
    }
    const after = await snapshotProtectedFiles(targetModule)
    await writeFile(join(caseRoot, "stdout.jsonl"), processResult.stdout, "utf8")
    await writeFile(join(caseRoot, "stderr.log"), processResult.stderr, "utf8")

    let trace = {
      eventCount: 0,
      toolCalls: [],
      analysisIdentity: null,
      runOutcomes: [],
      collections: [],
      analysisCompletion: null,
      finalAnswer: "",
    }
    let parseError = null
    try {
      trace = parseOpenCodeJsonl(processResult.stdout)
    } catch (failure) {
      parseError = failure.message
    }
    const grade = gradeCase(item, trace, {
      openCodeExitCode: processResult.exitCode,
      parseError,
      sourceModified: before.sha256 !== after.sha256,
    })
    grade.durationMillis = Date.now() - started
    grade.timedOut = processResult.timedOut
    grade.outputLimitExceeded = processResult.outputLimitExceeded
    grade.analysisIdentity = trace.analysisIdentity

    let audited = await runWorkspaceAudit(agentWorkspace, trace.analysisIdentity, targetModule)
    const caseWorkspaceRoot = audited.caseId && audited.projectId
      ? join(agentWorkspace, "projects", audited.projectId, "cases", audited.caseId) : null
    if (item.postAction === "CORRUPT_FIRST_ARTIFACT" && caseWorkspaceRoot) {
      const artifact = await firstRegisteredArtifact(caseWorkspaceRoot)
      if (artifact) await appendFile(artifact, "\nEVAL_INTEGRITY_TAMPER\n", "utf8")
      audited = await runWorkspaceAudit(agentWorkspace, trace.analysisIdentity, targetModule)
    }
    const interactionAudit = caseWorkspaceRoot
      ? await auditInteraction(join(caseWorkspaceRoot, "interaction.jsonl"))
      : { schemaVersion: "1.0", passed: false, eventCount: 0, issues: ["Case Workspace identity is unavailable"], events: [] }
    const expected = audited.audit.expectedArtifacts ?? []
    const actual = audited.audit.actualArtifacts ?? []
    const expectedVsActual = {
      schemaVersion: "1.0", expected, actual,
      missing: expected.filter((value) => !actual.includes(value)),
      unexpected: actual.filter((value) => !expected.includes(value)),
    }
    const expectsIntegrityFailure = item.postAction === "CORRUPT_FIRST_ARTIFACT"
    const workspacePassed = expectsIntegrityFailure
      ? audited.audit.passed === false && (audited.audit.issues ?? []).some((issue) =>
        /ARTIFACT_(HASH_MISMATCH|SIZE_MISMATCH)/u.test(issue.code ?? String(issue)))
      : audited.audit.passed === true
    if (!workspacePassed) grade.correctnessFailures.push(
      expectsIntegrityFailure ? "Case audit did not detect the intentional Artifact corruption" : "Case Workspace audit failed")
    if (!interactionAudit.passed) grade.correctnessFailures.push("Case interaction audit failed")
    if (!expectsIntegrityFailure && expectedVsActual.missing.length > 0) {
      grade.correctnessFailures.push(`Case Workspace is missing ${expectedVsActual.missing.length} expected files`)
    }
    grade.passed = grade.correctnessFailures.length === 0 && grade.evidenceFailures.length === 0

    await writeJson(join(caseRoot, "parsed-trace.json"), trace)
    await writeFile(join(caseRoot, "final-answer.md"), trace.finalAnswer, "utf8")
    await writeJson(join(caseRoot, "grade.json"), grade)
    await writeJson(join(caseRoot, "workspace-audit.json"), audited.audit)
    await writeJson(join(caseRoot, "interaction-audit.json"), interactionAudit)
    await writeJson(join(caseRoot, "expected-vs-actual.json"), expectedVsActual)
    await writeFile(join(caseRoot, "case-review.md"),
      caseReview(item, trace, audited.audit, interactionAudit), "utf8")
    grades.push(grade)

    if (options.failFast && !grade.passed) {
      break
    }
  }

  const summary = {
    schemaVersion: "1.0",
    suiteId: loaded.suite.suiteId,
    outputDirectory: runRoot,
    passed: grades.filter((item) => item.passed).length,
    failed: grades.filter((item) => !item.passed).length,
    cases: grades,
    completedAt: new Date().toISOString(),
  }
  await writeJson(join(runRoot, "summary.json"), summary)
  await writeFile(join(runRoot, "summary.md"), markdownSummary(summary), "utf8")
  return summary
}

function parseArguments(argv) {
  const options = {}
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    const value = () => {
      index += 1
      if (index >= argv.length) {
        throw new Error(`Missing value after ${argument}`)
      }
      return argv[index]
    }
    if (argument === "--suite") options.suite = value()
    else if (argument === "--case") options.caseId = value()
    else if (argument === "--target-module") options.targetModule = value()
    else if (argument === "--model") options.model = value()
    else if (argument === "--output-root") options.outputRoot = value()
    else if (argument === "--opencode") options.openCodeExecutable = value()
    else if (argument === "--timeout-seconds") options.timeoutMillis = Number.parseInt(value(), 10) * 1000
    else if (argument === "--fail-fast") options.failFast = true
    else throw new Error(`Unknown argument: ${argument}`)
  }
  return options
}

async function main() {
  const summary = await runEvalSuite(parseArguments(process.argv.slice(2)))
  process.stdout.write(`AGENT_EVAL_REPORT ${summary.outputDirectory}\n`)
  process.stdout.write(`AGENT_EVAL_RESULT passed=${summary.passed} failed=${summary.failed}\n`)
  if (summary.failed > 0) {
    process.exitCode = 1
  }
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : ""
if (invokedPath === import.meta.url) {
  main().catch((failure) => {
    process.stderr.write(`AGENT_EVAL_FAILED ${failure.stack ?? failure.message}\n`)
    process.exitCode = 1
  })
}
