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
    private Wallbox wallbox
    /** last Values read */
//    private WallboxValues values
    /** all valueSubscribers to wallbox values */
    private List<WallboxValueSubscriber> valueSubscribers = []
    /** all valueSubscribers to car states */
    private List<WallboxStateSubscriber> stateSubscribers = []
    /** task to read power values periodically */
    private PeriodicExecutor executor

    static enum CarLoadingState {
        UNDEFINED,
        NO_CAR,
        CHARGING,
        FULLY_CHARGED,
        CHARGING_STOPPED,
    }

    private CarLoadingState newState, state = CarLoadingState.UNDEFINED

    private static WallboxMonitor wbMonitor

    static synchronized getMonitor() {
        if (!wbMonitor) {
            wbMonitor = new WallboxMonitor(wallbox: Wallbox.wallbox)
        }
        wbMonitor
    }

    private Runnable readWallbox = new Runnable() {
        @Override
        void run() {
            def cwv = wallbox.wallboxValues
            valueSubscribers.each {
                it.takeWallboxValues(cwv)
            }
            if (hasStateChanged(cwv)) {
                stateSubscribers.each {
                    it.takeWallboxState(state)
                }
            }
        }
    }

    @ActiveMethod(blocking = true)
    private boolean hasStateChanged(WallboxValues values) {
        if (values?.carState == Wallbox.CarState.IDLE) {
            newState = CarLoadingState.NO_CAR
        } else if (values?.carState == Wallbox.CarState.CHARGING) {
            newState = CarLoadingState.CHARGING
        } else if (values?.carState == Wallbox.CarState.COMPLETE
                && values?.allowedToCharge == true) {
            // to be checked, same as stopped by car???
            newState = CarLoadingState.FULLY_CHARGED
        } else if (values?.forceState == Wallbox.ForceState.OFF
                && values.allowedToCharge == false ) {
            newState = CarLoadingState.CHARGING_STOPPED
        }
        if (newState != state) {
            state = newState
            true
        } else {
            false
        }
    }


/*
loading: WallboxValues[allowedToCharge=true, requestedCurrent=6, carState=CHARGING, forceState=NEUTRAL, energy=4080, phaseSwitchMode=FORCE_3]
stop by car: WallboxValues[allowedToCharge=true, requestedCurrent=6, carState=COMPLETE, forceState=NEUTRAL, energy=0, phaseSwitchMode=FORCE_3]
stop by app: WallboxValues[allowedToCharge=false, requestedCurrent=6, carState=COMPLETE, forceState=OFF, energy=0, phaseSwitchMode=FORCE_3]
no car:  WallboxValues[allowedToCharge=true, requestedCurrent=6, carState=IDLE, forceState=NEUTRAL, energy=0, phaseSwitchMode=FORCE_3]

Takes some time before load current is back to requested
 */

    @ActiveMethod(blocking = true)
    WallboxValues getCurrent() {
        wallbox.wallboxValues
    }

    @ActiveMethod(blocking = true)
    CarLoadingState getLoadingState() {
        state
    }

    @ActiveMethod
    void subscribeValue(WallboxValueSubscriber subscriber) {
        def willStart = noSubscribers()
        println "added valueSubscriber $subscriber"
        valueSubscribers.add subscriber
        if (willStart)
            start()
    }

    @ActiveMethod
    void unsubscribeValue(WallboxValueSubscriber subscriber) {
        valueSubscribers.remove subscriber
        if (noSubscribers()) {
            stop()
        }
    }

    @ActiveMethod
    void subscribeState(WallboxStateSubscriber subscriber) {
        def willStart = noSubscribers()
        stateSubscribers.add subscriber
        if (willStart) {
            start()
        }
    }

    @ActiveMethod
    void unsubscribeState(WallboxStateSubscriber subscriber) {
        stateSubscribers.remove subscriber
        if (noSubscribers())
            stop()
    }

    private boolean noSubscribers() {
        valueSubscribers.size() == 0 && stateSubscribers.size() == 0
    }

    private start() {
        println "wbMonitor started with $cycle $timeUnit period"
        if (! executor) {
            executor = new PeriodicExecutor(readWallbox, cycle, timeUnit)
        }
        executor.start()
    }

    private stop() {
        println "wbMonitor stopped "
        executor?.stop()
    }

    @ActiveMethod
    def shutdown() {
        executor?.shutdown()
    }

    static void main(String[] args) {
        def WallboxStateSubscriber stateSubscriber = new WallboxStateSubscriber() {
            @Override
            void takeWallboxState(CarLoadingState carState) {
                println carState
            }
        }
        def WallboxValueSubscriber valueSubscriber = new WallboxValueSubscriber() {
            @Override
            void takeWallboxValues(WallboxValues values) {
                println values
            }
        }
        WallboxMonitor.monitor.subscribeValue valueSubscriber
        WallboxMonitor.monitor.subscribeState stateSubscriber
        Thread.sleep 10000
        WallboxMonitor.monitor.unsubscribeValue valueSubscriber
        WallboxMonitor.monitor.unsubscribeState stateSubscriber
        Thread.sleep 1000
        WallboxMonitor.monitor.shutdown()

    }

}

interface WallboxStateSubscriber {
    void takeWallboxState(WallboxMonitor.CarLoadingState carState)
}

interface WallboxValueSubscriber {
    void takeWallboxValues(WallboxValues values)
}
