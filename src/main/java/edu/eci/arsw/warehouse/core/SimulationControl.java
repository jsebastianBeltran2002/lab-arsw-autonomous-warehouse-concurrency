package edu.eci.arsw.warehouse.core;


public class SimulationControl {

    private boolean paused;

    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
        notifyAll();
    }

    public synchronized void awaitIfPaused() throws InterruptedException {
        while (paused) {
            wait();
        }
    }

    public synchronized boolean isPaused() {
        return paused;
    }
}
