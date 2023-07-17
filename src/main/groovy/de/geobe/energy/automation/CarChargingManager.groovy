package de.geobe.energy.automation

import de.geobe.energy.go_e.Wallbox
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

@ActiveObject
class CarChargingManager implements WallboxStateSubscriber {

    static enum ChargeState {
        Inactive,
        Active,
        NoCarConnected,
        CarConnected,
        ChargePvSurplus,
        ChargeTibber,
        ChargeAnyway,
        ChargingStopped,
        HasSurplus,
        NoSurplus,
        FullyCharged
    }

    static enum ChargeRule {
        CHARGE_PV_SURPLUS,
        CHARGE_TIBBER,
        CHARGE_ANYWAY,
        CHARGE_STOP
    }

    static enum ChargeEvent {
        Activate,
        Deactivate,
        CarConnected,
        CarDisconnected,
        ChargeRuleChanged,
        Surplus,
        NoSurplus,
        FullyCharged,
        TibberGo,
        TibberStop
    }

    private ChargeState chargeState = ChargeState.Inactive
    private ChargeRule chargeRule = ChargeRule.CHARGE_STOP
    private ChargeStrategy chargeStrategy

//    @ActiveMethod(blocking = true)
//    void setChargeStrategy(ChargeStrategy strategy) {
//        if (strategy != chargeStrategy) {
//            chargeStrategy?.stopStrategy()
//            chargeStrategy = strategy
//            takeEvent(ChargeEvent.Deactivate)
//        }
//    }

    @ActiveMethod(blocking = true)
    void setActive(boolean active) {
        if (active) {
            chargeStrategy?.startStrategy(this)
            takeEvent(ChargeEvent.Activate)
        } else {
            chargeStrategy?.stopStrategy()
            takeEvent(ChargeEvent.Deactivate)
        }

    }

    @ActiveMethod
    void shutDown() {
        chargeStrategy?.stopStrategy()
        WallboxMonitor.monitor.shutdown()
    }

    @ActiveMethod(blocking = true)
    @Override
    void takeWallboxState(WallboxMonitor.CarChargingState carState) {
        print "WB state: $carState -> "
        switch (carState) {
            case WallboxMonitor.CarChargingState.NO_CAR:
            case WallboxMonitor.CarChargingState.UNDEFINED:
                takeEvent(ChargeEvent.CarDisconnected)
                break
            case WallboxMonitor.CarChargingState.CHARGING:
            case WallboxMonitor.CarChargingState.CHARGING_STOPPED:
            case WallboxMonitor.CarChargingState.FULLY_CHARGED:
                takeEvent(ChargeEvent.CarConnected)
        }
    }

    @ActiveMethod(blocking = true)
    void takeChargeRule(ChargeRule rule) {
        if (rule != chargeRule) {
            chargeRule = rule
            takeEvent(ChargeEvent.Deactivate)
            takeEvent(ChargeEvent.Activate)
        }
    }

    @ActiveMethod(blocking = true)
    void takeChargingCurrent(int amps) {
        if (amps) {
            takeEvent(ChargeEvent.Surplus, amps)
        } else {
            takeEvent(ChargeEvent.NoSurplus)
        }
    }

    @ActiveMethod(blocking = true)
    void takeFullyCharged() {
        takeEvent(ChargeEvent.FullyCharged)
    }

//    @ActiveMethod(blocking = true)
    private void takeEvent(ChargeEvent event, def param = null) {
        def evTrigger = "CarChargingManager $chargeState --$event${param ? '(' + param + ')' : ''}-> "
        switch (event) {
            case ChargeEvent.Activate:
                switch (chargeState) {
                    case ChargeState.Inactive:
                        chargeState = enterActive()
                }
                break
            case ChargeEvent.Deactivate:
                switch (chargeState) {
                    case ChargeState.NoCarConnected:
                        break
                    case ChargeState.ChargingStopped:
                    case ChargeState.ChargeAnyway:
                        stopCharging()
                        break
                    case ChargeState.ChargeTibber:
                        stopTibberMonitor()
                        break
                    case ChargeState.HasSurplus:
                        stopCharging()
                    case ChargeState.NoSurplus:
                        PvChargeStrategy.chargeStrategy.stopStrategy()
                        break
                }
                exitActive()
                chargeState = ChargeState.Inactive
                break
            case ChargeEvent.CarConnected:
                switch (chargeState) {
                    case ChargeState.NoCarConnected:
                        chargeState = enterActive()
                }
                break
            case ChargeEvent.CarDisconnected:
                switch (chargeState) {
                    case ChargeState.ChargingStopped:
                    case ChargeState.ChargeAnyway:
                        stopCharging()
                        break
                    case ChargeState.ChargeTibber:
                        stopTibberMonitor()
                        break
                    case ChargeState.HasSurplus:
                        stopCharging()
                    case ChargeState.NoSurplus:
                        PvChargeStrategy.chargeStrategy.stopStrategy()
                        break
                }
                chargeState = ChargeState.NoCarConnected
                break
//            case ChargeEvent.ChargeRuleChanged:
//                switch (chargeState) {
//                    case ChargeState.ChargeTibber:
//                        // stop current strategy
//                        // set tibber strategy
//                        break
//                    case ChargeState.ChargeAnyway:
//                        setCurrent(Wallbox.wallbox.maxCurrent)
//                        startCharging()
//                        break
//                    case ChargeState.ChargingStopped:
//                        stopCharging()
//                        break
//                    case ChargeState.NoSurplus:
//                    case ChargeState.HasSurplus:
//                    case ChargeState.FullyCharged:
//                        chargeState = enterCarConnected()
//                }
//                break
            case ChargeEvent.Surplus:
                switch (chargeState) {
                    case ChargeState.NoSurplus:
                        chargeState = ChargeState.HasSurplus
                        setCurrent(param)
                        startCharging()
                        break
                    case ChargeState.HasSurplus:
                        setCurrent(param)
                        break
                }
                break
            case ChargeEvent.NoSurplus:
                switch (chargeState) {
                    case ChargeState.HasSurplus:
                        stopCharging()
                        chargeState = ChargeState.NoSurplus
                        break
                }
                break
            case ChargeEvent.FullyCharged:
                switch (chargeState) {
                    case ChargeState.ChargeTibber:
                    case ChargeState.ChargeAnyway:
                    case ChargeState.ChargingStopped:
                    case ChargeState.NoSurplus:
                    case ChargeState.HasSurplus:
                        stopCharging()
                        chargeState = ChargeState.FullyCharged
                        break
                }
        }
        println "$evTrigger $chargeState"
    }

    private enterActive() {
        WallboxMonitor.monitor.subscribeState this
        if (WallboxMonitor.monitor.current.state == WallboxMonitor.CarChargingState.NO_CAR) {
            ChargeState.NoCarConnected
        } else {
            enterCarConnected()
        }
    }

    private exitActive() {
        WallboxMonitor.monitor.unsubscribeState this

    }

    private enterCarConnected() {
        switch (chargeRule) {
            case ChargeRule.CHARGE_STOP:
                stopCharging()
                return ChargeState.ChargingStopped
            case ChargeRule.CHARGE_ANYWAY:
                startCharging()
                setCurrent(Wallbox.wallbox.maxCurrent)
                return ChargeState.ChargeAnyway
            case ChargeRule.CHARGE_TIBBER:
                startTibberMonitor()
                return ChargeState.ChargeTibber
            case ChargeRule.CHARGE_PV_SURPLUS:
                chargeStrategy = PvChargeStrategy.chargeStrategy
                chargeStrategy.startStrategy this
                return ChargeState.NoSurplus
        }
    }

    private stopCharging() {
        println "stop charging"
        Wallbox.wallbox.stopCharging()
        setCurrent(0)
    }

    private startCharging() {
        println " start charging"
        Wallbox.wallbox.startCharging()
    }

    private setCurrent(int amp = 0) {
        println " set current to $amp"
        Wallbox.wallbox.chargingCurrent = amp
    }

    private startTibberMonitor() {
        println "start tibber monitor"
    }

    private stopTibberMonitor() {
        println "stop tibber monitor"
    }

    static void main(String[] args) {
        CarChargingManager manager = new CarChargingManager()
//        PvChargeStrategy.chargeStrategy.chargingManager = manager
        PvChargeStrategyParams params = new PvChargeStrategyParams(toleranceStackSize: 5)
        PvChargeStrategySM.chargeStrategy.params = params
//        manager.chargeStrategy = PvChargeStrategySM
//        manager.active = true
//        Thread.sleep(3000)
        manager.takeChargeRule(ChargeRule.CHARGE_PV_SURPLUS)
//        Thread.sleep(2 * 60 * 60 * 1000) // 1 hour
        Thread.sleep(3 * 60 * 1000) // 3 minutes
        manager.active = false
        Thread.sleep(3000)
        manager.shutDown()
    }
}
