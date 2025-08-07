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
 * This is a monitor class that controls details of charging when charging is set to PV surplus mode.<br>
 * Responsibility: Monitor available power from PV and power storage.
 * For this purpose, get actual realtime power values and wallbox values (PMValues).
 * To avoid too frequent switching build an average over several subsequent values.
 * Goal is to charge car with PV and possibly storage power only and to feed as little power into
 * the grid as possible.
 * Difference between supplied power from PV and storage minus used power by house and car is called power balance.
 * Evaluate values to identify following cases:
 * <ul>
 *     <li>on deeply negative power balance, instantly check if to stop charging
 *         or proceed with minimal charging power</li>
 *     <li>based on average values
 *     <ul>
 *         <li>stop charging if power balance gets negative</li>
 *         <li>if charging and available power is dropping quickly, set charging to minimum</li>
 *         <li>start charging with minimum, if available power is somewhat higher than minimum </li>
 *         <li>if charging and sufficient power available, set optimal charging current</li>
 *     </ul>
 *     </li>
 * </ul>
 * Based on these evaluations, send events surplus(v) and noSurplus to CarChargingManager
 */
@ActiveObject
class PvChargeStrategy implements ChargeStrategy {
    private PowerStrategyParams params = new PowerStrategyParams()
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
        NotCharging,
        Charging,
    }

    static enum ChargingEvent {
        startCharging,
        continueCharging,
        reduceToMin,
        stopCharging,
    }

    private boolean isCharging = false

    private CarChargingManager carChargingManager

    private boolean strategyActive = false
    private int chargeGradientBase = params.chargeGradientCount

    def getState() {
        isCharging.toString()
    }

    @ActiveMethod(blocking = false)
    void enableStrategy(CarChargingManager manager) {
        // println "PV Charge Strategy enabled"
//        PowerMonitor.monitor.subscribe this
        carChargingManager = manager
    }

    @ActiveMethod(blocking = false)
    void activateStrategy() {
        // println "PV Charge Strategy  active"
        strategyActive = true
    }

    @ActiveMethod
    void deactivateStrategy() {
        // println 'deactivate PV Charge Strategy'
        strategyActive = false
    }

    CarChargingManager.EventMessage evalPMValues(PMValues pmValues) {
        if (valueTrace.size() >= params.toleranceStackSize) {
            valueTrace.removeLast()
        }
        valueTrace.push(pmValues)
        if (strategyActive) {
            evalPower()
        } else {
            new CarChargingManager.EventMessage()
        }
    }

    @ActiveMethod(blocking = true)
    void setParams(PowerStrategyParams p) {
        params = new PowerStrategyParams(p)

    }

    PowerStrategyParams getParams() {
        params
    }

    /**
     * The central power evaluation method
     */
    private CarChargingManager.EventMessage evalPower() {
        def currentValues = valueTrace.first()
        def powerValues = currentValues.powerValues
        def wallboxValues = currentValues.wallboxValues
        CarChargingState chargingState = currentValues.chargingState
        def requestedCurrent = wallboxValues.requestedCurrent
        Wallbox.CarState carState = wallboxValues.carState
        int availableChargingPower = powerValues.powerSolar - powerValues.consumptionHome + wallboxValues.energy
        def requestedChargingPower = requestedCurrent * powerFactor

        def isCarCharging = chargingState in [CarChargingState.CHARGE_REQUEST,
                                              CarChargingState.STARTUP_CHARGING,
                                              CarChargingState.CHARGING,
                                              CarChargingState.FINISH_CHARGING]
        def powerBalance = availableChargingPower - requestedChargingPower

        ChargingEvent chargingEvent
        int availableCurrent
        int meanPSun = 0
        int meanCHome = 0
        int batBalance = 0
        int chargeGradient = 0
        boolean powerBalanceValid = false
//        int chargeMax = 0
//        boolean logMayCharge = true

        // powerValues and wallboxValues are read from different hardware sources in this sequence.
        // consumptionHome is calculated as a difference of values coming from these
        // asynchronous data sets. As a consequence, consumptionHome can show artificial swings if
        // wallboxValues.energy changes significantly between two readings. In this case, powerBalance
        // is no usable value.
        if (valueTrace.size() > 1 ) {
            def deltaCharging = Math.abs(wallboxValues.energy - valueTrace[1].wallboxValues.energy)
            powerBalanceValid = deltaCharging < powerFactor
        } else {
            powerBalanceValid = false
        }
        // if powerBalance valid and deeply negative, check if we have to stop immediately
        if (isCarCharging && powerBalanceValid && powerBalance <= params.stopThreshold) {
            def couldSave = (requestedCurrent - Wallbox.wallbox.minCurrent) * powerFactor
            // could we reduce current enough to keep on charging?
            if (couldSave && powerBalance + couldSave > params.stopThreshold) {
                // reduce to minimal charging current
                // println "PvChargeStrategy@1 requested current $requestedCurrent, could save $couldSave"
                chargingEvent = ChargingEvent.reduceToMin
            } else {
                // stop charging
                chargingEvent = ChargingEvent.stopCharging
            }
        } else if (valueTrace.size() >= params.toleranceStackSize) {
            // enough data to work with averages
            // calculate average values
            meanPSun = ((int) (valueTrace.collect {
                it.powerValues?.powerSolar
            }.sum()))
                    .intdiv(valueTrace.size())
            meanCHome = ((int) (valueTrace.collect {
                it.powerValues.consumptionHome - it.wallboxValues.energy
            }.sum()))
                    .intdiv(valueTrace.size())
            if (chargeGradientBase <= valueTrace.size()) {
                chargeGradient =
                        valueTrace[0].wallboxValues.energy - valueTrace[chargeGradientBase].wallboxValues.energy
            } else {
                chargeGradient = 0
            }
            // now work with rolling averages to ignore short time fluctuations
            batBalance = powerRamp(powerValues.socBattery)
            availableChargingPower = meanPSun - meanCHome + batBalance
            availableCurrent = Math.floorDiv(availableChargingPower, powerFactor)

            // determine average based charging event
            if (availableCurrent < Wallbox.wallbox.minCurrent) {
                // not enough pv power
                // println "PvChargeStrategy@2 requested current $requestedCurrent, available $availableCurrent"
                chargingEvent = ChargingEvent.stopCharging
//            } else if (isCarCharging && chargeGradient < -1000) {
//                // pv power drops quickly e.g. due to cloud
//                chargingEvent = ChargingEvent.reduceToMin
            } else {
                // enough power to charge
                if (isCarCharging) {
                    chargingEvent = ChargingEvent.continueCharging
                } else {
                    // but start charging only with a little reserve
                    def startPower = availableChargingPower - params.batStartHysteresis
                    if (Math.floorDiv(startPower, powerFactor) > Wallbox.wallbox.minCurrent) {
                        // println "PvChargeStrategy@2a requested current $requestedCurrent, available $availableCurrent"
                        chargingEvent = ChargingEvent.startCharging
                    } else {
                        chargingEvent = ChargingEvent.stopCharging
                    }
                }
            }
        }
        // now we can execute the internal state chart
        switch (chargingEvent) {
            case ChargingEvent.stopCharging:
                if (isCarCharging) {
                    isCharging = false
                    return sendNoSurplus()
                }
                break
            case ChargingEvent.reduceToMin:
                isCharging = true
                // println "PvChargeStrategy@3 reduceToMin"
                return sendSurplus(Wallbox.wallbox.minCurrent)
                break
            case ChargingEvent.startCharging:
                def result
                isCharging = true
                if (chargingState == CarChargingState.CHARGING) {
                    // startup done
                    return sendSurplus(availableCurrent)
                } else {
                    // always start with minimal current
                    // println "PvChargeStrategy@4 startup with min"
                    result = sendSurplus(Wallbox.wallbox.minCurrent,true)
                }
                return result
                break
            case ChargingEvent.continueCharging:
                isCharging = true
                // println "PvChargeStrategy@5 available $availableCurrent"
                return sendSurplus(availableCurrent)
                break
        }
        new CarChargingManager.EventMessage()
    }

    /**
     *  calculate power offset for car charging depending on house battery state of charge (soc).<br>
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
            return -params.batPower + fac * (params.batPower - params.minBatLoadPower)
        }
    }

    /**
     * reset valueTrace stack to restart evaluation of solar production and house consumption
     */
    private resetHistory() {
        valueTrace.clear()
    }

    /**
     * request charging with amp ampere
     */
    private CarChargingManager.EventMessage sendSurplus(int amps, boolean sendAnyway = false) {
        if (amps != carChargingManager.lastAmpsSent || sendAnyway) {
            new CarChargingManager.EventMessage(CarChargingManager.ChargeEvent.Surplus, amps)
        } else {
            new CarChargingManager.EventMessage()
        }
    }

    /**
     * request stop charging
     */
    private CarChargingManager.EventMessage sendNoSurplus() {
        new CarChargingManager.EventMessage(CarChargingManager.ChargeEvent.NoSurplus)
    }

    /**
     * inform that charging stopped (car battery fully charged)
     */
    private CarChargingManager.EventMessage sendFullyCharged() {
        new CarChargingManager.EventMessage(CarChargingManager.ChargeEvent.FullyCharged)
    }
}

