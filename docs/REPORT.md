# Laboratory 2 Report

> This report summarizes the concurrency diagnosis and fixes applied to the
> Autonomous Warehouse simulation. The fully detailed, question-by-question
> version (matching the course's submission template, including the raw
> command output used as evidence) lives in
> `ARSW_2026-2_Lab2_Plantilla_Entrega_Estudiantes.md` at the repository root.
> The architectural decision is recorded separately in
> `docs/ADR-001-concurrency-control.md`.

## 1. Shared-state inventory

| Shared object | Mutable state | Readers | Writers | Possible invariant |
|---|---|---|---|---|
| `PackageQueue` | `pending` (list/queue of parcels) | all `WarehouseRobot`s | all `WarehouseRobot`s (`takeNext()`) | a parcel is handed out to exactly one robot |
| `DeliveryRegistry` | `nextPosition`, `deliveries` | `WarehouseMain`, `RaceConditionProbe` | all `WarehouseRobot`s (`register()`) | arrival positions are unique and contiguous (1..N) |
| `WarehouseStatistics` | `processedParcels`, `totalProcessingMillis` | `WarehouseMain`, `RaceConditionProbe` | all `WarehouseRobot`s (`recordProcessed()`) | processed count matches the number of delivery records |
| `SimulationControl` | `paused` | all `WarehouseRobot`s | main/demo thread (`pause()`/`resume()`) | no robot busy-waits; all paused robots wake up on `resume()` |

## 2. Observed anomalies

Three reproducible race conditions were found in the starter code (100% of
probe runs anomalous before the fix):

1. **`PackageQueue.takeNext()`** — check-then-act (`isEmpty()`→`get(0)`→
   `remove(0)`) let two robots take the same parcel, or threw
   `IndexOutOfBoundsException` under concurrent modification of the backing
   `ArrayList`.
2. **`DeliveryRegistry.register()`** — non-atomic read-modify-write of
   `nextPosition` produced duplicated or skipped arrival positions.
3. **`WarehouseStatistics.recordProcessed()`** — non-atomic `++`/`+=`
   produced lost updates (`processedParcels` drifting from the true count).
4. **`WarehouseMain.main()`** — used `Thread.sleep(60)` instead of waiting
   for completion, printing a "final" report while most robots (up to ~80%
   of the parcels in a 12-robot/100-parcel run) were still working.

Full command lines and raw console output for each anomaly are in section 3
of `ARSW_2026-2_Lab2_Plantilla_Entrega_Estudiantes.md`.

## 3. Interleaving analysis

A step-by-step interleaving for the `DeliveryRegistry.register()` race
(two robots reading the same `nextPosition` before either writes it back,
producing a duplicated position and a skipped one) is documented in section 4
of the template file.

## 4. System invariants

- **I1**: every parcel is taken and processed at most once.
- **I2**: no parcel disappears: `pendingParcels + deliveries.size() ==
  initialParcels` at all times.
- **I3**: arrival positions in `DeliveryRegistry` are unique and form a
  contiguous `1..N` sequence once the simulation finishes.
- **I4**: `processedParcels() == deliveries.size()` once all robots have been
  `join()`-ed.

## 5. Critical regions and synchronization decisions

| Class | Critical region | Mechanism | Why |
|---|---|---|---|
| `PackageQueue` | `takeNext()` (delegated to `poll()`) | `ConcurrentLinkedQueue` (lock-free) | `poll()` is already atomic; no application-level lock needed |
| `DeliveryRegistry` | whole `register()` and `snapshot()` | `synchronized` | position assignment + list insertion must be one atomic step (I3) |
| `WarehouseStatistics` | each individual increment | `AtomicInteger`/`AtomicLong` | the two counters are independent of each other (I4), no joint atomicity required |
| `SimulationControl` | `pause()`/`resume()`/`awaitIfPaused()`/`isPaused()` | `synchronized` + `wait()`/`notifyAll()` | coordinates multiple threads (not just protects one), which requires the monitor pattern |

No single global lock was used, and not every public method was blindly
synchronized — each mechanism was chosen based on whether its invariant
required combining several steps atomically or not. See ADR-001 and section 7
of the template for the full alternatives analysis.

## 6. Thread completion and pause/resume coordination

- **Completion**: `WarehouseMain` now calls `simulation.awaitCompletion()`
  (which loops `robot.join()` over every `WarehouseRobot`) instead of
  `Thread.sleep(60)`, guaranteeing the final report is printed exactly once,
  after every robot has truly terminated.
- **Pause/Resume**: `SimulationControl` replaced the busy-waiting
  `while (paused) Thread.onSpinWait();` loop with a Java monitor:
  `awaitIfPaused()` does `while (paused) wait();` inside a `synchronized`
  method, and `resume()` does `paused = false; notifyAll();` inside a
  `synchronized` method — waking every parked robot with a single
  coordinated action, with zero CPU spent while paused.

## 7. Verification results

`RaceConditionProbe`, 100 runs per configuration:

| Robots | Parcels | Anomalies before | Anomalies after |
|---:|---:|---:|---:|
| 8 | 100 | 20/20 | 0/100 |
| 16 | 250 | 5/5 (incl. 1 `IndexOutOfBoundsException`) | 0/100 |
| 32 | 500 | 5/5 (incl. 1 `IndexOutOfBoundsException`) | 0/100 |

`mvn clean test`: 2/2 tests passing. `PauseResumeDemo`: paused snapshot shows
66 pending + 114 processed = 180 initial parcels (consistent); final snapshot
shows 180/180 processed, 0 pending.

## 8. Quality-attribute analysis

- **Correctness/Reliability**: race conditions eliminated (0/100 anomalies
  across three load configurations, vs. 100% before).
- **Performance/Throughput**: minimal impact — critical regions kept as small
  as possible, lock-free structures used where the invariant allowed it (100
  runs of 32 robots/500 parcels completed in ~47s total).
- **Maintainability**: every fixed class documents, in its Javadoc, which
  invariant it protects and why that specific mechanism was chosen.
- **Scalability**: no single global contention point; robots only contend on
  `DeliveryRegistry`'s small critical section or on `SimulationControl`
  during pause/resume.

See section 14 of the template file for the architectural discussion of what
changes when the warehouse runs as three separate JVM instances behind a load
balancer (in short: `synchronized` gives no cross-process guarantee; database
transactions/constraints are proposed instead).
