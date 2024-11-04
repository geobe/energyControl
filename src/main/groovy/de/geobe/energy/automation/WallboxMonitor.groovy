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

    static enum CarChargingState {
        UNDEFINED,
        NO_CAR,
        WAIT_CAR,               // transient ?
        NOT_CHARGING,
        CHARGE_REQUEST_0,       // transient
        CHARGE_REQUEST_1,       // transient
        STARTUP_CHARGING,       // transient
        CHARGING,
        FORCE_CHARGING,
        STOP_REQUEST_0,         // transient
        STOP_REQUEST_1,         // transient
        FINISH_CHARGING,
        FULLY_CHARGED,
        STOPPED_FULL,
    }

    private volatile CarChargingState currentWbState, prevWbState = CarChargingState.UNDEFINED
    private volatile WallboxValues prevWbValues, currentWbValues

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
            try {
                currentWbValues = wallbox.values
                currentWbState = calcChargingState(currentWbValues)
                if (!prevWbValues || prevWbValues?.differs(currentWbValues)) {
                    def log = "$currentWbValues @ WbState -> $currentWbState"
                    LogMessageRecorder.recorder.logMessage(log.toString())
                }
                prevWbValues = currentWbValues
                valueSubscribers.each {
                    it.takeWallboxValues(currentWbValues)
                }
                if (stateSubscribers) {
                    if (currentWbState != prevWbState) {
                        prevWbState = currentWbState
                        stateSubscribers.each {
                            it.takeWallboxState(prevWbState)
                        }
                    }
                }
            } catch (Exception ex) {
                ArrayList<MonitorExceptionSubscriber> subscribers = []
                subscribers.addAll(stateSubscribers)
                subscribers.addAll(valueSubscribers)
                subscribers.toSet().each {
                    it.takeMonitorException(ex)
                }
            }
        }
    }

    @ActiveMethod(blocking = true)
    private CarChargingState calcChargingState(WallboxValues v) {
        CarChargingState result
        switch (v) {
            case (v?.carState == Wallbox.CarState.IDLE):
                result = CarChargingState.NO_CAR
                break
            case (v?.carState == Wallbox.CarState.WAIT_CAR):
                result = CarChargingState.WAIT_CAR
                break
            case (!v.allowedToCharge
                    && v.forceState == Wallbox.ForceState.OFF
                    && v.carState == Wallbox.CarState.COMPLETE):
                result = CarChargingState.NOT_CHARGING
                break
            case (!v.allowedToCharge
                    && v.forceState in [Wallbox.ForceState.NEUTRAL, Wallbox.ForceState.ON]
                    && v.carState == Wallbox.CarState.COMPLETE):
                result = CarChargingState.CHARGE_REQUEST_0
                break
            case (v.allowedToCharge
                    && v.forceState in [Wallbox.ForceState.NEUTRAL, Wallbox.ForceState.ON]
                    && v.carState == Wallbox.CarState.COMPLETE):
                if (prevWbValues.carState == Wallbox.CarState.CHARGING) {
                    result = CarChargingState.FULLY_CHARGED
                } else {
                    result = CarChargingState.CHARGE_REQUEST_1
                }
                break
            case (v.allowedToCharge
                    && v.forceState in [Wallbox.ForceState.NEUTRAL, Wallbox.ForceState.ON]
                    && v.carState == Wallbox.CarState.CHARGING):
                if (prevWbState == CarChargingState.CHARGING) {
                    result = (v.energy < 3800) ? CarChargingState.FINISH_CHARGING : CarChargingState.CHARGING
                } else {
                    result = (v.energy < 3800) ? CarChargingState.STARTUP_CHARGING : CarChargingState.CHARGING
                }
                break
            case (v.allowedToCharge
                    && v.forceState == Wallbox.ForceState.OFF
                    && v.carState == Wallbox.CarState.CHARGING):
                result = CarChargingState.STOP_REQUEST_0
                break
            case (!v.allowedToCharge
                    && v.forceState == Wallbox.ForceState.OFF
                    && v.carState == Wallbox.CarState.CHARGING):
                result = CarChargingState.STOP_REQUEST_1
                break
            case (!v.allowedToCharge
                    && v.forceState == Wallbox.ForceState.OFF
                    && v.carState == Wallbox.CarState.COMPLETE):
                if (prevWbState == CarChargingState.FULLY_CHARGED) {
                    result = CarChargingState.STOPPED_FULL
                } else {
                    result = CarChargingState.NOT_CHARGING
                }
                break
            case (v.allowedToCharge
                    && v.forceState == Wallbox.ForceState.OFF
                    && v.carState == Wallbox.CarState.COMPLETE):
                result = CarChargingState.FULLY_CHARGED
                break
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
        values = wallbox.values
        def currentState = calcChargingState(values)
        [values: values, state: currentState]
    }

    @ActiveMethod(blocking = true)
    def startCharging() {
        wallbox.startCharging()
    }

    @ActiveMethod(blocking = true)
    def forceStartCharging() {
        wallbox.forceStartCharging()
    }

    @ActiveMethod(blocking = true)
    def stopCharging() {
//        print " -stop charging- "
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
        if (!executor) {
            executor = new PeriodicExecutor(readWallbox, cycle, timeUnit, initialDelay)
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

            @Override
            void takeMonitorException(Exception exception) {
                println exception
            }

            @Override
            void resumeAfterMonitorException() {
                println 'resume after exception'
            }
        }
        def WallboxValueSubscriber valueSubscriber = new WallboxValueSubscriber() {
            @Override
            void takeWallboxValues(WallboxValues values) {
                println values
            }

            @Override
            void takeMonitorException(Exception exception) {
                println exception
            }

            @Override
            void resumeAfterMonitorException() {
                println 'resume after exception'
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
