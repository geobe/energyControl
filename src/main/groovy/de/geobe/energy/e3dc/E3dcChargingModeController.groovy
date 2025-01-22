/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2023. Georg Beier. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
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

package de.geobe.energy.e3dc

import de.geobe.energy.automation.PMValues
import de.geobe.energy.automation.PeriodicExecutor
import de.geobe.energy.automation.PowerMonitor
import de.geobe.energy.automation.PowerValueSubscriber
import de.geobe.energy.recording.LogMessageRecorder
import de.geobe.energy.recording.PowerCommunicationRecorder
import org.joda.time.DateTime

import java.util.concurrent.TimeUnit

/**
 * Set E3DC storage unit to a charging mode and keep it in this mode by repeating set command
 */
class E3dcChargingModeController implements PowerValueSubscriber {
    static final long ticTime = 20
    static final TimeUnit ticUnit = TimeUnit.SECONDS

    static enum CtlState {
        Stopped,
        Auto,
        GridLoad,
        Idle,
        Solar,
        None
    }

    private PeriodicExecutor executor
    private volatile byte e3dcMode
    private volatile int inOutPower
    private volatile int socBlackoutReserve
    private volatile DateTime currentTimeout
    private int chargeMax
    private int gridFeedLimit
    private boolean isRunning = false
//    private int triggerFactor
//    private int triggerCount = 0
    PowerValues powerValues
    def e3dc = E3dcInteractionRunner.interactionRunner
    def logRecorder = LogMessageRecorder.recorder
    PowerMonitor powerMonitor = PowerMonitor.monitor
    CtlState controlState = CtlState.Auto
    CtlState historyState = CtlState.None

//    private Runnable repeatSet = new Runnable() {
//        @Override
//        void run() {
//            execChargingModeControl()
//        }
//    }

    E3dcChargingModeController(int gridfeed = 300, int maxChargeLimit = 98) {
        gridFeedLimit = gridfeed
        chargeMax = maxChargeLimit
        stopChargeControl()
    }

    @Override
    void takePMValues(PMValues pmValues) {
        powerValues = pmValues.powerValues
        // implement realtime events derived from power values
        if (controlState == CtlState.Auto && powerValues.socBattery < socBlackoutReserve) {
            setIdleState()
            logRecorder.logMessage "$controlState -> set idle to hold emergency reserve"
        } else if (controlState == CtlState.GridLoad && powerValues.socBattery >= chargeMax) {
            setIdleState()
            logRecorder.logMessage "$controlState -> set idle, soc >= chargeMax"
        } else if (controlState in [CtlState.GridLoad, CtlState.Idle] && powerValues.powerGrid <= -gridFeedLimit) {
            setSolarState()
            logRecorder.logMessage "$controlState -> set solar, don't feed grid"
        } else if (controlState == CtlState.Solar && powerValues.powerGrid > 0) {
            if (historyState == CtlState.GridLoad) {
                setGridLoadState()
                logRecorder.logMessage "history: $historyState; $controlState -> set gridload"
            } else if (historyState == CtlState.Idle) {
                setIdleState()
                logRecorder.logMessage "history: $historyState; $controlState -> set idle"
            } else {
                // should never happen, disable realtime control
                stopChargeControl()
            }
            historyState = CtlState.None
        }
    }

    @Override
    void takeMonitorException(Exception exception) {
        stopChargeControl()
    }

    @Override
    void resumeAfterMonitorException() {
        // don't resume at this level of control
    }
    /**
     * implement stopped state
     * stop realtime control activities and set E3DC storage to default (auto mode)
     */
    void stopChargeControl() {
        setAutoState()
        isRunning = false
//        executor.stop()
        powerMonitor.unsubscribe(this)
        PowerCommunicationRecorder.recorder.logMessage("chargingModeController unsubscribed")
        logRecorder.logMessage("chargingModeController unsubscribed")
//        e3dc.storageLoadMode(E3dcInteractionRunner.AUTO, 0)
        controlState = CtlState.Stopped
    }

    /**
     * implement setChargingMode event to (re-)enter Running state,
     * start realtime control of E3DC storage
     * @param mode E3dc storage control byte
     * @param watts charging or supplying power
     * @param targetSoc target soc for current time slot
     * @param timeout timeout instant of this run mode
     */
    void setChargingMode(byte mode, int watts, int targetSoc, DateTime timeout) {
        if (!isRunning) {
            // is it an entry of running mode?
//            executor.start()
            powerMonitor.subscribe(this)
            isRunning = true
//            println "chargingModeController subscribed"
            PowerCommunicationRecorder.recorder.logMessage("chargingModeController subscribed")
            logRecorder.logMessage("chargingModeController subscribed")
        }
        e3dcMode = mode
        inOutPower = watts
        chargeMax = targetSoc
        currentTimeout = timeout
        // select simple target state
        switch (mode) {
            case E3dcInteractionRunner.AUTO:
                setAutoState()
                break
            case E3dcInteractionRunner.GRIDLOAD:
                setGridLoadState()
                break
            case E3dcInteractionRunner.IDLE:
                setIdleState()
                break
            case E3dcInteractionRunner.LOAD:
                setSolarState()
                break
            default:
                stopChargeControl()
        }
    }

    void setSocBlackoutReserve(int socReserve) {
        socBlackoutReserve = socReserve
    }

    private setAutoState() {
        controlState = CtlState.Auto
        e3dc.setStorageMode(E3dcInteractionRunner.AUTO, 0)
    }

    private setGridLoadState() {
        controlState = CtlState.GridLoad
        historyState = CtlState.GridLoad
        e3dc.setStorageMode(E3dcInteractionRunner.GRIDLOAD, 3000)
    }

    private setIdleState() {
        controlState = CtlState.Idle
        historyState = CtlState.Idle
        e3dc.setStorageMode(E3dcInteractionRunner.IDLE, 0)
    }

    private setSolarState() {
        controlState = CtlState.Solar
        e3dc.setStorageMode(E3dcInteractionRunner.LOAD, 2000)
    }
}
