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
    private volatile int minimalSoc
    private volatile DateTime currentTimeout
    private int chargeMax
    private int gridFeedLimit
    private boolean isRunning = false
    PowerValues powerValues
    def e3dc = E3dcInteractionRunner.interactionRunner
    PowerMonitor powerMonitor = PowerMonitor.monitor
    CtlState controlState = CtlState.Auto
    CtlState historyState = CtlState.None

    private Runnable repeatSet = new Runnable() {
        @Override
        void run() {
            if (e3dcMode != E3dcInteractionRunner.AUTO) {
                e3dc.storageLoadMode(e3dcMode, watts)
            }
            if (DateTime.now().isAfter(currentTimeout)) {
                stopChargeControl()
            }
        }
    }

    E3dcChargingModeController( int gridfeed = 300, int maxChargeLimit = 98) {
        gridFeedLimit = gridfeed
        chargeMax = maxChargeLimit
        executor = new PeriodicExecutor(repeatSet, ticTime, ticUnit)
        stopChargeControl()
    }

    @Override
    void takePMValues(PMValues pmValues) {
        powerValues = pmValues.powerValues
        // implement realtime events derived from power values
        if(controlState == CtlState.Auto && powerValues.socBattery <= minimalSoc) {
            setIdleState()
        } else if(controlState == CtlState.GridLoad && powerValues.socBattery >= chargeMax) {
            setIdleState()
        } else if(controlState in [CtlState.GridLoad, CtlState.Idle] && powerValues.powerGrid <= gridFeedLimit) {
            setSolarState()
        } else if(controlState == CtlState.Solar && powerValues.powerGrid > 0) {
            if (historyState == CtlState.GridLoad) {
                setGridLoadState()
            } else if (historyState == CtlState.Idle) {
                setIdleState()
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
        isRunning = false
        executor.stop()
        powerMonitor.unsubscribe(this)
        e3dc.storageLoadMode(E3dcInteractionRunner.AUTO, 0)
        controlState = CtlState.Stopped
    }

    /**
     * implement setChargingMode event to (re-)enter Running state,
     * start realtime control of E3DC storage
     * @param mode E3dc storage control byte
     * @param watts charging or supplying power
     * @param minSoc minimal soc for current time slot
     * @param timeout timeout instant of this run mode
     */
    void setChargingMode(byte mode, int watts, int minSoc, DateTime timeout) {
        if(!isRunning) {
            // is it an entry of running mode?
            executor.start()
            powerMonitor.subscribe(this)
            isRunning = true
        }
        e3dcMode = mode
        inOutPower = watts
        minimalSoc = minSoc
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
            default:
                stopChargeControl()
        }
    }

    private setAutoState() {
        controlState = CtlState.Auto
    }

    private setGridLoadState() {
        controlState = CtlState.GridLoad
        historyState = CtlState.GridLoad
    }
    private setIdleState() {
        controlState = CtlState.Idle
        historyState = CtlState.Idle
    }
    private setSolarState() {
        controlState = CtlState.Solar
    }
}
