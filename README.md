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
