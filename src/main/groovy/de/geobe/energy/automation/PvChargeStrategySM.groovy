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

package de.geobe.energy.automation

import de.geobe.energy.e3dc.PowerValues
import de.geobe.energy.go_e.Wallbox
import de.geobe.energy.go_e.WallboxValues
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

import static de.geobe.energy.automation.WallboxMonitor.CarChargingState

/**
 * Responsibility:
 */
@ActiveObject
class PvChargeStrategySM implements PowerValueSubscriber, ChargeStrategy {
    private PvChargeStrategyParams params = new PvChargeStrategyParams()
    private List<PowerValues> valueTrace = []

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
        checkSurplus,
        noSurplus,
        ampReduced,
        reduceImmediate
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
    void takePowerValues(PowerValues pValues) {
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
        def powerValues = valueTrace.first()
        def wb = WallboxMonitor.monitor.current
        WallboxValues wbValues = wb.values
        def requestedCurrent = wbValues.requestedCurrent
        CarChargingState carChargingState = wb.state
        def powerBalance = powerValues.powerSolar - powerValues.consumptionHome
        def isCarCharging = carChargingState == CarChargingState.CHARGING && wbValues.energy > 1000
        ChargingEvent chargingEvent = ChargingEvent.noSurplus

        switch (checkReduceImmediate(powerBalance, requestedCurrent, isCarCharging)) {
            case ImmediateAction.Stop:
                chargingEvent = ChargingEvent.noSurplus
                break
            case ImmediateAction.Reduce:
                chargingEvent = ChargingEvent.reduceImmediate
                break
            case ImmediateAction.None:
                if (valueTrace.size() >= params.toleranceStackSize) {
                    if (isChargeReduced(requestedCurrent, wbValues.energy)) {
                        chargingEvent = ChargingEvent.ampReduced
                    } else {
                        chargingEvent = ChargingEvent.checkSurplus
                    }
                } else {
                    chargingEvent = ChargingEvent.waitForAverage
                }
        }
        // we work with rolling averages
        int meanPSun, meanCHome, surplusPVEnergy, surplusPVAmps, availableFromBattery, amp4loading, batteryEnergy
        if (chargingEvent in [ChargingEvent.checkSurplus, ChargingEvent.ampReduced]) {
            meanPSun = ((int) (valueTrace.collect { it.powerSolar }.sum())).intdiv(valueTrace.size())
            meanCHome = ((int) (valueTrace.collect { it.consumptionHome }.sum())).intdiv(valueTrace.size())
            // how much power should be used for car charging depending on house battery soc
            if (chargingState == ChargingState.NotCharging) {
                //use latest home consumption value
                surplusPVEnergy = meanPSun - meanCHome
//                surplusPVAmps = powerToAmp(meanPSun - powerValues.consumptionHome)
            } else if (chargingState == ChargingState.StartUpCharging) {
                // consider charging power contained in home consumption
//                surplusPVAmps = powerToAmp(meanPSun - (powerValues.consumptionHome - wbValues.energy))
                surplusPVEnergy = meanPSun - (powerValues.consumptionHome - wbValues.energy)
            } else {
                //use average home consumption value
//                surplusPVAmps = powerToAmp(meanPSun - meanCHome)
                surplusPVEnergy = meanPSun - meanCHome
            }
            batteryEnergy = powerRamp(powerValues.socBattery)
            surplusPVAmps = powerToAmp(surplusPVEnergy )
            availableFromBattery = powerToAmp(batteryEnergy)
            amp4loading = Math.min(requestedCurrent + surplusPVAmps, Wallbox.wallbox.maxCurrent)
        }

        // tracing output
        def values = "sun: $powerValues.powerSolar, bat: $powerValues.socBattery%, batEnergy $batteryEnergy, car: $wbValues.energy " +
                "balance $powerBalance, req $requestedCurrent, avgSun $meanPSun, plusEnergy $surplusPVEnergy "
        def evTrace = " ChargeStrategy: $chargingState --$chargingEvent"
        int caseTrace = 0
        // now we can execute the internal state chart
        switch (chargingEvent) {
            case ChargingEvent.noSurplus:
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
                    case ChargingState.StartUpCharging:
                        if (surplusPVAmps >= Wallbox.wallbox.minCurrent) {
                            caseTrace = 401
                            sendSurplus(Wallbox.wallbox.minCurrent)
                            chargingState = ChargingState.StartUpCharging
                        } else {
                            caseTrace = 402
                            sendNoSurplus()
                            chargingState = ChargingState.NotCharging
                        }
                        resetHistory()
                        break
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
            case ChargingEvent.checkSurplus:
                switch (chargingState) {
                    case ChargingState.NotCharging:
                        def startAmps = surplusPVAmps + availableFromBattery
                        // some amp hysteresis to avoid erratic on/off/on/off
                        if (startAmps - params.batStartHysteresis >= Wallbox.wallbox.minCurrent) {
                            caseTrace = 801
                            // always start with minimal current
                            sendSurplus(Wallbox.wallbox.minCurrent)
                            resetHistory()
                            chargingState = ChargingState.StartUpCharging
                        } else {
                            caseTrace = 802
                            // just in case wallbox is just slowly starting to charge
                            if (wbValues.energy > 0) {
                                caseTrace = 803
                                sendNoSurplus()
                            }
                            chargingState = ChargingState.NotCharging
                        }
                        break
                    case ChargingState.StartUpCharging:
                    case ChargingState.ContinueCharging:
                    case ChargingState.TestAmpCarReduction:
                    case ChargingState.HasAmpCarReduction:
                        if (amp4loading >= Wallbox.wallbox.minCurrent) {
                            //PV only
                            caseTrace = 901
                            sendSurplus(amp4loading)
                            chargingState = ChargingState.ContinueCharging
                        } else if (amp4loading + availableFromBattery >= Wallbox.wallbox.minCurrent) {
                            caseTrace = 902
                            // pv + battery
                            sendSurplus(Wallbox.wallbox.minCurrent)
                            chargingState = ChargingState.ContinueCharging
                        } else {
                            caseTrace = 903
                            // stop charging
                            sendNoSurplus()
                            chargingState = ChargingState.NotCharging
                        }
                        resetHistory()
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
     *  calculate power offset for car loading depending on battery state of charge (soc).<br>
     *  I.e. which power must be subtracted from available PV power to load house battery when soc is low or <br>
     *  which additional power may be taken from house battery when soc is above socmax
     *
     * @param soc current state of charge
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

