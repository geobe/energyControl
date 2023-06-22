package de.geobe.energy.automation

import de.geobe.energy.e3dc.PowerValues
import de.geobe.energy.e3dc.E3dcInteractionRunner
import de.geobe.energy.e3dc.IStorageInteractionRunner
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

import java.util.concurrent.TimeUnit

/**
 * Periodically read power values from storage system. If values
 * have changed more than hysteresis, send new values to PvLoadStrategyActor
 */
@ActiveObject
class PowerMonitor {
    /** cycle time */
    private long cycle = 5
    /** Time unit */
    private TimeUnit timeUnit = TimeUnit.SECONDS
    /** Reference to Power System */
    private final IStorageInteractionRunner powerInfo
    /** all valueSubscribers to power values */
    private List<PowerValueSubscriber> subscribers = []
    /** task to read power values periodically */
    private PeriodicExecutor executor

    private static PowerMonitor monitor

    static synchronized PowerMonitor getMonitor() {
        if(! monitor) {
            monitor = new PowerMonitor(E3dcInteractionRunner.interactionRunner)
            monitor.start()
        }
        monitor
    }

    private PowerMonitor(IStorageInteractionRunner interactionRunner) {
        powerInfo = interactionRunner
    }

    private Runnable readPower = new Runnable() {
        @Override
        void run() {
            def current = powerInfo.currentValues
            subscribers.each {
                it.takePowerValues(current())
            }
        }
    }

    @ActiveMethod(blocking = true)
    PowerValues getCurrent() {
        powerInfo.currentValues
    }

    @ActiveMethod
    void subscribe(PowerValueSubscriber subscriber) {
        def willStart = subscribers.size() == 0
        subscribers.add subscriber
        if (willStart)
            start()
    }

    @ActiveMethod
    void unsubscribe(PowerValueSubscriber subscriber) {
        subscribers.remove subscriber
        if (subscribers.size() == 0)
            stop()
    }

    private start() {
        println "monitor started with $cycle $timeUnit period"
        executor = new PeriodicExecutor(readPower, cycle, timeUnit)
        executor.start()
    }

    private stop() {
        println "monitor stoped "
        executor?.stop()
        executor = null
    }

    @ActiveMethod
    def shutdown() {
        executor?.shutdown()
    }
}

interface PowerValueSubscriber {
    void takePowerValues(PowerValues powerValues)
}