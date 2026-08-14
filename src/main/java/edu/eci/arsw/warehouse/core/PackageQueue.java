package edu.eci.arsw.warehouse.core;

import edu.eci.arsw.warehouse.model.Parcel;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;


public class PackageQueue {

    private final Queue<Parcel> pending = new ConcurrentLinkedQueue<>();

    public PackageQueue(List<Parcel> parcels) {
        pending.addAll(parcels);
    }

    public Parcel takeNext() {
        // Atomic: returns null when empty, otherwise removes and returns the head.
        return pending.poll();
    }

    public int pendingCount() {
        return pending.size();
    }
}
