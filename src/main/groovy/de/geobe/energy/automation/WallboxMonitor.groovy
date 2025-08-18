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
import de.geobe.energy.recording.LogMessageRecorder
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject
import static de.geobe.energy.go_e.Wallbox.CarState
import static de.geobe.energy.go_e.Wallbox.ForceState

import java.util.concurrent.TimeUnit

/**
 * Responsibility of WallboxMoitor:
 * <ul>
 *     <li>when triggered by powerMonitor, read Wallbox data </li>
 *     <li>interprete Wallbox values as NoCar, CarFullyLoaded, CarReadyToLoad</li>
 *     <li>send interpreted value to all valueSubscribers</li>
 * </ul>
 */
@ActiveObject
class WallboxMonitor implements WallboxValueProvider {
    /** subscription cycle time */
    private long cycle = 5
    /** subscription Time unit */
    private TimeUnit timeUnit = TimeUnit.SECONDS
    /** first subscription initial delay */
    private long initialDelay = 0
    /** Reference to wallbox */
    private Wallbox wallbox
    /** last Values read */
    private volatile WallboxValues values
    /** all valueSubscribers to wallbox values */
    private List<WallboxValueSubscriber> valueSubscribers = []
    /** all valueSubscribers to car states taken from wallbox values */
    private List<WallboxStateSubscriber> stateSubscribers = []
    /** task to read power values periodically */
    private PeriodicExecutor executor

    static short E_LIMIT = 1500

    /**
     * state is determined by values read from wallbox and in some cases
     * by previous state
     */
    static enum CarChargingState {
        UNDEFINED,
        NO_CAR,
        WAIT_CAR,               // transient -> NOT_CHARGING
        NOT_CHARGING,
        CHARGE_REQUEST,         // transient but must be tracked
        STARTUP_CHARGING,       // sum up transient
        CHARGING,
        FINISH_CHARGING,
        FULLY_CHARGED,
//        CHARGE_STOP_0,         // transient
//        CHARGE_STOP_1,         // transient
        CHARGE_STOPPING,       // sum up transient
        CHARGE_STOP_FULL,
        START_AGAIN,
        STOP_AGAIN,
    }

    static enum CarChargingControlState {
        IDLE,
        STARTED,
        STOPPED,
        AGAIN_STARTED,
        AGAIN_STOPPED,
    }

    /**
     * possible states car takes on after a starting to charge
     */
    static final appStartedStates = [CarChargingState.CHARGE_REQUEST,
                                     CarChargingState.STARTUP_CHARGING,
                                     CarChargingState.CHARGING,
                                     CarChargingState.FINISH_CHARGING,
                                     CarChargingState.FULLY_CHARGED]

    /**
     * possible states after stopping to charge
     */
    static final appStoppedStates = [CarChargingState.NOT_CHARGING,
                                     CarChargingState.CHARGE_STOPPING,
                                     CarChargingState.CHARGE_STOP_FULL]

    private volatile CarChargingState currentCarChargingState
    private volatile CarChargingControlState controlState = CarChargingControlState.IDLE
    private volatile WallboxValues prevWbValues, currentWbValues
    private CarChargingState prevCarChargingState = CarChargingState.UNDEFINED
    private long startupTimestamp
    private static WallboxMonitor wbMonitor

    static synchronized getMonitor() {
        if (!wbMonitor) {
            wbMonitor = new WallboxMonitor(wallbox: Wallbox.wallbox)
        }
        wbMonitor
    }

    @ActiveMethod(blocking = true)
    WallboxMonitorValues readWallbox() {
        currentWbValues = wallbox.values
        currentCarChargingState = calcChargingState(currentWbValues)
        if (!prevWbValues || prevWbValues?.differs(currentWbValues, (short) 10)) {
            def log = "WallboxMonitor: $currentWbValues @ CarChargingState -> $currentCarChargingState"
            LogMessageRecorder.recorder.logMessage(log.toString())
        }
        prevWbValues = currentWbValues
//        valueSubscribers.each {
//            it.takeWallboxValues(currentWbValues)
//        }
        if (stateSubscribers) {
            if (currentCarChargingState != prevCarChargingState) {
                prevCarChargingState = currentCarChargingState
                stateSubscribers.each {
                    it.takeWallboxState(currentCarChargingState)
                }
            }
        }
        new WallboxMonitorValues(currentWbValues, currentCarChargingState)
    }

    Set<MonitorExceptionSubscriber> getSubscribers() {
        ArrayList<MonitorExceptionSubscriber> subscribers = []
        subscribers.addAll(stateSubscribers)
        subscribers.addAll(valueSubscribers)
        subscribers.toSet()
    }

    /**
     * calculate charging state of car from various status values
     * that are read from wallbox
     * as described in table CarChargingStates.md
     * @return calculated current car charging state
     */
    @ActiveMethod(blocking = true)
    private CarChargingState calcChargingState(WallboxValues v) {
        CarChargingState resultingState
        boolean mayCharge = v.allowedToCharge
        CarState carState = v.carState
        ForceState forceState = v.forceState
        def energy = v.energy
        if (carState == CarState.IDLE) {
            resultingState = CarChargingState.NO_CAR
        } else if (carState == CarState.WAIT_CAR) {
            resultingState = CarChargingState.WAIT_CAR
        } else {
            if (!mayCharge) {
                if (forceState == ForceState.OFF && carState == CarState.COMPLETE) {
                    resultingState = CarChargingState.NOT_CHARGING
                } else if (forceState in [ForceState.ON, ForceState.NEUTRAL] && carState == CarState.COMPLETE) {
                    resultingState = CarChargingState.CHARGE_REQUEST
                } else if (forceState == ForceState.OFF && carState == CarState.CHARGING) {
                    resultingState = CarChargingState.CHARGE_STOPPING
                } else {
                    resultingState = CarChargingState.UNDEFINED
                }
                startupTimestamp = 0
            } else {
                if (forceState in [ForceState.ON, ForceState.NEUTRAL]) {
                    if (carState == CarState.COMPLETE) {
                        if (prevCarChargingState in [CarChargingState.CHARGING,
                                                     CarChargingState.FINISH_CHARGING,
                                                     CarChargingState.FULLY_CHARGED,
                                                     CarChargingState.UNDEFINED]) {
                            resultingState = CarChargingState.FULLY_CHARGED
                        } else {
                            resultingState = CarChargingState.CHARGE_REQUEST
                        }
                        startupTimestamp = 0
                    } else if (carState == CarState.CHARGING) {
                        if (energy < E_LIMIT) {
                            if (prevCarChargingState in [CarChargingState.WAIT_CAR,
                                                         CarChargingState.NOT_CHARGING,
                                                         CarChargingState.CHARGE_REQUEST,
                                                         CarChargingState.STARTUP_CHARGING]) {
                                if(startupTimestamp == 0) {
                                    // remember startup time
                                    startupTimestamp = System.currentTimeMillis()
                                    resultingState = CarChargingState.STARTUP_CHARGING
                                } else if (System.currentTimeMillis() - startupTimestamp < 120 * 1000) {
                                    def t = (System.currentTimeMillis() - startupTimestamp).intdiv(1000)
                                    LogMessageRecorder.logMessage "since $t s, energy $energy W, prev: $prevCarChargingState".toString()
                                    // give car up to 120 seconds to fully start charging
                                    resultingState = CarChargingState.STARTUP_CHARGING
                                } else {
                                    def t = (System.currentTimeMillis() - startupTimestamp).intdiv(1000)
                                    // car seems to be fully charged
                                    LogMessageRecorder.logMessage "stopped starting up after $t s".toString()
                                    resultingState = CarChargingState.FINISH_CHARGING
                                    startupTimestamp = 0
                                }
                            } else if (prevCarChargingState == CarChargingState.CHARGING) {
                                resultingState = CarChargingState.FINISH_CHARGING
                            } else if(prevCarChargingState == CarChargingState.FINISH_CHARGING) {
                                resultingState = CarChargingState.FINISH_CHARGING
                            }
                        } else {
                            resultingState = CarChargingState.CHARGING
                            startupTimestamp = 0
                        }
                    } else {
                        def log = "WallboxMonitor (1, $carState): $currentWbValues -> ${CarChargingState.UNDEFINED}"
                        LogMessageRecorder.recorder.logMessage(log.toString())
                        resultingState = CarChargingState.UNDEFINED
                        startupTimestamp = 0
                    }
                } else {
                    def log = "WallboxMonitor (2, $forceState): $currentWbValues -> ${CarChargingState.UNDEFINED}"
                    LogMessageRecorder.recorder.logMessage(log.toString())
                    resultingState = CarChargingState.UNDEFINED
                    startupTimestamp = 0
                }
            }
        }
        def returnValue
        // check, if external command (handy-app, car etc) has switched charging independently
        if (prevCarChargingState in appStoppedStates && resultingState in appStartedStates
                && controlState != CarChargingControlState.STARTED) {
            returnValue = CarChargingState.START_AGAIN
            controlState = CarChargingControlState.AGAIN_STARTED
        } else if (prevCarChargingState in appStartedStates && resultingState in appStoppedStates
                && controlState != CarChargingControlState.STOPPED) {
            returnValue = CarChargingState.STOP_AGAIN
            controlState = CarChargingControlState.AGAIN_STOPPED
        } else {
            returnValue = resultingState
        }
        prevCarChargingState = resultingState
        return returnValue
    }

    def controlStateStart() {
        if (prevCarChargingState in appStoppedStates) {
            controlState = CarChargingControlState.STARTED
        }
    }

    def controlStateStop() {
        if (prevCarChargingState in appStartedStates) {
            controlState = CarChargingControlState.STOPPED
        }
    }


/*
Takes some time before load current is back to requested
 */

    @ActiveMethod(blocking = true)
    def getCurrent() {
        if(!currentWbValues) {
            // only if called before first monitor activation by PowerManager
            currentWbValues = wallbox.values
            currentCarChargingState = calcChargingState(currentWbValues)
        }
        [values: currentWbValues, state: currentCarChargingState]
    }

    @ActiveMethod(blocking = true)
    def startCharging() {
        controlStateStart()
        wallbox.startCharging()
    }

    @ActiveMethod(blocking = true)
    def forceStartCharging() {
        controlStateStart()
        wallbox.forceStartCharging()
    }

    @ActiveMethod(blocking = true)
    def stopCharging() {
//        print " -stop charging- "
        controlStateStop()
        wallbox.stopCharging()
        setCurrent(0)
    }

    @ActiveMethod(blocking = true)
    def setCurrent(int amp = 0) {
//        print " -set current to $amp- "
        wallbox.chargingCurrent = amp
//        println " --> $wallbox.wallboxValues"
    }

//    @ActiveMethod(blocking = true)
//    CarChargingState getChargingState() {
//        state
//    }

    @ActiveMethod
    void subscribeValue(WallboxValueSubscriber subscriber) {
//            def willStart = noSubscribers()
        println "added valueSubscriber $subscriber"
        valueSubscribers.add subscriber
//            if (willStart)
//                start()
    }

    @ActiveMethod
    void unsubscribeValue(WallboxValueSubscriber subscriber) {
        valueSubscribers.remove subscriber
//            if (noSubscribers()) {
//                stop()
//            }
    }

    @ActiveMethod
    void subscribeState(WallboxStateSubscriber subscriber) {
//            def willStart = noSubscribers()
        println "added stateSubscriber $subscriber"
        stateSubscribers.add subscriber
//            if (willStart) {
//                start()
//            }
    }

    @ActiveMethod
    void unsubscribeState(WallboxStateSubscriber subscriber) {
        stateSubscribers.remove subscriber
//            if (noSubscribers())
//                stop()
    }

}

record WallboxMonitorValues(
        WallboxValues wallboxValues,
        WallboxMonitor.CarChargingState chargingState
){}

interface WallboxValueProvider {
    void subscribeValue(WallboxValueSubscriber subscriber)

    void unsubscribeValue(WallboxValueSubscriber subscriber)
}

interface WallboxStateSubscriber extends MonitorExceptionSubscriber {
    void takeWallboxState(WallboxMonitor.CarChargingState carState)
}

interface WallboxValueSubscriber extends MonitorExceptionSubscriber {
    void takeWallboxValues(WallboxValues values)
}
