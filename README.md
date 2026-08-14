# Autonomous Warehouse — ARSW Lab 2

Simulación de un almacén con robots autónomos (hilos) que corrigen condiciones
de carrera, coordinan su finalización con `join()` y manejan pausa/reanudación
con `synchronized` + `wait()`/`notifyAll()`.

## Requisitos

- JDK 21
- Maven 3.9+

## Uso

```bash
mvn clean test

# Simulación
java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain 12 100

# Verificación de condiciones de carrera
java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 100 32 500

# Demo de pausa/reanudación
java -cp target/classes edu.eci.arsw.warehouse.app.PauseResumeDemo
```

## Documentación

- [`docs/REPORT.md`](docs/REPORT.md) — diagnóstico, invariantes y verificación
- [`docs/ADR-001-concurrency-control.md`](docs/ADR-001-concurrency-control.md) — decisión de diseño
- [`ARSW_2026-2_Lab2_Entrega_Warehouse.docx`](ARSW_2026-2_Lab2_Entrega_Warehouse.docx) — entrega completa
