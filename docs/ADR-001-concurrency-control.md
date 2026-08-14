# ADR-001: Concurrency control for warehouse shared state

## Context

`WarehouseRobot` models each autonomous robot as a platform thread (`Thread`) that
competes with other robots for four shared objects: `PackageQueue`,
`DeliveryRegistry`, `WarehouseStatistics` and `SimulationControl`. The initial
version of the project was intentionally unsafe: it used non-atomic
"check-then-act" and "read-modify-write" sequences, and a busy-waiting loop
(`Thread.onSpinWait()`) for pause/resume. This produced reproducible race
conditions (duplicated or lost parcels, duplicated or non-contiguous arrival
positions, inconsistent counters, and even `IndexOutOfBoundsException`), plus a
final report printed before the robots had actually finished working.

## Decision

Class-specific synchronization was applied, sized to the minimum region needed
to protect each identified invariant (I1–I4), instead of one global lock or
blindly marking every public method `synchronized`:

- `PackageQueue`: backed by `ConcurrentLinkedQueue` (lock-free) instead of an
  `ArrayList` with a check-then-act sequence.
- `DeliveryRegistry`: `register()`/`snapshot()` declared `synchronized`,
  because the invariant requires position assignment and record insertion to
  happen as one atomic step.
- `WarehouseStatistics`: `AtomicInteger`/`AtomicLong` instead of `int`/`long`
  updated with `++`/`+=`, because its two counters are independent of each
  other.
- `SimulationControl`: the standard Java monitor pattern (`synchronized` +
  `wait()`/`notifyAll()`) instead of busy waiting.
- `WarehouseMain`: completion coordinated with `Thread.join()` (through
  `WarehouseSimulation.awaitCompletion()`) instead of `Thread.sleep(60)`.

## Alternatives considered

1. A single global lock for all shared state (one `Object` shared by the four
   classes, or `synchronized` on every public method indiscriminately).
   Rejected: it would needlessly serialize independent operations (e.g.
   incrementing statistics has no reason to block taking a parcel from the
   queue), hurting parallelism with no additional correctness benefit.
2. Replacing `Thread`/`join()`/`wait()`/`notifyAll()` with higher-level
   `java.util.concurrent` utilities (`ExecutorService`, `CountDownLatch`,
   `BlockingQueue`, `ReentrantLock`/`Condition`). Rejected for the required
   exercise because the assignment explicitly requires platform threads and
   the classic monitor primitives to demonstrate understanding of them; kept
   as a possible optional-challenge direction (`BlockingQueue`,
   `Lock`/`Condition`) for a future iteration.

## Quality attributes affected

- **Correctness / Reliability** (positive): eliminates demonstrable race
  conditions.
- **Performance / Throughput** (slight cost, mitigated by keeping each
  critical region as small as possible and using lock-free structures where
  the invariant allows it).
- **Maintainability** (positive): each class documents which invariant it
  protects and why that mechanism was chosen.
- **Scalability** (positive): there is no single global point of contention.

## Evidence

- `mvn clean test` passes (2/2 tests in `InvariantCheckerTest`).
- `RaceConditionProbe` went from 100% anomalous runs before the fix (20/20 at
  8 robots/100 parcels, 5/5 at 16/250, 5/5 at 32/500 — the latter two runs
  each also produced an `IndexOutOfBoundsException`) to **0/100 anomalies**
  after the fix, for all three configurations (8×100, 16×250, 32×500), 100
  runs each.
- `WarehouseMain` went from reporting 21/100 processed parcels prematurely to
  reporting 100/100 consistently after `join()`.
- `PauseResumeDemo` shows a consistent paused snapshot (66 pending + 114
  processed = 180 initial parcels).

Full raw evidence and command lines are documented in
`ARSW_2026-2_Lab2_Plantilla_Entrega_Estudiantes.md` (sections 3, 10 and 17).

## Consequences

Any future extension of the shared state (new fields, new classes) must
explicitly analyze which invariant it protects and choose the corresponding
minimal mechanism, instead of blindly copying "add `synchronized`". Mixing
heterogeneous mechanisms (monitor in some classes, lock-free atomics in
others) requires whoever maintains the code to understand the difference and
not misuse them (e.g. `wait()`/`notifyAll()` can only be called inside a
`synchronized` block on the same monitor object).

## Risks

If a future invariant relates fields of `WarehouseStatistics` to fields of
`DeliveryRegistry` (a stricter cross-check than I4), the current mechanisms
(independent atomics vs. a separate monitor) would no longer be sufficient,
and explicit coordination between both classes would need to be introduced —
risking new contention or, if done incorrectly, a new race condition.
Migrating to a multi-instance architecture (three JVMs behind a load
balancer, discussed in section 14 of the report) would require a full
redesign of the consistency mechanism (from in-memory locks to database
guarantees), since the current `synchronized` blocks offer no protection
across separate processes/JVMs.
