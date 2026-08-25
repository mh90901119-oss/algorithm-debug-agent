# Wafer Demo Planning Reference v1

## Status and trust boundary

- Kind: `PLANNING_HINT`
- Adapter: `wafer-demo` version `0.2.0`
- Target UT: `org.example.scheduler.wafer.WaferSchedulingReproductionTest#reproduceComplexSchedulingFromTimestampedInput`
- Source basis: Demo specification, fixed UT, fixed input, and current scheduler source reviewed on 2026-08-20.
- This file is not runtime evidence. Never cite it as `CONFIRMED_FACT` or use its method names without
  resolving current Method Catalog keys and source anchors.

## Fixed scenario hints

- The UT reads `input/cases/20260810101501.json` and writes timestamped JSON under
  `output/algorithm-results`.
- The input declares snapshot `COMPLEX-PARALLEL-001`, mode `PARALLEL`, three Jobs, five wafers per
  Job, three Sequence recipe steps per Job, five Chambers, one Robot, and no running Jobs.
- A current-source planning derivation is 11 operations per wafer: three groups of
  `PICK + PLACE + RECIPE`, then `PICK + PLACE` to return home. Fifteen wafers therefore suggest 165
  operations. Confirm this with the archived Run/Gantt and current source before presenting it as fact.

## Domain invariants to test

- `PARALLEL` allows different Jobs to alternate on a Chamber; it does not allow concurrent Chamber
  occupancy.
- Robot, source/destination and Chamber resource availability constrain operation start times.
- Each wafer follows its Sequence order and returns to its source Load Port.
- Within one Job, stable wafer ordering prevents overtaking; different Jobs may interleave.
- Chamber occupancy must transition on PICK/PLACE and match the wafer executing RECIPE.

## Static and CodePath planning hints

Run `static_analyze` first. Resolve only the current catalog entries needed for the question, normally
starting from these candidates:

1. `WaferSchedulingReproductionTest#reproduceComplexSchedulingFromTimestampedInput`
2. `WaferSchedulingInputReader#read`
3. `SimpleWaferScheduler#schedule`
4. `SimpleWaferScheduler#waferComparator`
5. `SimpleWaferScheduler#buildPlan`
6. `SimpleWaferScheduler#scheduleWafer`
7. `WaferScheduleJsonWriter#writeTimestamped`

For an end-to-end path question, prefer the test entry, reader, `schedule`, `buildPlan`,
`scheduleWafer`, and writer. Do not collect every helper unless the first collection leaves a concrete
gap.

## JDWP planning hints

## Current catalog snapshot for planning

The following planning snapshot was generated from the Demo Method Catalog on 2026-08-20. Every
entry belongs to `SimpleWaferScheduler.java` source SHA
`1dfc6760b7fd438ab7670f26108aff2d88a33d7eb153eca98d4bf9aad761e9b1`. Use an entry only after the
current `static_analyze` result resolves the same method key and source SHA. A mismatch means the
snapshot is stale and must not be used.

- `org.example.scheduler.wafer.SimpleWaferScheduler#schedule(Lorg/example/scheduler/wafer/WaferSchedulingInput;)Lorg/example/scheduler/wafer/WaferScheduleResult;`, lines 17-67
- `org.example.scheduler.wafer.SimpleWaferScheduler#scheduleWafer(Lorg/example/scheduler/wafer/SimpleWaferScheduler$WaferContext;Lorg/example/scheduler/wafer/WaferSchedulingInput;Ljava/util/Map;Ljava/util/Map;Ljava/util/Map;Ljava/util/Map;Ljava/util/Set;Ljava/util/List;Ljava/util/Set;Ljava/util/Map;)V`, lines 69-150
- `org.example.scheduler.wafer.SimpleWaferScheduler#buildPlan(Lorg/example/scheduler/wafer/SimpleWaferScheduler$WaferContext;Lorg/example/scheduler/wafer/EquipmentSnapshot;)Ljava/util/List;`, lines 152-179
- `org.example.scheduler.wafer.SimpleWaferScheduler#requiredResources(Lorg/example/scheduler/wafer/SimpleWaferScheduler$PlannedOperation;Lorg/example/scheduler/wafer/EquipmentSnapshot;)Ljava/util/List;`, lines 244-253

For a first CodePath collection, these four entries are sufficient to test the path from scheduling
entry through one wafer decision to resource selection. For a first JDWP collection, line 115 in
`scheduleWafer` is a conservative candidate immediately before resource availability affects the
operation time. The line remains a planning hint; the JDWP Plan Compiler must resolve and validate it.

Use CodePath and source anchors before JDWP. Prefer one or two tracepoints with `maxHits=1`:

- `schedule`: confirm input mode and bounded collection sizes such as Jobs, wafer contexts and running Jobs.
- `scheduleWafer`: inspect one representative first hit for `plan`, `readyAt`, `location`, operation list
  growth, resource availability and selected operation timing.

Suggested capture ceiling for the first attempt:

```json
{
  "locals": true,
  "stack": true,
  "maxFrames": 8,
  "maxDepth": 1,
  "maxItems": 20,
  "maxStringLength": 256
}
```

Use a small collection budget such as 10 events, 4 MiB, 120 seconds total and 30 seconds idle. Select
the exact line from current source anchors. If local-variable debug metadata is unavailable, retain the
stack evidence and report the missing state rather than widening collection blindly.

## Evidence questions

- Did the baseline Run archive mode `PARALLEL`, 15 final wafer locations and 165 operations?
- Does CodePath show the expected reader → scheduler → per-wafer plan → writer path?
- Does the representative JDWP hit show a bounded plan and resource timing consistent with the source?
- Did every dynamic collection preserve the same normalized Gantt fingerprint?
