import test from "node:test"
import assert from "node:assert/strict"

import { runAdaCommand } from "../lib/ada-cli.mjs"

const validSuccess = JSON.stringify({
  schemaVersion: "2.0",
  success: true,
  code: "OK",
  message: "Success",
  data: { eventType: "TARGET_TEST_RUN_COMPLETED" },
  artifacts: [],
})

test("returns a valid ToolResponse without rewriting facts", async () => {
  const result = await runAdaCommand(
    ["run", "test"],
    "D:/target",
    fakeSpawn(validSuccess, "diagnostic log", 0),
  )

  assert.equal(result, validSuccess)
})

test("uses an explicitly configured repository launcher", async () => {
  let observed
  const spawn = (command, options) => {
    observed = { command, options }
    return fakeProcess(validSuccess, "", 0)
  }

  await runAdaCommand(["case", "inspect"], "D:/target", spawn, {
    executable: "D:/agent/bin/ada.cmd",
  })

  assert.deepEqual(observed.command, ["D:/agent/bin/ada.cmd", "case", "inspect"])
  assert.equal(observed.options.cwd, "D:/target")
})

test("passes the resolved DFX directory through the internal child environment", async () => {
  let observed
  const spawn = (command, options) => {
    observed = { command, options }
    return fakeProcess(validSuccess, "", 0)
  }

  await runAdaCommand(["case", "inspect"], "D:/target", spawn, {
    environment: { ADA_DFX_DIRECTORY: "D:/diagnostics" },
  })

  assert.equal(observed.options.env.ADA_DFX_DIRECTORY, "D:/diagnostics")
})

test("accepts artifact hash case normalized by the Java contract", async () => {
  const response = JSON.stringify({
    schemaVersion: "2.0",
    success: true,
    code: "OK",
    message: "Success",
    data: {},
    artifacts: [{
      artifactId: "artifact-1",
      artifactType: "GANTT",
      relativePath: "result/gantt.json",
      mediaType: "application/json",
      sha256: "A".repeat(64),
      sizeBytes: 1,
    }],
  })

  const result = await runAdaCommand([], "D:/target", fakeSpawn(response, "", 0))

  assert.equal(result, response)
})

test("returns a structured response when the CLI cannot start", async () => {
  const result = await runAdaCommand([], "D:/target", () => {
    throw new Error("spawn failed at C:/secret/path")
  })

  assert.deepEqual(JSON.parse(result), failure("ADA_CLI_START_FAILED"))
  assert.doesNotMatch(result, /secret/)
})

test("terminates the CLI when stream reading fails", async () => {
  let killed = false
  const brokenStream = new ReadableStream({
    start(controller) {
      controller.error(new Error("read failed"))
    },
  })
  const spawn = () => ({
    stdout: brokenStream,
    stderr: stream(""),
    exited: new Promise(() => {}),
    kill: () => { killed = true },
  })

  const result = await runAdaCommand([], "D:/target", spawn)

  assert.deepEqual(JSON.parse(result), failure("ADA_CLI_INVALID_RESPONSE"))
  assert.equal(killed, true)
})

test("terminates the CLI when the adapter runtime budget expires", async () => {
  let killed = false
  const spawn = () => ({
    stdout: new ReadableStream({}),
    stderr: new ReadableStream({}),
    exited: new Promise(() => {}),
    kill: () => { killed = true },
  })

  const result = await runAdaCommand([], "D:/target", spawn, { timeoutMilliseconds: 1 })

  assert.deepEqual(JSON.parse(result), failure("ADA_CLI_TIMEOUT"))
  assert.equal(killed, true)
})

test("rejects output above the byte budget without echoing it", async () => {
  const oversized = "x".repeat(1_048_577)
  let killed = false

  const result = await runAdaCommand([], "D:/target", fakeSpawn(
    oversized, "", 1, () => { killed = true },
  ))

  assert.deepEqual(JSON.parse(result), failure("ADA_CLI_OUTPUT_LIMIT_EXCEEDED"))
  assert.doesNotMatch(result, /xxx/)
  assert.equal(killed, true)
})

test("rejects malformed or incompatible ToolResponse", async () => {
  const malformed = await runAdaCommand([], "D:/target", fakeSpawn("not-json", "", 0))
  const wrongVersion = await runAdaCommand([], "D:/target", fakeSpawn(JSON.stringify({
    schemaVersion: "1.0",
    success: false,
    code: "FAILED",
    message: "failed",
    data: null,
    artifacts: [],
  }), "", 1))

  assert.deepEqual(JSON.parse(malformed), failure("ADA_CLI_INVALID_RESPONSE"))
  assert.deepEqual(JSON.parse(wrongVersion), failure("ADA_CLI_INVALID_RESPONSE"))
})

test("rejects invalid artifact references", async () => {
  const response = JSON.stringify({
    schemaVersion: "2.0",
    success: true,
    code: "OK",
    message: "Success",
    data: {},
    artifacts: [{
      artifactId: "artifact-1",
      artifactType: "GANTT",
      relativePath: "../secret.json",
      mediaType: "application/json",
      sha256: "0".repeat(64),
      sizeBytes: 1,
    }],
  })

  const result = await runAdaCommand([], "D:/target", fakeSpawn(response, "", 0))

  assert.deepEqual(JSON.parse(result), failure("ADA_CLI_INVALID_RESPONSE"))
})

test("enforces the same artifact id and portable path bounds as Java contracts", async () => {
  for (const artifact of [
    validArtifact({ artifactId: "a".repeat(129) }),
    validArtifact({ relativePath: "result/data:part.json" }),
  ]) {
    const response = JSON.stringify({
      schemaVersion: "2.0",
      success: true,
      code: "OK",
      message: "Success",
      data: {},
      artifacts: [artifact],
    })

    const result = await runAdaCommand([], "D:/target", fakeSpawn(response, "", 0))

    assert.deepEqual(JSON.parse(result), failure("ADA_CLI_INVALID_RESPONSE"))
  }
})

test("rejects undeclared response fields", async () => {
  const response = JSON.stringify({
    ...JSON.parse(validSuccess),
    stderr: "must not cross the adapter boundary",
  })

  const result = await runAdaCommand([], "D:/target", fakeSpawn(response, "", 0))

  assert.deepEqual(JSON.parse(result), failure("ADA_CLI_INVALID_RESPONSE"))
})

test("returns a valid CLI failure even when the process exits nonzero", async () => {
  const cliFailure = JSON.stringify(failure("TARGET_TEST_FAILED"))

  const result = await runAdaCommand([], "D:/target", fakeSpawn(cliFailure, "details", 1))

  assert.equal(result, cliFailure)
})

function fakeSpawn(stdout, stderr, exitCode, kill = () => {}) {
  return () => fakeProcess(stdout, stderr, exitCode, kill)
}

function fakeProcess(stdout, stderr, exitCode, kill = () => {}) {
  return {
    stdout: stream(stdout),
    stderr: stream(stderr),
    exited: Promise.resolve(exitCode),
    kill,
  }
}

function stream(value) {
  return new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(value))
      controller.close()
    },
  })
}

function validArtifact(overrides = {}) {
  return {
    artifactId: "artifact-1",
    artifactType: "GANTT",
    relativePath: "result/gantt.json",
    mediaType: "application/json",
    sha256: "0".repeat(64),
    sizeBytes: 1,
    ...overrides,
  }
}

function failure(code) {
  return {
    schemaVersion: "2.0",
    success: false,
    code,
    message: cliFailureMessage(code),
    data: null,
    artifacts: [],
  }
}

function cliFailureMessage(code) {
  const messages = {
    ADA_CLI_START_FAILED: "The Agent CLI could not start. Rebuild or reinstall the Agent and run installer Check. This tool result is not target-test evidence.",
    ADA_CLI_TIMEOUT: "The Agent CLI timed out. Report the Agent failure before deciding whether a new bounded execution is required. This tool result is not target-test evidence.",
    ADA_CLI_OUTPUT_LIMIT_EXCEEDED: "The Agent CLI response exceeded its byte limit. Narrow the requested read or query. This tool result is not target-test evidence.",
    ADA_CLI_INVALID_RESPONSE: "The Agent CLI did not return a valid ToolResponse. Report the Agent failure and inspect local DFX logs. This tool result is not target-test evidence.",
  }
  return messages[code]
    ?? `${String(code).toLowerCase().replaceAll("_", " ")}. This tool result is not target-test evidence.`
}
