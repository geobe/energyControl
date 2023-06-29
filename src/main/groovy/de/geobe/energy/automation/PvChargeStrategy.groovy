package de.geobe.energy.automation


import de.geobe.energy.e3dc.PowerValues
import de.geobe.energy.go_e.Wallbox
import de.geobe.energy.go_e.WallboxValues
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

/**
 * Responsibility:
 */
@ActiveObject
class PvChargeStrategy implements PowerValueSubscriber/*, WallboxValueSubscriber, WallboxStateSubscriber*/ {
    private PvChargeStrategyParams params = new PvChargeStrategyParams()
    private int stacksize = 5
    private List<PowerValues> valueTrace = []

    private static PvChargeStrategy pvChargeStrategy

    static synchronized PvChargeStrategy getChargeStrategy() {
        if (!pvChargeStrategy) {
            pvChargeStrategy = new PvChargeStrategy()
        }
        pvChargeStrategy
    }
//
//    private enum State {
//        IDLE,
//        WAIT_FOR_SURPLUS,
//        CHANGED_SURPLUS,
//        HAS_SURPLUS
//    }
//    private State currentState = State.IDLE
//

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
//        WallboxMonitor.monitor.subscribeValue ((WallboxValueSubscriber) this)
//        WallboxMonitor.monitor.subscribeState ((WallboxStateSubscriber) this)
    }

    @ActiveMethod
    void stopStrategy(CarChargingManager) {
        PowerMonitor.monitor.unsubscribe this
        carChargingManager = null
//        WallboxMonitor.monitor.unsubscribeValue this
//        WallboxMonitor.monitor.unsubscribeState this
    }

    @ActiveMethod(blocking = false)
    void takePowerValues(PowerValues powerValues) {
        if (valueTrace.size() > stacksize) {
            valueTrace.removeLast()
        }
        valueTrace.push powerValues
        evalPower()
        println powerValues
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
        def wbValues = WallboxMonitor.monitor.current
        def powerBalance = powerValues.powerSolar - powerValues.consumptionHome
        // more consumption as could be served by battery (negative balance) && loading car
        if (powerBalance < params.stopThreshold && wbValues.energy > 0) {
            // are we currently loading car with more than minimal load current?
            if (wbValues.requestedCurrent > Wallbox.wallbox.minCurrent) {
                // check if reduction of load current would be sufficient
                def couldSave = 3 * 230 * (wbValues.requestedCurrent - Wallbox.wallbox.minCurrent)
                if (powerBalance + couldSave >= params.stopThreshold) {
                    // request minimal load current
                    sendSurplus(Wallbox.wallbox.minCurrent)
                } else {
                    sendNoSurplus()
                    resetHistory()
                }
            }
        } else if (valueTrace.size() >= stacksize) {
            // we have collected data for some time so we can work with rolling averages
            int meanPSun = ((int) (valueTrace.collect { it.powerSolar }.sum())).intdiv(valueTrace.size())
            int meanCHome = ((int) (valueTrace.collect { it.consumptionHome }.sum())).intdiv(valueTrace.size())
            // min and max values in that time
//            int minPSun = valueTrace.collect {it.powerSolar}.min()
//            int maxPSun = valueTrace.collect {it.powerSolar}.max()
            // variance in that time
//            def deltaPSun = maxPSun - minPSun
//            println "Sun mean: $meanPSun, min: $minPSun, max: $maxPSun, var: $deltaPSun"
            // battery state of charge
            def soc = powerValues.socBattery
            def bmin = params.minUseBat
            def bmax = params.maxUseBat
            def maxBatteryCharge = params.batPower
            // how much should be used for battery charge
            def batteryCharge = (soc <= bmin) ? maxBatteryCharge : // load maximal when battery below bmin
                    ((soc >= bmax) ? 0 :                     // no need to load when above bmax
                            (bmax - soc).intdiv(bmax - bmin) * maxBatteryCharge)  // else interpolate
            def carCharging = wbValues.energy > 1000
            def mayBatteryUnload = soc > bmax ? maxBatteryCharge : 0
            def availablePowerBalance = meanPSun - meanCHome - batteryCharge + mayBatteryUnload
            def amp4car = availablePowerBalance.intdiv(3 * 230)
            println "amp4car calculation $amp4car <- availablePowerBalance $availablePowerBalance, " +
                    "batteryCharge $batteryCharge, mayBatteryUnload $mayBatteryUnload," +
                    "carCharging $carCharging"
            // amp4car could be negative, 0 means no change
            if (carCharging && amp4car != 0) {
                def amp4loading = wbValues.requestedCurrent + amp4car
                if (amp4loading >= Wallbox.wallbox.minCurrent) {
                    sendSurplus(amp4loading)
                } else {
                    sendNoSurplus()
                }
                resetHistory()
            } else if (!carCharging && amp4car >= Wallbox.wallbox.minCurrent) {
                sendSurplus(amp4car)
                resetHistory()
            }

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
        carChargingManager?.takeSurplus(amps)
    }

    /**
     * request stoploading
     */
    private void sendNoSurplus() {
        carChargingManager?.takeSurplus(0)
    }

    static void main(String[] args) {
        PvChargeStrategy strategyActor = PvChargeStrategy.getLoadStrategy()
        strategyActor.startStrategy()
        Thread.sleep(90000)
        strategyActor.stopStrategy()
        Thread.sleep(10000)
        strategyActor.startStrategy()
        Thread.sleep(30000)
        strategyActor.stopStrategy()
        PowerMonitor.monitor.shutdown()
    }
}
