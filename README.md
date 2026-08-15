# Autonomous Warehouse — ARSW Lab 2

Simulación de un almacén con robots autónomos (`Thread`) que compiten por
tomar pedidos de una cola compartida, registrar su entrega y actualizar
estadísticas. El objetivo del laboratorio es corregir las condiciones de
carrera del estado compartido sin sincronizar más de lo necesario.

## Requisitos

- JDK 21
- Maven 3.9+

```bash
java -version
mvn -version
```

## Compilar y ejecutar

```bash
# Tests unitarios
mvn clean test

# Simulación (robots, pedidos) — valores por defecto: 12 robots, 100 pedidos
java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain
java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain 24 250

# Probador de condiciones de carrera (runs, robots, pedidos)
java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe
java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 100 32 500

# Demo de pausa/reanudación
java -cp target/classes edu.eci.arsw.warehouse.app.PauseResumeDemo
```

---

## Parte I — Diagnóstico

### 1. Inventario de estado compartido

| Objeto compartido | Estado mutable | Lectores | Escritores | Invariante posible |
|---|---|---|---|---|
| `PackageQueue` | `pending` (cola de `Parcel`) | todos los `WarehouseRobot` (`takeNext()`) | todos los `WarehouseRobot` | un pedido se entrega a un único robot |
| `DeliveryRegistry` | `nextPosition`, `deliveries` | `WarehouseMain`, `RaceConditionProbe` | todos los `WarehouseRobot` (`register()`) | las posiciones de llegada son únicas y consecutivas (1..N) |
| `WarehouseStatistics` | `processedParcels`, `totalProcessingMillis` | `WarehouseMain`, `RaceConditionProbe` | todos los `WarehouseRobot` (`recordProcessed()`) | el contador de procesados coincide con el tamaño del registro |
| `SimulationControl` | `paused` | todos los `WarehouseRobot` (`awaitIfPaused()`) | hilo principal/demo (`pause()`/`resume()`) | ningún robot consume CPU en espera activa mientras está en pausa |

### 2. Anomalías observadas (versión sin sincronizar)

1. **`PackageQueue.takeNext()`**: al usar una lista con `isEmpty()` →
   `get(0)` → `remove(0)` como pasos separados (check-then-act), dos robots
   podían tomar el mismo pedido, o el programa lanzaba
   `IndexOutOfBoundsException` por modificación concurrente de la lista.
2. **`DeliveryRegistry.register()`**: `nextPosition = nextPosition + 1` no es
   atómico; dos robots podían leer el mismo valor antes de escribirlo,
   generando posiciones de llegada duplicadas o saltadas.
3. **`WarehouseStatistics.recordProcessed()`**: el incremento (`++`) del
   contador no es atómico, así que se perdían actualizaciones y
   `processedParcels()` terminaba por debajo del número real de pedidos
   entregados.
4. **`WarehouseMain.main()`**: imprimía el reporte final tras un
   `Thread.sleep(...)` fijo en vez de esperar a los robots, mostrando un
   reporte inconsistente con la mayoría de los pedidos aún pendientes.

### 3. Interleaving de ejemplo (`DeliveryRegistry.register()`)

| Paso | Robot A | Robot B | Estado compartido |
|---|---|---|---|
| 1 | lee `nextPosition = 5` | — | `nextPosition = 5` |
| 2 | — | lee `nextPosition = 5` | `nextPosition = 5` |
| 3 | calcula `assigned = 5`, escribe `nextPosition = 6` | — | `nextPosition = 6` |
| 4 | — | calcula `assigned = 5` (dato viejo), escribe `nextPosition = 6` | `nextPosition = 6` |
| 5 | agrega registro con posición 5 | agrega registro con posición 5 | posición 5 duplicada, posición 6 nunca se usa |

El resultado depende del *scheduler* del SO/JVM: si el sistema operativo
intercala los dos hilos entre la lectura y la escritura de `nextPosition`,
aparece la anomalía; si un hilo ejecuta las tres instrucciones sin ser
interrumpido, el resultado es correcto. Por eso el error no es determinista
y una sola ejecución exitosa no prueba que el código sea correcto.

---

## Parte II — Invariantes

| Candidato | Clasificación |
|---|---|
| Cada pedido se procesa a lo sumo una vez | requerido (I1) |
| Ningún pedido desaparece del sistema | requerido (I2) |
| Las posiciones de llegada son únicas | requerido (I3) |
| Las posiciones forman una secuencia válida 1..N | derivado de I3 + I1 |
| El contador de procesados coincide con el registro de entregas | requerido (I4) |
| Al terminar, no quedan pedidos pendientes | derivado de I1 + I2 |

Conjunto final de invariantes:

- **I1**: cada pedido se toma y procesa como máximo una vez.
- **I2**: `pendingParcels + deliveries.size() == initialParcels` en todo momento.
- **I3**: las posiciones en `DeliveryRegistry` son únicas y forman la secuencia 1..N al terminar.
- **I4**: `statistics.processedParcels() == deliveries.size()` una vez que todos los robots terminaron (`join()`).

---

## Parte III — Regiones críticas

| Clase | Región crítica | Invariante protegido | Mecanismo | Por qué ese tamaño |
|---|---|---|---|---|
| `PackageQueue` | `takeNext()` (delega en `poll()`) | I1 | `ConcurrentLinkedQueue` (sin bloqueo explícito) | `poll()` ya es atómico; no hace falta un `synchronized` adicional |
| `DeliveryRegistry` | `register()` y `snapshot()` completos | I3, I4 | `synchronized` | asignar la posición e insertar el registro deben ocurrir como un solo paso atómico |
| `WarehouseStatistics` | cada incremento (`incrementAndGet`, `addAndGet`) | I4 | `AtomicInteger` / `AtomicLong` | los dos contadores son independientes entre sí; no necesitan un lock compartido |
| `SimulationControl` | `pause()`, `resume()`, `awaitIfPaused()`, `isPaused()` | pausa sin espera activa | `synchronized` + `wait()`/`notifyAll()` | se coordinan varios hilos (no solo se protege un dato), lo que exige el patrón monitor |

No se usó un único lock global ni se marcó cada método `public` como
`synchronized`: cada clase usa el mecanismo mínimo que su invariante exige.

**Si la región protegida fuera más grande de lo necesario** (por ejemplo, un
`synchronized` sobre todo el método `run()` del robot en vez de solo sobre
`register()`), los robots se bloquearían entre sí incluso mientras procesan
pedidos de forma independiente, serializando trabajo que podría ejecutarse en
paralelo y reduciendo el throughput sin ganar nada en corrección.

---

## Parte IV — Finalización de hilos

`WarehouseSimulation.start()` crea y arranca todos los `WarehouseRobot` antes
de que el hilo principal continúe. `awaitCompletion()` recorre la lista de
robots y llama `robot.join()` sobre cada uno, así el hilo principal solo
imprime el reporte final cuando **todos** terminaron.

**Por qué `Thread.sleep(...)` no reemplaza a `join()`**: `sleep` solo
garantiza que pasó cierto tiempo, no que el trabajo terminó. El tiempo real
de cada robot depende de la carga, el número de pedidos y el *scheduler*, así
que cualquier tiempo fijo puede ser insuficiente (reporte incompleto) o
excesivo (tiempo perdido). `join()` espera exactamente hasta que el hilo
objetivo finalice, sin importar cuánto tarde.

---

## Parte V — Pausa / Reanudación

`SimulationControl` reemplazó el ciclo de espera activa
(`while (paused) Thread.onSpinWait();`) por un monitor de Java:

```java
public synchronized void awaitIfPaused() throws InterruptedException {
    while (paused) {
        wait();
    }
}

public synchronized void resume() {
    paused = false;
    notifyAll();
}
```

- `pause()` marca `paused = true`; los robots lo detectan la próxima vez que
  llaman `awaitIfPaused()` (punto seguro entre pedido y pedido).
- Mientras están en pausa, los robots quedan bloqueados dentro de `wait()`
  sin consumir CPU (no hay bucle activo).
- `resume()` cambia `paused = false` y llama `notifyAll()` una sola vez,
  despertando a todos los robots en espera.

**Consistencia del snapshot en pausa**: `pause()` y `resume()` son
`synchronized` sobre el mismo objeto monitor, así que un robot solo puede
salir de `awaitIfPaused()` después de que `resume()` termine de ejecutarse.
Mientras el sistema está en pausa ningún robot puede estar a mitad de
`register()` o `recordProcessed()` esperando (esos métodos ya terminaron o
aún no empezaron), por lo que el snapshot (pendientes, procesados, tamaño del
registro, líder actual) refleja un estado quieto y no una foto a mitad de una
escritura.

---

## Parte VI — Verificación

```bash
mvn clean test
java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 100 32 500
```

| Robots | Pedidos | Runs | Anomalías (versión sin sincronizar) | Anomalías (versión corregida) |
|---:|---:|---:|---:|---:|
| 8 | 100 | 100 | alta (duplicados/perdidos en casi todas las corridas) | 0/100 |
| 16 | 250 | 100 | alta (incluye `IndexOutOfBoundsException`) | 0/100 |
| 32 | 500 | 100 | alta (incluye `IndexOutOfBoundsException`) | 0/100 |

`mvn clean test` debe pasar los 2 tests de `InvariantCheckerTest`. Para
reproducir la columna "versión corregida" basta con ejecutar los comandos de
arriba sobre el código actual del repositorio.

---

## Parte VII — Análisis arquitectónico

### 1. Decisión principal

- **Problema**: varios hilos modificando el mismo estado sin coordinación
  (cola de pedidos, registro de entregas, contadores, bandera de pausa).
- **Invariantes a preservar**: I1–I4 (ver Parte II).
- **Alternativas consideradas**: un único lock global para todo el estado
  compartido, o marcar todos los métodos `public` como `synchronized`.
- **Por qué se descartaron**: serializarían operaciones independientes (por
  ejemplo, incrementar estadísticas no tiene por qué bloquear la toma de un
  pedido), perdiendo paralelismo sin ninguna ganancia de corrección.
- **Mecanismo elegido**: sincronización específica por clase (cola
  *lock-free*, `synchronized` solo donde varios pasos deben ser atómicos,
  variables atómicas donde los contadores son independientes, monitor
  `wait`/`notifyAll` para coordinar pausa/reanudación).
- **Consecuencias**: el código es más largo de justificar (cada clase usa un
  mecanismo distinto), pero cada uno protege exactamente el invariante que le
  corresponde.

### 2. Atributos de calidad

- **Correctitud / confiabilidad**: las condiciones de carrera identificadas
  se eliminan; el probador de condiciones de carrera converge a 0 anomalías.
- **Rendimiento / throughput**: impacto mínimo porque las regiones críticas
  son pequeñas y se usan estructuras sin bloqueo (`ConcurrentLinkedQueue`,
  `AtomicInteger`/`AtomicLong`) donde el invariante lo permite.
- **Mantenibilidad**: cada clase deja claro, por su implementación, qué
  invariante protege y con qué mecanismo, en vez de un candado genérico que
  no explica nada por sí mismo.

### 3. Límite arquitectónico

Si el almacén se despliega como **tres instancias de JVM independientes**
detrás de un balanceador de carga:

- Los bloques `synchronized` **no** protegerían el invariante entre
  instancias, porque cada JVM tiene su propia memoria y sus propios objetos
  monitor; una JVM no sabe nada de los locks de otra.
- Se necesitaría un mecanismo de coordinación **fuera del proceso**: por
  ejemplo, una base de datos con transacciones/restricciones (constraint de
  unicidad para la posición de llegada), o un servicio de bloqueo
  distribuido, en lugar de memoria compartida con `synchronized`.

---

## Estructura del proyecto

```
src/main/java/edu/eci/arsw/warehouse/
  app/            WarehouseMain, PauseResumeDemo, WarehouseSimulation, WarehouseFactory
  core/           PackageQueue, DeliveryRegistry, WarehouseStatistics, SimulationControl
  model/          Parcel, DeliveryRecord, WarehouseSnapshot
  worker/         WarehouseRobot
  verification/   InvariantChecker, InvariantReport, RaceConditionProbe
src/test/java/edu/eci/arsw/warehouse/
  InvariantCheckerTest
```
