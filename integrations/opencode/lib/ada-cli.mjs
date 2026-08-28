const MAXIMUM_STREAM_BYTES = 1024 * 1024
const DEFAULT_TIMEOUT_MILLISECONDS = 15 * 60 * 1000
const UTF8 = new TextDecoder("utf-8", { fatal: true })

/**
 * 有界执行 Algorithm Debug CLI，仅原样返回通过 ToolResponse 2.0 校验的 stdout。
 * @param {string[]} args CLI 参数
 * @param {string} cwd 目标项目目录
 * @param {(command: string[], options: object) => object} spawn 进程启动函数
 * @param {{ timeoutMilliseconds?: number, executable?: string, environment?: Record<string, string> }} [options] Adapter 总运行预算、CLI 启动器与内部子进程环境
 * @returns {Promise<string>} ToolResponse 2.0 JSON
 */
export async function runAdaCommand(args, cwd, spawn, options = {}) {
  if (!Array.isArray(args) || typeof cwd !== "string" || cwd.length === 0
      || typeof spawn !== "function") {
    throw new TypeError("args, cwd, and spawn must be valid")
  }
  const timeoutMilliseconds = options.timeoutMilliseconds ?? DEFAULT_TIMEOUT_MILLISECONDS
  if (!Number.isSafeInteger(timeoutMilliseconds) || timeoutMilliseconds <= 0) {
    throw new TypeError("timeoutMilliseconds must be a positive integer")
  }
  const executable = options.executable ?? "ada"
  if (typeof executable !== "string" || executable.trim().length === 0) {
    throw new TypeError("executable must be a non-empty string")
  }
  const environment = options.environment ?? {}
  if (typeof environment !== "object" || environment === null || Array.isArray(environment)
      || Object.entries(environment).some(([key, value]) =>
        typeof key !== "string" || key.length === 0 || typeof value !== "string")) {
    throw new TypeError("environment must contain string keys and values")
  }

  let process
  try {
    process = spawn([executable, ...args], {
      cwd,
      stdout: "pipe",
      stderr: "pipe",
      env: { ...globalThis.process.env, ...environment },
    })
  } catch {
    return failure("ADA_CLI_START_FAILED")
  }

  try {
    const [stdout, stderr] = await withTimeout(Promise.all([
        readBounded(process.stdout),
        readBounded(process.stderr),
        process.exited,
      ]), timeoutMilliseconds)
    void stderr
    const response = UTF8.decode(stdout).trim()
    if (!isToolResponse(response)) return failure("ADA_CLI_INVALID_RESPONSE")
    return response
  } catch (error) {
    try {
      process.kill?.()
    } catch {
      // 终止失败不改变有界 Adapter 响应；底层运行器负责最终进程树清理。
    }
    if (error instanceof StreamLimitError) return failure("ADA_CLI_OUTPUT_LIMIT_EXCEEDED")
    if (error instanceof AdapterTimeoutError) return failure("ADA_CLI_TIMEOUT")
    return failure("ADA_CLI_INVALID_RESPONSE")
  }
}

function withTimeout(promise, timeoutMilliseconds) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new AdapterTimeoutError()), timeoutMilliseconds)
    promise.then(
      value => {
        clearTimeout(timer)
        resolve(value)
      },
      error => {
        clearTimeout(timer)
        reject(error)
      },
    )
  })
}

async function readBounded(stream) {
  if (!stream || typeof stream.getReader !== "function") {
    throw new TypeError("CLI stream is not readable")
  }
  const reader = stream.getReader()
  const chunks = []
  let total = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const chunk = value instanceof Uint8Array ? value : new Uint8Array(value)
      total += chunk.byteLength
      if (total > MAXIMUM_STREAM_BYTES) throw new StreamLimitError()
      chunks.push(chunk)
    }
  } finally {
    reader.releaseLock()
  }
  const bytes = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    bytes.set(chunk, offset)
    offset += chunk.byteLength
  }
  return bytes
}

function isToolResponse(text) {
  let value
  try {
    value = JSON.parse(text)
  } catch {
    return false
  }
  if (!isObject(value) || value.schemaVersion !== "2.0"
      || typeof value.success !== "boolean" || !nonBlank(value.code)
      || !nonBlank(value.message) || !Array.isArray(value.artifacts)
      || !hasExactKeys(value, [
        "schemaVersion", "success", "code", "message", "data", "artifacts",
      ])) {
    return false
  }
  if (value.success ? value.data === null || value.data === undefined : value.data !== null) {
    return false
  }
  return value.artifacts.every(isArtifactReference)
}

function isArtifactReference(value) {
  return isObject(value)
    && hasExactKeys(value, [
      "artifactId", "artifactType", "relativePath", "mediaType", "sha256", "sizeBytes",
    ])
    && boundedNonBlank(value.artifactId, 128)
    && nonBlank(value.artifactType)
    && portableRelativePath(value.relativePath)
    && nonBlank(value.mediaType)
    && typeof value.sha256 === "string"
    && /^[0-9a-fA-F]{64}$/.test(value.sha256)
    && Number.isSafeInteger(value.sizeBytes)
    && value.sizeBytes >= 0
}

function portableRelativePath(value) {
  if (!nonBlank(value) || value.includes("\\") || value.startsWith("/")
      || value.includes(":")) return false
  return value.split("/").every(segment => segment !== "" && segment !== "." && segment !== "..")
}

function boundedNonBlank(value, maximumLength) {
  return nonBlank(value) && value.length <= maximumLength
    && !Array.from(value).some(character => /[\u0000-\u001f\u007f-\u009f]/u.test(character))
}

function nonBlank(value) {
  return typeof value === "string" && value.trim().length > 0
}

function isObject(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function hasExactKeys(value, expected) {
  const actual = Object.keys(value).sort()
  const wanted = [...expected].sort()
  return actual.length === wanted.length && actual.every((key, index) => key === wanted[index])
}

function failure(code) {
  return JSON.stringify({
    schemaVersion: "2.0",
    success: false,
    code,
    message: "Algorithm Debug CLI adapter failed; inspect local Agent logs for details",
    data: null,
    artifacts: [],
  })
}

class StreamLimitError extends Error {}
class AdapterTimeoutError extends Error {}
