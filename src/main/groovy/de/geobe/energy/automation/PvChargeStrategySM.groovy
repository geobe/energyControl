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
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

import static de.geobe.energy.automation.WallboxMonitor.CarChargingState

/**
 * Responsibility:
 */
@ActiveObject
class PvChargeStrategySM implements PowerValueSubscriber, ChargeStrategy {
    private PvChargeStrategyParams params = new PvChargeStrategyParams()
    private List<PMValues> valueTrace = []

    static powerFactor = 3 * 230

    private static PvChargeStrategySM pvChargeStrategy

    static synchronized PvChargeStrategySM getChargeStrategy() {
        if (!pvChargeStrategy) {
            pvChargeStrategy = new PvChargeStrategySM()
        }
        pvChargeStrategy
    }

    static enum ChargingState {
        Inactive,
        NotCharging,
        StartUpCharging,
        ContinueCharging,
        TestAmpCarReduction,
        HasAmpCarReduction
    }

    static enum ChargingEvent {
        init,
        waitForAverage,
        continueCharging,
        startCharging,
        stopCharging,
        ampReduced,
        reduceToMin,
//        reduceImmediate
    }

    static enum ImmediateAction {
        None,
        Stop,
        Reduce
    }

    private ChargingState chargingState = ChargingState.Inactive

    private CarChargingManager carChargingManager

    private int lastAmpsSent = 0

//    @ActiveMethod
//    void setChargingManager(CarChargingManager manager) {
//        carChargingManager = manager
//    }

    @ActiveMethod(blocking = false)
    void startStrategy(CarChargingManager manager) {
        println "start strategy"
        PowerMonitor.monitor.subscribe this
        carChargingManager = manager
        chargingState = ChargingState.NotCharging
        sendNoSurplus()
    }

    @ActiveMethod
    void stopStrategy() {
        println 'stop strategy'
        PowerMonitor.monitor.unsubscribe this
        carChargingManager = null
        chargingState = ChargingState.Inactive
    }

    @ActiveMethod(blocking = false)
    void takePowerValues(PMValues pValues) {
//        println "new power values $pValues"
        if (valueTrace.size() >= params.toleranceStackSize) {
            valueTrace.removeLast()
        }
        valueTrace.push pValues
        evalPower()
    }

    @ActiveMethod(blocking = true)
    void setParams(PvChargeStrategyParams p) {
        params = new PvChargeStrategyParams(p)

    }

    /**
     * test for more consumption as could be served by PV and battery (negative balance) while loading car
     * @param balance PV production minus home consumption
     * @param requested charging current requested by wallbox
     * @param charging is car charging?
     * @return immediate action needed or none
     */
    private ImmediateAction checkReduceImmediate(int balance, int requested, boolean charging) {
        if (balance < params.stopThreshold && charging) {
            // are we currently loading car with more than minimal load current?
            if (requested > Wallbox.wallbox.minCurrent) {
                // check if reduction of load current would be sufficient
                def couldSave = 3 * 230 * (requested - Wallbox.wallbox.minCurrent)
                if (balance + couldSave >= params.stopThreshold) {
                    // request minimal load current
                    ImmediateAction.Reduce
                } else {
                    ImmediateAction.Stop
                }
            } else {
                ImmediateAction.Stop
            }
        } else {
            ImmediateAction.None
        }
    }

    /**
     * detect if loading is reduced or finished, i.e. car takes less or no energy though supplied
     * @return true if ended
     */
    private boolean isChargeReduced(int requestedCurrent, int usedEnergy) {
        powerToAmp(usedEnergy) <= requestedCurrent - 2
    }

    /**
     * The central power evaluation method
     */
    private evalPower() {
        print '.'
        def currentValues = valueTrace.first()
        def powerValues = currentValues.powerValues
        def requestedCurrent = currentValues.requestedCurrent
        CarChargingState carChargingState = currentValues.carState
        int availableChargingPower = powerValues.powerSolar - powerValues.consumptionHome + currentValues.wbEnergy
        def requestedChargingPower = requestedCurrent * powerFactor

        def isCarCharging = carChargingState == CarChargingState.CHARGING
        def powerBalance = availableChargingPower - requestedChargingPower

        ChargingEvent chargingEvent
        int availableCurrent
        int meanPSun = 0
        int meanCHome = 0

        // if powerBalance deeply negative, check if we have to stop immediately
        if (isCarCharging && powerBalance <= params.stopThreshold ) {
            def couldSave = (requestedCurrent - Wallbox.wallbox.minCurrent) * powerFactor
            // could we reduce current enough to keep on charging?
            if (couldSave && powerBalance + couldSave > params.stopThreshold ) {
                // reduce to minimal charging current
                chargingEvent = ChargingEvent.reduceToMin
            } else {
                // stop charging
                chargingEvent = ChargingEvent.stopCharging
            }
        } else if (valueTrace.size() < params.toleranceStackSize) {
            // collect more data for working with average values
            chargingEvent = ChargingEvent.waitForAverage
        } else {
            // calculate average values
            meanPSun = ((int) (valueTrace.collect {
                it.powerValues.powerSolar
            }.sum())).intdiv(valueTrace.size())
            meanCHome = ((int) (valueTrace.collect {
                it.powerValues.consumptionHome - it.wbEnergy
            }.sum())).intdiv(valueTrace.size())
            int chargeGradient = 0
            for (i in 1..<valueTrace.size()) {
                chargeGradient += valueTrace[i].wbEnergy - valueTrace[i-1].wbEnergy
            }
            // now work with rolling averages to ignore short time fluctuations
            availableChargingPower = meanPSun - meanCHome + powerRamp(powerValues.socBattery)
            availableCurrent = Math.floorDiv(availableChargingPower, powerFactor)
            if (availableCurrent < Wallbox.wallbox.minCurrent) {
                chargingEvent = ChargingEvent.stopCharging
            } else if (chargeGradient < 0) {
                chargingEvent = ChargingEvent.ampReduced
            } else {
                def startPower = availableChargingPower - params.batStartHysteresis
                if (Math.floorDiv(startPower, powerFactor) > Wallbox.wallbox.minCurrent) {
                    chargingEvent = ChargingEvent.startCharging
                } else {
                    chargingEvent = ChargingEvent.continueCharging
                }
            }
        }

        // tracing output
        def values = "sun: $powerValues.powerSolar, bat: $powerValues.socBattery%, batEnergy $batteryEnergy," +
                " car: $currentValues.wbEnergy, surplus: $availableChargingPower, req $requestedCurrent," +
                " avgSun: $meanPSun, avgHome: $meanCHome"
        def evTrace = " ChargeStrategy: $chargingState --$chargingEvent"
        int caseTrace = 0
        // now we can execute the internal state chart
        switch (chargingEvent) {
            case ChargingEvent.stopCharging:
            case ChargingEvent.init:
                caseTrace = 1
                sendNoSurplus()
                resetHistory()
                chargingState = ChargingState.NotCharging
                break
            case  ChargingEvent.reduceImmediate:
                caseTrace = 2
                sendSurplus(Wallbox.wallbox.minCurrent)
                resetHistory()
                chargingState = ChargingState.StartUpCharging
                break
            case ChargingEvent.waitForAverage:
                caseTrace = 3
                break
            case ChargingEvent.ampReduced:
                switch (chargingState) {
                    case ChargingState.NotCharging:
                        break
                    case ChargingState.StartUpCharging:
                    case ChargingState.ContinueCharging:
                        caseTrace = 5
                        sendSurplus(Wallbox.wallbox.minCurrent)
                        resetHistory()
                        chargingState = ChargingState.TestAmpCarReduction
                        break
                    case ChargingState.TestAmpCarReduction:
                        caseTrace = 6
                        sendSurplus(Wallbox.wallbox.minCurrent)
                        resetHistory()
                        chargingState = ChargingState.HasAmpCarReduction
                        break
                    case ChargingState.HasAmpCarReduction:
                        if (wbValues.energy == 0) {
                            caseTrace = 701
                            chargingState = ChargingState.NotCharging
                            resetHistory()
                            sendNoSurplus()
//                            sendFullyCharged()
                        } else {
                            caseTrace = 702
                            sendSurplus(Wallbox.wallbox.minCurrent)
                            resetHistory()
                            chargingState = ChargingState.HasAmpCarReduction
                        }
                        break
                }
                break
            case ChargingEvent.startCharging:
                if (chargingState == ChargingState.NotCharging) {
                        caseTrace = 801
                        // always start with minimal current
                        sendSurplus(Wallbox.wallbox.minCurrent)
                        resetHistory()
                        chargingState = ChargingState.StartUpCharging
                        break
                }
            case ChargingEvent.continueCharging:
                switch (chargingState) {
                    case ChargingState.ContinueCharging:
                    case ChargingState.TestAmpCarReduction:
                    case ChargingState.HasAmpCarReduction:
                        caseTrace = 802
                        sendSurplus(availableCurrent)
                        resetHistory()
                        chargingState = ChargingState.ContinueCharging
                        break
                }
                break
        }
        if (chargingEvent != ChargingEvent.waitForAverage || chargingState == ChargingState.NotCharging) {
            println "$values"
            println "$evTrace($caseTrace)--> $chargingState\n"
        }
    }

    private int powerToAmp(int power, int phases = 3) {
        power.intdiv(phases * 230)
    }

    /**
     *  calculate power offset for car loading depending on battery state of continueCharging (soc).<br>
     *  I.e. which power must be subtracted from available PV power to load house battery when soc is low or <br>
     *  which additional power may be taken from house battery when soc is above socmax
     *
     * @param soc current state of continueCharging
     * @return power offset for current soc, maybe positive or negative
     */
    private Integer powerRamp(int soc) {
        if (soc < params.minChargeUseBat) {
            return -params.batPower
        } else if (soc >= params.fullChargeUseBat) {
            float fac = (soc - params.fullChargeUseBat) / (100 - params.fullChargeUseBat)
            return params.minBatUnloadPower + fac * (params.batPower - params.minBatUnloadPower)
        } else if (soc < params.fullChargeUseBat) {   // && soc >= params.minUsePower
            float fac = (soc - params.minChargeUseBat) / (params.fullChargeUseBat - params.minChargeUseBat)
            return - params.batPower + fac * (params.batPower - params.minBatUnloadPower)
        }
    }

    /**
     * reset valueTrace stack to restart evaluation of solar production and house consumption
     */
    private resetHistory() {
        valueTrace.clear()
    }

    /**
     * request loading with amp ampere
     */
    private void sendSurplus(int amps) {
        if (amps != lastAmpsSent) {
            carChargingManager?.takeChargingCurrent(amps)
            lastAmpsSent = amps
        }
    }

    /**
     * request stoploading
     */
    private void sendNoSurplus() {
        carChargingManager?.takeChargingCurrent(0)
        lastAmpsSent = 0
    }

    /**
     * inform that charging stopped (car battery fully charged)
     */
    private void sendFullyCharged() {
        carChargingManager?.takeFullyCharged()
    }

    static void main(String[] args) {
        PvChargeStrategySM strategyActor = PvChargeStrategySM.chargeStrategy
        strategyActor.startStrategy()
        Thread.sleep(180000)
        strategyActor.stopStrategy()
        Thread.sleep(10000)
        strategyActor.startStrategy()
        Thread.sleep(10000)
        strategyActor.stopStrategy()
        PowerMonitor.monitor.shutdown()
    }
}

