package de.geobe.energy.automation

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PeriodicExecutor {

    private static ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(5)

    private long cycleTime
    private TimeUnit timeUnit
    final Runnable task
    private ScheduledFuture taskHandle

    PeriodicExecutor(Runnable task, long cycleTime, TimeUnit timeUnit) {
        this.task = task
        this.cycleTime = cycleTime
        this.timeUnit = timeUnit
    }

    def start() {
        taskHandle = executor.scheduleAtFixedRate(task, 0, cycleTime, timeUnit)
    }

    def stop() {
        taskHandle?.cancel(false)
    }

    static shutdown() {
        executor.shutdown()
    }

}
