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
class PvChargeStrategy implements PowerValueSubscriber/*, WallboxValueSubscriber, WallboxStateSubscriber*/ {
    private PvChargeStrategyParams params = new PvChargeStrategyParams()
    private List<PowerValues> valueTrace = []

    private static PvChargeStrategy pvChargeStrategy

    static synchronized PvChargeStrategy getChargeStrategy() {
        if (!pvChargeStrategy) {
            pvChargeStrategy = new PvChargeStrategy()
        }
        pvChargeStrategy
    }

    private CarChargingManager carChargingManager

    @ActiveMethod
    void setChargingManager(CarChargingManager manager) {
        carChargingManager = manager
    }

    @ActiveMethod(blocking = true)
    void startStrategy(CarChargingManager manager) {
        println "start strategy"
        PowerMonitor.monitor.subscribe this
        carChargingManager = manager
    }

    @ActiveMethod
    void stopStrategy() {
        println 'stop strategy'
        PowerMonitor.monitor.unsubscribe this
        carChargingManager = null
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
     * The central power evaluation method
     */
    private evalPower() {
        def powerValues = valueTrace.first()
        def wb = WallboxMonitor.monitor.current
        WallboxValues wbValues = wb.values
        def requestedCurrent = wbValues.requestedCurrent
        CarChargingState carCargingState = wb.state
        def powerBalance = powerValues.powerSolar - powerValues.consumptionHome
        // more consumption as could be served by battery (negative balance) && loading car
        if (powerBalance < params.stopThreshold && wbValues.energy > 0) {
            println 'stop charging immediately'
            // are we currently loading car with more than minimal load current?
            if (wbValues.requestedCurrent > Wallbox.wallbox.minCurrent) {
                // check if reduction of load current would be sufficient
                def couldSave = 3 * 230 * (wbValues.requestedCurrent - Wallbox.wallbox.minCurrent)
                if (powerBalance + couldSave >= params.stopThreshold) {
                    // request minimal load current
                    sendSurplus(Wallbox.wallbox.minCurrent, requestedCurrent)
                } else {
                    sendNoSurplus()
                    resetHistory()
                }
            }
        } else if (valueTrace.size() >= params.toleranceStackSize) {
            // we have collected data for some time so we can work with rolling averages
            int meanPSun = ((int) (valueTrace.collect { it.powerSolar }.sum())).intdiv(valueTrace.size())
            int meanCHome = ((int) (valueTrace.collect { it.consumptionHome }.sum())).intdiv(valueTrace.size())
            println powerValues
            print "meanPSun: $meanPSun, meanCHome: $meanCHome, "
            // how much power should be used for car charging depending on house battery soc
            def availablePVBalance = powerToAmp(meanPSun - meanCHome)
            def availableFromBattery = powerToAmp(powerRamp(powerValues.socBattery))
            def isCarCharging = carCargingState == CarChargingState.CHARGING && wbValues.energy > 1000
            def carChargingAmps = powerToAmp(wbValues.energy)
            println "avPV: $availablePVBalance, avBatt: $availableFromBattery, " +
                    "charging: $isCarCharging, iCharge: $carChargingAmps"
            // different strategies if car is loading or not
            if (isCarCharging) {
                def amp4loading = requestedCurrent + availablePVBalance
//                def amp4loading = carChargingAmps + availablePVBalance
                if (amp4loading >= Wallbox.wallbox.minCurrent) {
                    println 'loading from PV only'
                    sendSurplus(amp4loading, requestedCurrent)
                } else if (amp4loading + availableFromBattery >= Wallbox.wallbox.minCurrent) {
                    println 'keep charging with battery support'
                    sendSurplus(Wallbox.wallbox.minCurrent, requestedCurrent)
                } else {
                    println 'stop charging'
                    sendNoSurplus()
                }
                resetHistory()
            } else {
                if (availablePVBalance >= Wallbox.wallbox.minCurrent) {
                    println 'start charging PV only (limited current)'
                    sendSurplus(Math.min(availablePVBalance, Wallbox.wallbox.maxStartCurrent), requestedCurrent)
                    resetHistory()
                } else if (availablePVBalance + availableFromBattery >= Wallbox.wallbox.minCurrent) {
                    println 'start charging with battery support'
                    sendSurplus(Wallbox.wallbox.minCurrent, requestedCurrent)
                    resetHistory()
                } else {
                    println 'no PV power for loading'
                    sendNoSurplus()
                }
            }
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
    private void sendSurplus(int amps, def actual) {
//        println "send carChargingManager.takeSurplus($amps)"
        if (amps != actual) {
            carChargingManager?.takeSurplus(amps)
        }
    }

    /**
     * request stoploading
     */
    private void sendNoSurplus() {
//        println "send carChargingManager.takeSurplus($amps)"
        carChargingManager?.takeSurplus(0)
    }

    static void main(String[] args) {
        PvChargeStrategy strategyActor = PvChargeStrategy.chargeStrategy
//        println "soc: 40 ${strategyActor.powerRamp(40)}"
//        println "soc: 50 ${strategyActor.powerRamp(50)}"
//        println "soc: 51 ${strategyActor.powerRamp(51)}"
//        println "soc: 60 ${strategyActor.powerRamp(60)}"
//        println "soc: 70 ${strategyActor.powerRamp(70)}"
//        println "soc: 79 ${strategyActor.powerRamp(79)}"
//        println "soc: 80 ${strategyActor.powerRamp(80)}"
//        println "soc: 81 ${strategyActor.powerRamp(81)}"
//        println "soc: 90 ${strategyActor.powerRamp(90)}"
//        println "soc: 95 ${strategyActor.powerRamp(95)}"
//        println "soc: 100 ${strategyActor.powerRamp(100)}"
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
