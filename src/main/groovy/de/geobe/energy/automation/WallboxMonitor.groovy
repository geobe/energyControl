package de.geobe.energy.automation


import de.geobe.energy.go_e.Wallbox
import de.geobe.energy.go_e.WallboxValues
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

import java.util.concurrent.TimeUnit

/**
 * Responsibility of WallboxMoitor:
 * <ul>
 *     <li>when activated, continuously read Wallbox data with a fixed frequency</li>
 *     <li>interprete Wallbox values as NoCar, CarFullyLoaded, CarReadyToLoad</li>
 *     <li>send interpreted value to all valueSubscribers</li>
 * </ul>
 */
@ActiveObject
class WallboxMonitor {
    /** cycle time */
    private long cycle = 5
    /** Time unit */
    private TimeUnit timeUnit = TimeUnit.SECONDS
    /** Reference to wallbox */
    private final Wallbox wallbox
    /** last Values read */
    private WallboxValues values
    /** all valueSubscribers to wallbox values */
    private List<WallboxValueSubscriber> valueSubscribers = []
    /** all valueSubscribers to car states */
    private List<WallboxStateSubscriber> stateSubscribers = []
    /** task to read power values periodically */
    private PeriodicExecutor executor

    static enum CarState {
        NO_CAR,
        CAR_FULLY_LOADED,
        CAR_READY_TO_LOAD,
    }

    private CarState state = CarState.NO_CAR

    private static WallboxMonitor monitor

    static synchronized getMonitor() {
        if (!monitor) {
            monitor = new WallboxMonitor(wallbox: Wallbox.wallbox)
        }
        monitor
    }

    private Runnable readWallbox = new Runnable() {
        @Override
        void run() {
            def current = wallbox.wallboxValues
            def newState = onNewValues()
            if (state != newState) {
                state = newState
                valueSubscribers.each {
                    it.takeWallboxState(current())
                }
            }
        }
    }

    @ActiveMethod
    private CarState onNewValues() {
        if (!values?.allowedToCharge) {
            CarState.NO_CAR
        } else if (values?.forceState == Wallbox.ForceState.NEUTRAL
                && values?.carState == Wallbox.CarState.COMPLETE) {
            CarState.CAR_FULLY_LOADED
        } else if (values?.forceState == Wallbox.ForceState.NEUTRAL) {
            CarState.CAR_READY_TO_LOAD
        }
    }


/*
WallboxValues[allowedToCharge=true, requestedCurrent=6, carState=CHARGING, forceState=NEUTRAL, energy=4080, phaseSwitchMode=FORCE_3]
WallboxValues[allowedToCharge=false, requestedCurrent=6, carState=COMPLETE, forceState=OFF, energy=0, phaseSwitchMode=FORCE_3]
Takes some time before load current is back to requested
 */

    @ActiveMethod(blocking = true)
    WallboxValues getCurrent() {
        wallbox.wallboxValues
    }

    @ActiveMethod
    void subscribe(WallboxValueSubscriber subscriber) {
        def willStart = noSubscribers()
        valueSubscribers.add subscriber
        if (willStart)
            start()
    }

    @ActiveMethod
    void unsubscribe(WallboxValueSubscriber subscriber) {
        valueSubscribers.remove subscriber
        if (noSubscribers())
            stop()
    }

    private boolean noSubscribers() {
        valueSubscribers.size() == 0 && stateSubscribers.size() == 0
    }

    private start() {
        println "monitor started with $cycle $timeUnit period"
        executor = new PeriodicExecutor(readWallbox, cycle, timeUnit)
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

interface WallboxStateSubscriber {
    void takeWallboxState(WallboxMonitor.CarState carState)
}

interface WallboxValueSubscriber {
    void takeWallboxValues(WallboxValues values)
}
