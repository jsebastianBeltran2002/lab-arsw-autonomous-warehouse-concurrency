package edu.eci.arsw.warehouse.worker;

import edu.eci.arsw.warehouse.core.DeliveryRegistry;
import edu.eci.arsw.warehouse.core.PackageQueue;
import edu.eci.arsw.warehouse.core.SimulationControl;
import edu.eci.arsw.warehouse.core.WarehouseStatistics;
import edu.eci.arsw.warehouse.model.Parcel;

import java.util.concurrent.ThreadLocalRandom;

public class WarehouseRobot extends Thread {

    private final int robotId;
    private final PackageQueue packageQueue;
    private final DeliveryRegistry deliveryRegistry;
    private final WarehouseStatistics statistics;
    private final SimulationControl control;
    private final long simulationStartNanos;

    public WarehouseRobot(
            int robotId,
            PackageQueue packageQueue,
            DeliveryRegistry deliveryRegistry,
            WarehouseStatistics statistics,
            SimulationControl control,
            long simulationStartNanos) {
        super("warehouse-robot-" + robotId);
        this.robotId = robotId;
        this.packageQueue = packageQueue;
        this.deliveryRegistry = deliveryRegistry;
        this.statistics = statistics;
        this.control = control;
        this.simulationStartNanos = simulationStartNanos;
    }

    @Override
    public void run() {
        while (true) {
            try {
                control.awaitIfPaused();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            Parcel parcel = packageQueue.takeNext();

            if (parcel == null) {
                return;
            }

            long processingMillis = process(parcel);

            long elapsedMillis = (System.nanoTime() - simulationStartNanos) / 1_000_000L;
            deliveryRegistry.register(robotId, parcel.id(), elapsedMillis);
            statistics.recordProcessed(processingMillis);
        }
    }

    private long process(Parcel parcel) {
        long started = System.nanoTime();
        try {
            int jitter = ThreadLocalRandom.current().nextInt(0, 8);
            Thread.sleep(parcel.processingMillis() + jitter);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
