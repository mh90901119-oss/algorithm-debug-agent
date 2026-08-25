const CONFIRMED_CLASSIFICATIONS = new Set([
  "CONFIRMED_FACT",
  "VALIDATOR_CONCLUSION",
  "SOURCE_INFERENCE",
])

export function normalizeToolName(name) {
  if (typeof name !== "string") {
    return ""
  }
  return name.startsWith("algorithm-debug_")
    ? name.slice("algorithm-debug_".length)
    : name
}

function parseToolResponse(output) {
  if (output && typeof output === "object") {
    return output
  }
  if (typeof output !== "string" || output.trim() === "") {
    return null
  }
  try {
    return JSON.parse(output)
  } catch {
    return null
  }
}

export function parseOpenCodeJsonl(raw) {
  if (typeof raw !== "string") {
    throw new TypeError("OpenCode JSONL must be a string")
  }

  const events = []
  for (const [index, sourceLine] of raw.split(/\r?\n/u).entries()) {
    const line = sourceLine.trim()
    if (line === "") {
      continue
    }
    try {
      events.push(JSON.parse(line))
    } catch (failure) {
      throw new SyntaxError(`Invalid OpenCode JSONL at line ${index + 1}: ${failure.message}`)
    }
  }

  const toolCalls = []
  const toolCallIndexes = new Map()
  const textParts = []
  for (const event of events) {
    const part = event?.part
    if (part?.type === "tool") {
      const state = part.state ?? {}
      const call = {
        name: normalizeToolName(part.tool),
        rawName: part.tool ?? "",
        executionStatus: state.status ?? "unknown",
        input: state.input ?? {},
        output: state.output ?? "",
        response: parseToolResponse(state.output),
      }
      const callId = typeof part.callID === "string" && part.callID.trim() !== ""
        ? part.callID : null
      if (callId && toolCallIndexes.has(callId)) {
        toolCalls[toolCallIndexes.get(callId)] = call
      } else {
        if (callId) toolCallIndexes.set(callId, toolCalls.length)
        toolCalls.push(call)
      }
    } else if (part?.type === "text" && typeof part.text === "string" && part.text.trim() !== "") {
      textParts.push(part.text)
    }
  }

  const callsNamed = (name) => toolCalls.filter((call) => call.name === name)
  const successfulData = (call) => call?.response?.success === true ? call.response.data : null
  const beginCalls = callsNamed("analysis_begin")
  const completionCalls = callsNamed("analysis_complete")

  return {
    eventCount: events.length,
    toolCalls,
    analysisIdentity: successfulData(beginCalls.at(-1)),
    runOutcomes: callsNamed("run_test").map(successfulData).filter(Boolean),
    collections: toolCalls
      .filter((call) => call.name === "codepath_collect" || call.name === "jdwp_collect")
      .map(successfulData)
      .filter(Boolean),
    analysisCompletion: completionCalls.at(-1) ?? null,
    finalAnswer: textParts.at(-1) ?? "",
  }
}

function includesTool(toolNames, expected) {
  return toolNames.includes(expected)
}

function assertionReferences(completion) {
  const conclusions = Array.isArray(completion?.input?.conclusions)
    ? completion.input.conclusions
    : []
  return { conclusions, confirmed: conclusions.filter((item) => CONFIRMED_CLASSIFICATIONS.has(item?.classification)) }
}

export function gradeCase(evalCase, trace, runtime) {
  const correctnessFailures = []
  const evidenceFailures = []
  const efficiencyWarnings = []
  const toolNames = trace.toolCalls.map((call) => call.name)

  if (runtime.openCodeExitCode !== 0) {
    correctnessFailures.push(`OpenCode exited with code ${runtime.openCodeExitCode}`)
  }
  if (runtime.parseError) {
    correctnessFailures.push(`OpenCode JSONL could not be parsed: ${runtime.parseError}`)
  }
  if (runtime.sourceModified) {
    correctnessFailures.push("TargetModule protected source files were modified")
  }

  for (const required of evalCase.requiredTools ?? []) {
    if (!includesTool(toolNames, required)) {
      correctnessFailures.push(`Required tool was not called: ${required}`)
    }
  }
  for (const forbidden of evalCase.forbiddenTools ?? []) {
    if (includesTool(toolNames, forbidden)) {
      correctnessFailures.push(`Forbidden tool was called: ${forbidden}`)
    }
  }
  if (evalCase.allowCodePath === false && toolNames.some((name) => name.startsWith("codepath_"))) {
    correctnessFailures.push("CodePath was used although this Eval Case does not allow it")
  }
  if (evalCase.allowJdwp === false && toolNames.some((name) => name.startsWith("jdwp_"))) {
    correctnessFailures.push("JDWP was used although this Eval Case does not allow it")
  }

  const runOutcome = trace.runOutcomes[0]
  if ((evalCase.expectedProcessOutcome || evalCase.expectedTestOutcome
      || evalCase.expectedGanttOutcome || evalCase.expectedExceptionClass) && !runOutcome) {
    correctnessFailures.push("No successful run_test outcome was returned")
  } else if (runOutcome) {
    if (evalCase.expectedProcessOutcome && runOutcome.processOutcome !== evalCase.expectedProcessOutcome) {
      correctnessFailures.push(`Expected processOutcome ${evalCase.expectedProcessOutcome}, observed ${runOutcome.processOutcome}`)
    }
    if (evalCase.expectedTestOutcome && runOutcome.testOutcome !== evalCase.expectedTestOutcome) {
      correctnessFailures.push(`Expected testOutcome ${evalCase.expectedTestOutcome}, observed ${runOutcome.testOutcome}`)
    }
    if (evalCase.expectedGanttOutcome && runOutcome.ganttOutcome !== evalCase.expectedGanttOutcome) {
      correctnessFailures.push(`Expected ganttOutcome ${evalCase.expectedGanttOutcome}, observed ${runOutcome.ganttOutcome}`)
    }
    if (evalCase.expectedExceptionClass
        && runOutcome.targetFailure?.exceptionClass !== evalCase.expectedExceptionClass) {
      correctnessFailures.push(
        `Expected exceptionClass ${evalCase.expectedExceptionClass}, observed ${runOutcome.targetFailure?.exceptionClass ?? "none"}`,
      )
    }
  }

  if (evalCase.expectedCollectionCompletion) {
    const completions = trace.collections.map((collection) => collection.completion)
    if (!completions.includes(evalCase.expectedCollectionCompletion)) {
      correctnessFailures.push(
        `Expected collection completion ${evalCase.expectedCollectionCompletion}, observed ${completions.join(",") || "none"}`,
      )
    }
  }

  const completion = trace.analysisCompletion
  if (evalCase.requireAnalysisComplete !== false) {
    if (!completion) {
      correctnessFailures.push("analysis_complete was not called")
    } else if (completion.executionStatus !== "completed" || completion.response?.success !== true) {
      correctnessFailures.push("analysis_complete did not complete successfully")
    }
  }

  if (evalCase.requireEvidenceReferences) {
    const { conclusions, confirmed } = assertionReferences(completion)
    if (conclusions.length === 0 || confirmed.length === 0) {
      evidenceFailures.push("analysis_complete did not contain an evidence-backed confirmed conclusion")
    }
    for (const [index, conclusion] of confirmed.entries()) {
      if (!Array.isArray(conclusion.evidenceReferenceIds) || conclusion.evidenceReferenceIds.length === 0) {
        evidenceFailures.push(`Confirmed conclusion ${index} has no evidence reference`)
      }
    }
  }

  const answer = trace.finalAnswer
  for (const pattern of evalCase.requiredAnswerPatterns ?? []) {
    if (!new RegExp(pattern, "iu").test(answer)) {
      correctnessFailures.push(`Final answer does not match required pattern: ${pattern}`)
    }
  }
  for (const pattern of evalCase.forbiddenAnswerPatterns ?? []) {
    if (new RegExp(pattern, "iu").test(answer)) {
      correctnessFailures.push(`Final answer matches forbidden pattern: ${pattern}`)
    }
  }

  const targetExecutions = toolNames.filter((name) => ["run_test", "codepath_collect", "jdwp_collect"].includes(name)).length
  if (Number.isInteger(evalCase.maxTargetTestExecutions)
      && targetExecutions > evalCase.maxTargetTestExecutions) {
    efficiencyWarnings.push(
      `Target UT was executed ${targetExecutions} times; budget is ${evalCase.maxTargetTestExecutions}`,
    )
  }
  const beginCount = toolNames.filter((name) => name === "analysis_begin").length
  if (beginCount > 1) {
    efficiencyWarnings.push(`analysis_begin was called ${beginCount} times`)
  }
  const completionIndex = toolNames.lastIndexOf("analysis_complete")
  if (completionIndex >= 0 && completionIndex < toolNames.length - 1) {
    efficiencyWarnings.push("Tools were called after analysis_complete")
  }

  return {
    caseId: evalCase.id,
    passed: correctnessFailures.length === 0 && evidenceFailures.length === 0,
    correctnessFailures,
    evidenceFailures,
    efficiencyWarnings,
    observedToolSequence: toolNames,
  }
}
