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
class PvChargeStrategy implements PowerValueSubscriber, WallboxValueSubscriber, WallboxStateSubscriber  {
    private PvChargeStrategyParams params = new PvChargeStrategyParams()
    private int stacksize = 10
    private List<PowerValues> valueTrace = []

    private static PvChargeStrategy pvLoadStrategy

    static synchronized PvChargeStrategy getLoadStrategy() {
        if(! pvLoadStrategy) {
            pvLoadStrategy = new PvChargeStrategy()
        }
        pvLoadStrategy
    }

    private enum State {
        WAIT_FOR_SURPLUS,
        CHANGED_SURPLUS,
        HAS_SURPLUS
    }
    private State currentState = State.CHANGED_SURPLUS


    private evalPower() {
        def vt = valueTrace.first()
        def powerBalance = valueTrace.first().powerSolar - valueTrace.first().consumptionHome
        def wbValues = WallboxMonitor.monitor.current
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
                    // request stoploading
                    sendNoSurplus()
                    // reset valueTrace stack to restart evaluation of solar production and house consumption
                    valueTrace.clear()
                }
            }
        } else if (valueTrace.size() >= stacksize) {
            // we have collected data for some time so we can work with rolling averages
//            int meanPBat = ((int)(valueTrace.collect {it.powerBattery}.sum())).intdiv(valueTrace.size())
//            int meanPGrid = ((int)(valueTrace.collect {it.powerGrid}.sum())).intdiv(valueTrace.size())
            int meanPSun = ((int)(valueTrace.collect {it.powerSolar}.sum())).intdiv(valueTrace.size())
            int meanCHome = ((int)(valueTrace.collect {it.consumptionHome}.sum())).intdiv(valueTrace.size())
            // min and max values in that time
//            int minPBat = valueTrace.collect {it.powerBattery}.min()
//            int maxPGrid = valueTrace.collect {it.powerGrid}.max()
            int minPSun = valueTrace.collect {it.powerSolar}.min()
            int maxPSun = valueTrace.collect {it.powerSolar}.max()
//            int maxCHome = valueTrace.collect {it.consumptionHome}.max()
            // variance in that time
//            def deltaPSun = maxPSun - minPSun
//            println "Sun mean: $meanPSun, min: $minPSun, max: $maxPSun, var: $deltaPSun"
            // battery state of charge
            def soc = vt.socBattery
            def bmin = params.minUseBat
            def bmax = params.maxUseBat
            def bcharge = params.batPower
            // how much should be used for battery charge
            def batCharge = (soc <= bmin) ? bcharge : // load maximal when battery below bmin
                    ((soc >= bmax) ? 0 :                     // no need to load when above bmax
                            (bmax - soc).intdiv(bmax - bmin) * bcharge)  // else interpolate
            def carCharging = wbValues.energy > 1000
            def availableBalance = meanPSun - meanCHome - batCharge
            def amp4car = availableBalance.intdiv(3 * 230)
            if (carCharging && amp4car != 0) {
                def amp4loading = wbValues.requestedCurrent + amp4car
                if ( amp4loading >= Wallbox.wallbox.minCurrent) {
                    sendSurplus(amp4loading)
                } else {
                    sendNoSurplus()
                }
                valueTrace.clear()
            } else if (amp4car >= Wallbox.wallbox.minCurrent) {
                sendSurplus(amp4car)
                valueTrace.clear()
            }

        }
    }

    private void sendSurplus(int amps) {

    }

    private void sendNoSurplus() {

    }

    @ActiveMethod(blocking = true)
    void startStrategy(CarChargingManager manager) {
        println "start strategy"
        PowerMonitor.monitor.subscribe this
        WallboxMonitor.monitor.subscribeValue ((WallboxValueSubscriber) this)
        WallboxMonitor.monitor.subscribeState ((WallboxStateSubscriber) this)
    }

    @ActiveMethod
    void stopStrategy(CarChargingManager manager) {
        PowerMonitor.monitor.unsubscribe this
        WallboxMonitor.monitor.unsubscribeValue this
        WallboxMonitor.monitor.unsubscribeState this
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

    @Override
    @ActiveMethod
    void takeWallboxValues(WallboxValues values) {
//        println values
    }

    @Override
    void takeWallboxState(WallboxMonitor.CarLoadingState carState) {
//        println "CarState: $carState"
    }

    @ActiveMethod(blocking = true)
    void setParams(PvChargeStrategyParams p) {
        params = new PvChargeStrategyParams(p)

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
