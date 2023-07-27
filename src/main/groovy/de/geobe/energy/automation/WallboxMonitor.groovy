/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2023. Georg Beier. All rights reserved.
 *
 * Permission is hereby granted, free of continueCharging, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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

    static enum CarChargingState {
        UNDEFINED,
        NO_CAR,
        WAIT_CAR,
        CHARGING,
        CHARGING_ANYWAY,
        FULLY_CHARGED,
        CHARGING_STOPPED_BY_APP,
        CHARGING_STOPPED_BY_CAR,
    }

    private CarChargingState newState, state = CarChargingState.UNDEFINED

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
            def cwv = wallbox.values
            valueSubscribers.each {
                it.takeWallboxValues(cwv)
            }
            if (stateSubscribers) {
                newState = calcChargingState(cwv)
                if (newState != state) {
                    state = newState
                    println "values: $cwv\n\t-> CarChargingState: $state"
                    stateSubscribers.each {
                        it.takeWallboxState(state)
                    }
                }
            }
        }
    }

    @ActiveMethod(blocking = true)
    private CarChargingState calcChargingState(WallboxValues values) {
        CarChargingState result
        if (values?.carState == Wallbox.CarState.IDLE) {
            result = CarChargingState.NO_CAR
        } else if (values?.carState == Wallbox.CarState.WAIT_CAR) {
            result = CarChargingState.WAIT_CAR
        } else if (values?.carState == Wallbox.CarState.CHARGING
//                && values?.forceState == Wallbox.ForceState.NEUTRAL     // necessary condition ?
        ) {
            if (values.requestedCurrent == wallbox.maxCurrent) {
                // unconditionally switch to maximal charging current
                result = CarChargingState.CHARGING_ANYWAY
            } else {
                // continueCharging with default charging mode
                result = CarChargingState.CHARGING
            }
        } else if (values?.carState == Wallbox.CarState.COMPLETE
                && values?.allowedToCharge == true
                && values.forceState == Wallbox.ForceState.NEUTRAL
        ) {
            // to be checked, same as stopped by car???
            result = CarChargingState.CHARGING_STOPPED_BY_CAR
        } else if (values?.carState == Wallbox.CarState.COMPLETE
                && values?.allowedToCharge == true) {
            // to be checked, same as stopped by car???
            result = CarChargingState.FULLY_CHARGED
        } else if (values?.forceState == Wallbox.ForceState.OFF
                && values.allowedToCharge == false ) {
            result = CarChargingState.CHARGING_STOPPED_BY_APP
        }
        result
    }


/*
charging: WallboxValues[allowedToCharge=true, requestedCurrent=6, carState=CHARGING, forceState=NEUTRAL, energy=4080, phaseSwitchMode=FORCE_3]
stop by car: WallboxValues[allowedToCharge=true, requestedCurrent=6, carState=COMPLETE, forceState=NEUTRAL, energy=0, phaseSwitchMode=FORCE_3]
stop by app: WallboxValues[allowedToCharge=false, requestedCurrent=6, carState=COMPLETE, forceState=OFF, energy=0, phaseSwitchMode=FORCE_3]
no car:  WallboxValues[allowedToCharge=true, requestedCurrent=6, carState=IDLE, forceState=NEUTRAL, energy=0, phaseSwitchMode=FORCE_3]

Takes some time before load current is back to requested
 */

    @ActiveMethod(blocking = true)
    def getCurrent() {
        def values = wallbox.values
        def currentState = calcChargingState(values)
        [values: values, state: currentState]
    }

//    @ActiveMethod(blocking = true)
//    CarChargingState getChargingState() {
//        state
//    }

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
        println "added stateSubscriber $subscriber"
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
            void takeWallboxState(CarChargingState carState) {
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
        Thread.sleep 12000
        WallboxMonitor.monitor.unsubscribeValue valueSubscriber
        WallboxMonitor.monitor.unsubscribeState stateSubscriber
        Thread.sleep 1000
        WallboxMonitor.monitor.shutdown()

    }

}

interface WallboxStateSubscriber {
    void takeWallboxState(WallboxMonitor.CarChargingState carState)
}

interface WallboxValueSubscriber {
    void takeWallboxValues(WallboxValues values)
}
