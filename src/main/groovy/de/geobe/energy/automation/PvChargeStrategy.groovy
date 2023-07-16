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

    static enum ChargingState {
        NotCharging,
        StartUpCharging,
        Charging,
        TestAmpCarReduction,
        HasAmpCarReduction
    }

    private ChargingState chargingState = ChargingState.NotCharging

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
        print '.'
        def powerValues = valueTrace.first()
        def wb = WallboxMonitor.monitor.current
        WallboxValues wbValues = wb.values
        def requestedCurrent = wbValues.requestedCurrent
        CarChargingState carCargingState = wb.state
        def powerBalance = powerValues.powerSolar - powerValues.consumptionHome
        def isCarCharging = carCargingState == CarChargingState.CHARGING && wbValues.energy > 1000
        // more consumption as could be served by battery (negative balance) && loading car
        if (powerBalance < params.stopThreshold && isCarCharging) {
            println "stop or reduce charging immediately, powerBalence: $powerBalance"
            println "PV: $powerValues.powerSolar, bat: $powerValues.powerBattery, grid: $powerValues.powerGrid," +
                    " home: $powerValues.consumptionHome, soc: $powerValues.socBattery "
            // are we currently loading car with more than minimal load current?
            if (wbValues.requestedCurrent > Wallbox.wallbox.minCurrent) {
                // check if reduction of load current would be sufficient
                def couldSave = 3 * 230 * (wbValues.requestedCurrent - Wallbox.wallbox.minCurrent)
                if (powerBalance + couldSave >= params.stopThreshold) {
                    // request minimal load current
                    sendSurplus(Wallbox.wallbox.minCurrent, requestedCurrent)
                } else {
                    sendNoSurplus()
                }
                resetHistory()
            }
        } else if (valueTrace.size() >= params.toleranceStackSize) {
            println()
            // we have collected data for some time so we can work with rolling averages
            int meanPSun = ((int) (valueTrace.collect { it.powerSolar }.sum())).intdiv(valueTrace.size())
            int meanCHome = ((int) (valueTrace.collect { it.consumptionHome }.sum())).intdiv(valueTrace.size())
            print "PV: $powerValues.powerSolar, bat: $powerValues.powerBattery, grid: $powerValues.powerGrid," +
                    " home: $powerValues.consumptionHome, soc: $powerValues.socBattery "
            print "meanPSun: $meanPSun, meanCHome: $meanCHome, "
            // how much power should be used for car charging depending on house battery soc
            def availablePVBalance = powerToAmp(meanPSun - meanCHome)
            def availableFromBattery = powerToAmp(powerRamp(powerValues.socBattery))
            def carChargingAmps = powerToAmp(wbValues.energy)
            print "avPV: $availablePVBalance, avBatt: $availableFromBattery, " +
                    "${isCarCharging ? 'charging with ' + wbValues.energy : ''}  reqAmp: $wbValues.requestedCurrent. "
            // different strategies if car is loading or not
            if (isCarCharging) {
                def amp4loading = Math.min(requestedCurrent + availablePVBalance, Wallbox.wallbox.maxCurrent)
                // check if car is reducing current because nearly fully charged
                if (carChargingAmps <= requestedCurrent - 1 ) {
                    switch (chargingState) {
                        case ChargingState.Charging:
                            chargingState = ChargingState.TestAmpCarReduction
                        case ChargingState.TestAmpCarReduction:
                            chargingState = ChargingState.HasAmpCarReduction
                            amp4loading = requestedCurrent
                            break
                        case ChargingState.HasAmpCarReduction:
                            if (wbValues.energy == 0) {
                                sendNoSurplus()
                                stopStrategy()
                                println ' --> eval done, fully charged'
                            }
                    }
                }
                if (amp4loading >= Wallbox.wallbox.minCurrent) {
                    print 'charging from PV only '
                    // charging not in startup phase or requested charging current to be reduced
                    if (chargingState == ChargingState.Charging || amp4loading < requestedCurrent) {
                        sendSurplus(amp4loading, requestedCurrent)
                    }
                    chargingState = chargingState in [ChargingState.NotCharging, ChargingState.StartUpCharging] ?
                            ChargingState.Charging : chargingState
                } else if (amp4loading + availableFromBattery >= Wallbox.wallbox.minCurrent) {
                    print 'keep charging with battery support '
                    sendSurplus(Wallbox.wallbox.minCurrent, requestedCurrent)
                    chargingState = chargingState in [ChargingState.NotCharging, ChargingState.StartUpCharging] ?
                            ChargingState.Charging : chargingState
                } else {
                    print 'stop charging '
                    sendNoSurplus()
                    chargingState = ChargingState.NotCharging
                }
                resetHistory()
            } else {
                if (availablePVBalance >= Wallbox.wallbox.minCurrent) {
                    print 'start charging PV only (limited current) '
                    sendSurplus(Math.min(availablePVBalance, Wallbox.wallbox.maxStartCurrent), 0)
                    resetHistory()
                    chargingState = ChargingState.StartUpCharging
                } else if (availablePVBalance + availableFromBattery - params.batStartHysteresis
                        >= Wallbox.wallbox.minCurrent) {
                    print 'start charging with battery support '
                    sendSurplus(Wallbox.wallbox.minCurrent, 0)
                    resetHistory()
                    chargingState = ChargingState.StartUpCharging
                } else {
                    print 'not enough PV power for charging '
                    // just in case wallbox is just slowly starting to charge
                    if (wbValues.energy > 0) {
                        sendNoSurplus()
                    }
                    chargingState = ChargingState.NotCharging
                }
            }
            println ' --> eval done'
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
