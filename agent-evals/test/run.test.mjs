import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

import { buildOpenCodeArguments, prepareSpawn, runProcess, validateSuite } from "../run.mjs"

test("loads the versioned smoke suite and keeps the target module outside checked-in cases", async () => {
  const suiteUrl = new URL("../suites/smoke.json", import.meta.url)
  const suite = JSON.parse(await readFile(suiteUrl, "utf8"))

  assert.doesNotThrow(() => validateSuite(suite))
  assert.equal(suite.schemaVersion, "1.0")
  assert.equal(suite.cases.length, 9)
  assert.equal(Object.hasOwn(suite, "targetModule"), false)
  assert.equal(suite.cases.every((item) => Object.hasOwn(item, "targetModule") === false), true)
  assert.equal(suite.cases.find((item) => item.id === "missing-input").expectedTestOutcome, "ERROR")
  const passing = suite.cases.find((item) => item.id === "passing-ut")
  assert.equal(passing.requiredTools.includes("run_test"), true)
  const missingUt = suite.cases.find((item) => item.id === "missing-ut")
  assert.equal(missingUt.requiredTools.includes("run_test"), false)
  assert.equal(missingUt.requireEvidenceReferences, false)
  assert.equal(missingUt.maxTargetTestExecutions, 0)
  const loopGuard = suite.cases.find((item) => item.id === "algorithm-loop-guard")
  assert.equal(loopGuard.expectedTestOutcome, "ERROR")
  assert.equal(loopGuard.allowJdwp, true)
  assert.equal(loopGuard.maxTargetTestExecutions, 3)
  assert.equal(loopGuard.allowCodePath, true)
  assert.equal(loopGuard.allowJdwp, true)
  assert.equal(suite.cases.find((item) => item.id === "assertion-failure").expectedTestOutcome, "FAILED")
})

test("builds a real OpenCode run command from TargetModule and one user-style case question", () => {
  const args = buildOpenCodeArguments({
    targetModule: "D:/code/algorithm-module",
    question: "Why does this UT fail?",
    model: "provider/model",
  })

  assert.deepEqual(args, [
    "run",
    "--agent", "algorithm-debug",
    "--format", "json",
    "--dir", "D:/code/algorithm-module",
    "--model", "provider/model",
    "Why does this UT fail?",
  ])
})

test("rejects duplicate case ids and absolute module paths inside a suite", () => {
  const invalid = {
    schemaVersion: "1.0",
    suiteId: "invalid",
    description: "invalid",
    targetModule: "D:/machine-specific",
    cases: [
      { id: "same", question: "q", targetTest: { className: "a.B", methodName: "m" } },
      { id: "same", question: "q", targetTest: { className: "a.C", methodName: "m" } },
    ],
  }

  assert.throws(() => validateSuite(invalid), /targetModule is not allowed|duplicate case id/u)
})

test("executes Windows PowerShell and cmd shims without enabling shell interpolation", () => {
  assert.deepEqual(
    prepareSpawn("C:/Users/test/AppData/Roaming/npm/opencode.ps1", ["run", "question & evidence"]),
    {
      command: "powershell.exe",
      args: [
        "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-File", "C:/Users/test/AppData/Roaming/npm/opencode.ps1",
        "run", "question & evidence",
      ],
    },
  )
  assert.deepEqual(
    prepareSpawn("C:/tools/maven/bin/mvn.cmd", ["--version"]),
    {
      command: process.env.ComSpec ?? "cmd.exe",
      args: ["/d", "/s", "/c", "C:/tools/maven/bin/mvn.cmd", "--version"],
    },
  )
})

test("closes stdin for non-interactive child processes", async () => {
  const result = await runProcess(process.execPath, [
    "-e",
    "process.stdin.resume(); process.stdin.on('end', () => process.stdout.write('STDIN_CLOSED'))",
  ], { timeoutMillis: 200 })

  assert.equal(result.timedOut, false)
  assert.equal(result.exitCode, 0)
  assert.equal(result.stdout, "STDIN_CLOSED")
})
