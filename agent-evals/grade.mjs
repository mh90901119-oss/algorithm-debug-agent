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

  return {
    eventCount: events.length,
    toolCalls,
    analysisIdentity: successfulData(beginCalls.at(-1)),
    runOutcomes: callsNamed("run_test").map(successfulData).filter(Boolean),
    collections: toolCalls
      .filter((call) => call.name === "codepath_collect" || call.name === "jdwp_collect")
      .map(successfulData)
      .filter(Boolean),
    finalAnswer: textParts.at(-1) ?? "",
  }
}

function includesTool(toolNames, expected) {
  return toolNames.includes(expected)
}

function traceabilityIds(trace) {
  const values = new Set()
  const visit = value => {
    if (Array.isArray(value)) {
      value.forEach(visit)
      return
    }
    if (!value || typeof value !== "object") return
    for (const [key, item] of Object.entries(value)) {
      if (typeof item === "string"
          && /(?:artifact|evidence|collection|run)Id$/u.test(key)
          && key !== "collectorExecutionRunId") values.add(item)
      else visit(item)
    }
  }
  trace.toolCalls.forEach(call => visit(call.response?.data))
  return [...values]
}

function collectionEvidenceIds(call) {
  const values = new Set()
  const visit = (value, key = "") => {
    if (Array.isArray(value)) {
      value.forEach(item => visit(item, key))
      return
    }
    if (value && typeof value === "object") {
      Object.entries(value).forEach(([childKey, child]) => visit(child, childKey))
      return
    }
    if (typeof value !== "string") return
    if (key === "evidenceId" && value.startsWith("evidence-")) values.add(value)
    const pathMatch = value.match(/(?:^|\/)evidence\/(evidence-[^/]+)(?:\/|$)/u)
    if (pathMatch) values.add(pathMatch[1])
  }
  visit(call?.response?.data)
  return values
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

  const beginIndex = toolNames.indexOf("analysis_begin")
  const inputIndex = toolNames.indexOf("algorithm_input_capture")
  const runIndex = toolNames.indexOf("run_test")
  if (inputIndex >= 0 && beginIndex >= 0 && inputIndex <= beginIndex) {
    correctnessFailures.push("algorithm_input_capture was called before analysis_begin")
  }
  if (runIndex >= 0 && (inputIndex < 0 || inputIndex >= runIndex)) {
    correctnessFailures.push("run_test was called before a successful algorithm input capture attempt")
  }
  if (runIndex >= 0) {
    for (const evidenceTool of [
      "static_analyze", "codepath_plan_create", "codepath_collect",
      "jdwp_plan_create", "jdwp_collect",
    ]) {
      const evidenceIndex = toolNames.indexOf(evidenceTool)
      if (evidenceIndex >= 0 && evidenceIndex < runIndex) {
        correctnessFailures.push(`${evidenceTool} was called before run_test`)
      }
    }
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
    if (evalCase.requireAllCollectionsSuccessful
        && completions.some((completion) => completion !== evalCase.expectedCollectionCompletion)) {
      correctnessFailures.push(
        `Every collection must complete as ${evalCase.expectedCollectionCompletion}; observed ${completions.join(",") || "none"}`,
      )
    }
  }

  const planCalls = trace.toolCalls.filter((call) =>
    call.name === "codepath_plan_create" || call.name === "jdwp_plan_create")
  if (evalCase.requirePlanIntent) {
    for (const call of planCalls) {
      const input = call.input ?? {}
      for (const field of ["questionToAnswer", "hypothesis"]) {
        if (typeof input[field] !== "string" || input[field].trim() === "") {
          correctnessFailures.push(`${call.name} is missing structured intent field ${field}`)
        }
      }
      if (!Array.isArray(input.expectedObservations) || input.expectedObservations.length === 0) {
        correctnessFailures.push(`${call.name} is missing expectedObservations`)
      }
    }
    if (planCalls.length === 0) {
      correctnessFailures.push("Structured Plan intent was required but no Plan was created")
    }
    const minimumReferences = evalCase.minimumPlanEvidenceReferences ?? 0
    const hasIncrementalLineage = planCalls
      .filter((call) => call.response?.success === true)
      .some((call) => Array.isArray(call.input?.basedOnEvidenceIds)
        && call.input.basedOnEvidenceIds.length >= minimumReferences)
    if (minimumReferences > 0 && !hasIncrementalLineage) {
      evidenceFailures.push(
        `No successful incremental Plan references at least ${minimumReferences} prior Evidence IDs`,
      )
    }
  }

  if (evalCase.requireSequentialDynamicRefinement) {
    const codePathCollectIndex = trace.toolCalls.findIndex(call =>
      call.name === "codepath_collect" && call.response?.success === true)
    const jdwpPlanIndex = trace.toolCalls.findIndex(call =>
      call.name === "jdwp_plan_create" && call.response?.success === true)
    if (codePathCollectIndex < 0 || jdwpPlanIndex < 0 || jdwpPlanIndex <= codePathCollectIndex) {
      correctnessFailures.push("JDWP Plan must be created after successful CodePath Evidence")
    } else {
      const codePathEvidence = collectionEvidenceIds(trace.toolCalls[codePathCollectIndex])
      const lineage = trace.toolCalls[jdwpPlanIndex].input?.basedOnEvidenceIds
      if (!Array.isArray(lineage) || !lineage.some(id => codePathEvidence.has(id))) {
        evidenceFailures.push("JDWP Plan does not reference the preceding CodePath Evidence ID")
      }
    }
  }

  if (evalCase.requireJdwpCondition) {
    const jdwpPlans = trace.toolCalls.filter((call) => call.name === "jdwp_plan_create")
    const conditionalPoints = jdwpPlans.flatMap((call) =>
      Array.isArray(call.input?.tracepoints) ? call.input.tracepoints : [])
      .filter((point) => Array.isArray(point?.conditions) && point.conditions.length > 0
        && Number.isInteger(point.maxObservedHits)
        && Number.isInteger(point.maxCapturedHits))
    if (conditionalPoints.length === 0) {
      correctnessFailures.push("A bounded JDWP condition was required but none was planned")
    }
    for (const pattern of evalCase.requiredJdwpConditionValuePatterns ?? []) {
      const values = conditionalPoints.flatMap((point) =>
        point.conditions.map(condition => condition.expectedValue ?? ""))
      if (!values.some((value) => new RegExp(pattern, "iu").test(value))) {
        correctnessFailures.push(`No JDWP condition value matches required pattern: ${pattern}`)
      }
    }
  }

  if (toolNames.includes("analysis_complete")) {
    correctnessFailures.push("analysis_complete must not be called; return the answer directly")
  }

  if (evalCase.requireEvidenceReferences) {
    const ids = traceabilityIds(trace)
    if (ids.length === 0 || !ids.some(id => trace.finalAnswer.includes(id))) {
      evidenceFailures.push("Final answer does not cite an observed Run, Collection, Evidence, or Artifact ID")
    }
  }

  const answer = trace.finalAnswer
  if (evalCase.requireAnswerContext !== false) {
    const caseDirectory = trace.analysisIdentity?.caseDirectory
    const analysisDirectory = trace.analysisIdentity?.analysisDirectory
    if (typeof caseDirectory !== "string" || !answer.includes(caseDirectory)) {
      correctnessFailures.push("Final answer does not identify the current Case directory")
    }
    if (typeof analysisDirectory !== "string" || !answer.includes(analysisDirectory)) {
      correctnessFailures.push("Final answer does not identify the current Analysis directory")
    }
    if (!/(?:\b(?:(?:major|agent)\s+)?capabilities(?:\s+actually)?\s+used\b|使用的.*功能|主要功能)/iu.test(answer)) {
      correctnessFailures.push("Final answer does not summarize the Agent capabilities used")
    }
  }
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
  return {
    caseId: evalCase.id,
    passed: correctnessFailures.length === 0 && evidenceFailures.length === 0,
    correctnessFailures,
    evidenceFailures,
    efficiencyWarnings,
    observedToolSequence: toolNames,
  }
}
