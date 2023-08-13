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
class PvChargeStrategy implements PowerValueSubscriber, ChargeStrategy {
    private PvChargeStrategyParams params = new PvChargeStrategyParams()
    private List<PMValues> valueTrace = []

    static powerFactor = 3 * 230

    private static PvChargeStrategy pvChargeStrategy

    static synchronized PvChargeStrategy getChargeStrategy() {
        if (!pvChargeStrategy) {
            pvChargeStrategy = new PvChargeStrategy()
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
    private int skipCount = 0

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
    void takePMValues(PMValues pmValues) {
//        println "new power values $pValues"
        if (valueTrace.size() >= params.toleranceStackSize) {
            valueTrace.removeLast()
        }
        valueTrace.push (pmValues)
        evalPower()
    }

    @ActiveMethod(blocking = true)
    void setParams(PvChargeStrategyParams p) {
        params = new PvChargeStrategyParams(p)

    }

    PvChargeStrategyParams getParams() {
        params
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
//        println currentValues
        def powerValues = currentValues.powerValues
        def wallboxValues = currentValues.wallboxValues
        def requestedCurrent = wallboxValues.requestedCurrent
        Wallbox.CarState carState = wallboxValues.carState
        int availableChargingPower = powerValues.powerSolar - powerValues.consumptionHome + wallboxValues.energy
        def requestedChargingPower = requestedCurrent * powerFactor

        def isCarCharging = carState == CarChargingState.CHARGING
        def powerBalance = availableChargingPower - requestedChargingPower

        ChargingEvent chargingEvent
        int availableCurrent
        int meanPSun = 0
        int meanCHome = 0
        int batBalance = 0
        int chargeGradient = 0

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
                it.powerValues.consumptionHome - it.wallboxValues.energy
            }.sum())).intdiv(valueTrace.size())
            chargeGradient = 0
            for (i in 1..<valueTrace.size()) {
                chargeGradient += valueTrace[i].wallboxValues.energy - valueTrace[i-1].wallboxValues.energy
            }
            // now work with rolling averages to ignore short time fluctuations
            batBalance = powerRamp(powerValues.socBattery)
            availableChargingPower = meanPSun - meanCHome + batBalance
            availableCurrent = Math.floorDiv(availableChargingPower, powerFactor)
            if (availableCurrent < Wallbox.wallbox.minCurrent) {
                chargingEvent = ChargingEvent.stopCharging
            } else if (isCarCharging && chargeGradient < 500 && wallboxValues.energy < requestedChargingPower - 500) {
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
        def stateBefore = chargingState
        def values = "sun: $powerValues.powerSolar, soc: $powerValues.socBattery%, batEnergy $powerValues.powerBattery," +
                " car: $wallboxValues.energy, req $requestedCurrent, gradient: $chargeGradient" +
                ", surplus: $availableChargingPower, avgSun: $meanPSun, avgHome: $meanCHome, bat: $batBalance"
        def evTrace = " ChargeStrategy: $chargingState --$chargingEvent"
        int caseTrace = 0
        // now we can execute the internal state chart
        switch (chargingEvent) {
            case ChargingEvent.stopCharging:
                switch (chargingState) {
                    case ChargingState.NotCharging:
                        caseTrace = 101
                        break
                    default:
                        caseTrace = 102
                        sendNoSurplus()
                        resetHistory()
                        chargingState = ChargingState.NotCharging
                }
                break
            case  ChargingEvent.reduceToMin:
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
                        caseTrace = 4
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
                switch (chargingState) {
                    case ChargingState.NotCharging:
                        caseTrace = 801
                        // always start with minimal current
                        sendSurplus(Wallbox.wallbox.minCurrent)
                        resetHistory()
                        chargingState = ChargingState.StartUpCharging
                        break
                    case ChargingState.StartUpCharging:
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
            case ChargingEvent.continueCharging:
                switch (chargingState) {
                    case ChargingState.ContinueCharging:
                    case ChargingState.TestAmpCarReduction:
                    case ChargingState.HasAmpCarReduction:
                    case ChargingState.StartUpCharging:
                        caseTrace = 901
                        sendSurplus(availableCurrent)
                        resetHistory()
                        chargingState = ChargingState.ContinueCharging
                        break
                    case ChargingState.NotCharging:
                        caseTrace = 902
                        // not enough power to start charging
                        break
                }
                break
        }
//        skipCount++
//        if ((skipCount >= 12 && chargingEvent != ChargingEvent.waitForAverage) ||
//                chargingState != stateBefore ||
//                (availableCurrent && lastAmpsSent && availableCurrent != lastAmpsSent)) {
//            println "$values"
//            println "--> skip: $skipCount, stateBefore: $stateBefore, availableCurrent: $availableCurrent, lastAmpsSent: $lastAmpsSent"
//            println "$evTrace($caseTrace)--> $chargingState\n"
//            skipCount = 0
//        }
    }

    private int powerToAmp(int power, int phases = 3) {
        power.intdiv(phases * 230)
    }

    /**
     *  calculate power offset for car loading depending on house battery state of charge (soc).<br>
     *  I.e. which power must be subtracted from available PV power to load house battery when soc is low or <br>
     *  which additional power may be taken from house battery when soc is above socmax
     *
     * @param soc current state of house battery state of charge
     * @return power offset for current soc, maybe positive or negative
     */
    private Integer powerRamp(int soc) {
        if (soc < params.minChargeUseBat) {
            // charge with maximal power
            return -params.batPower
        } else if (soc >= params.fullChargeUseBat) {
            // allow discharging house battery in fevour of car
            float fac = (soc - params.fullChargeUseBat) / (100 - params.fullChargeUseBat)
            return params.minBatUnloadPower + fac * (params.maxBatUnloadPower - params.minBatUnloadPower)
        } else if (soc < params.fullChargeUseBat) {   // && soc >= params.minUsePower
            float fac = (soc - params.minChargeUseBat) / (params.fullChargeUseBat - params.minChargeUseBat)
            return - params.batPower + fac * (params.batPower - params.minBatLoadPower)
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
        PvChargeStrategy strategyActor = PvChargeStrategy.chargeStrategy
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

