package de.geobe.energy.automation

import de.geobe.energy.go_e.Wallbox
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

@ActiveObject
class CarChargingManager implements WallboxStateSubscriber {

    static enum ChargeState {
        Inactive,
        Active,             // compound state
        NoCarConnected,
        CarIsConnected,     // compound state
        ChargePvSurplus,    // compound state
        ChargeTibber,
        ChargeAnyway,
        ChargingStopped,
        HasSurplus,
        NoSurplus,
//        FullyCharged
    }

    static enum ChargeCommand {
        CHARGE_PV_SURPLUS,
        CHARGE_TIBBER,
        CHARGE_ANYWAY,
        CHARGE_STOP
    }

    static enum ChargeEvent {
        Activate,
        Deactivate,
        ExtChargeCmd,
        CarDisconnected,
        ExtStopped,
//        ExtCharge,
        ChargeCommandChanged,
        Surplus,
        NoSurplus,
        AnyStop,
        TibberGo,
        TibberStop
    }

    private ChargeState chargeState = ChargeState.Inactive
    private ChargeCommand chargeCmd = ChargeCommand.CHARGE_STOP
    private ChargeCommand defaultChargeCmd = ChargeCommand.CHARGE_ANYWAY
    private int anywayAmps = Wallbox.wallbox.minCurrent
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
            executeEvent(ChargeEvent.Activate)
        } else {
            executeEvent(ChargeEvent.Deactivate)
        }
    }

    @ActiveMethod
    void setDefaultChargeCmd(ChargeCommand cmd) {
        defaultChargeCmd = cmd
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
                executeEvent(ChargeEvent.CarDisconnected)
                break
            case WallboxMonitor.CarChargingState.CHARGING:
                executeEvent(ChargeEvent.ExtChargeCmd)
                break
            case WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_CAR:
            case WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_APP:
            case WallboxMonitor.CarChargingState.FULLY_CHARGED:
                executeEvent(ChargeEvent.AnyStop)
         }
    }

    @ActiveMethod(blocking = true)
    void takeChargeCmd(ChargeCommand cmd, int amps = Wallbox.wallbox.minCurrent) {
        if (cmd != chargeCmd) {
            chargeCmd = cmd
            if (cmd == ChargeCommand.CHARGE_ANYWAY) {
                anywayAmps = amps
            }
            executeEvent(ChargeEvent.ChargeCommandChanged)
        }
    }

    @ActiveMethod(blocking = true)
    void takeChargingCurrent(int amps) {
        if (amps) {
            executeEvent(ChargeEvent.Surplus, amps)
        } else {
            executeEvent(ChargeEvent.NoSurplus)
        }
    }

    @ActiveMethod(blocking = true)
    void takeFullyCharged() {
        executeEvent(ChargeEvent.AnyStop)
    }

    private void executeEvent(ChargeEvent event, def param = null) {
        def evTrigger = "CarChargingManager $chargeState --$event${param ? '(' + param + ')' : ''}-> "
        print evTrigger
        switch (event) {
            case ChargeEvent.Activate:
                switch (chargeState) {
                    case ChargeState.Inactive:
                        chargeState = enterActive()
                        break
                    default:
                        println "ignore $event in state $chargeState"
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
                        stopTibberStrategy()
                        break
                    case ChargeState.HasSurplus:
                        stopCharging()
                    case ChargeState.NoSurplus:
                        PvChargeStrategySM.chargeStrategy.stopStrategy()
                        break
                    default:
                        println "ignore $event in state $chargeState"
                }
                exitActive()
                chargeState = ChargeState.Inactive
                break
            case ChargeEvent.ExtChargeCmd:
                switch (chargeState) {
                    case ChargeState.NoCarConnected:
                        chargeState = enterActive()
                        break
                    case ChargeState.ChargingStopped:
                        forceDefaultIfStopped()
                        enterCarConnected()
                        break
                    default:
                        println "ignore $event in state $chargeState"
                }
                break
            case ChargeEvent.CarDisconnected:
                switch (chargeState) {
                    case ChargeState.ChargeAnyway:
                        stopCharging()
                    case ChargeState.ChargingStopped:
                        break
                    case ChargeState.ChargeTibber:
                        stopTibberStrategy()
                        break
                    case ChargeState.HasSurplus:
                        stopCharging()
                    case ChargeState.NoSurplus:
                        PvChargeStrategySM.chargeStrategy.stopStrategy()
                        break
                    case  ChargeState.ChargingStopped:
                        forceDefaultIfStopped()
                        break
                    default:
                        println "ignore $event in state $chargeState"
                }
                chargeState = ChargeState.NoCarConnected
                break
            case ChargeEvent.ChargeCommandChanged:
                switch (chargeState) {
                    case ChargeState.ChargeTibber:
                        stopTibberStrategy()
                        break
                    case ChargeState.ChargeAnyway:
                        stopCharging()
                        break
                    case ChargeState.ChargingStopped:
                        break
                    case ChargeState.HasSurplus:
                        stopCharging()
                    case ChargeState.NoSurplus:
                        PvChargeStrategySM.chargeStrategy.stopStrategy()
                        break
                    default:
                        println "ignore $event in state $chargeState"
                }
                chargeState = enterCarConnected()
                break
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
                    default:
                        println "ignore $event in state $chargeState"
                }
                break
            case ChargeEvent.NoSurplus:
                switch (chargeState) {
                    case ChargeState.HasSurplus:
                        stopCharging()
                        chargeState = ChargeState.NoSurplus
                        break
                    default:
                        println "ignore $event in state $chargeState"
                }
                break
            case ChargeEvent.AnyStop:
                switch (chargeState) {
                    case ChargeState.NoSurplus:
                        stopCharging()
                    case ChargeState.HasSurplus:
                        PvChargeStrategySM.chargeStrategy.stopStrategy()
                        break
                    case ChargeState.ChargeTibber:
                        stopTibberStrategy()
                        break
                    case ChargeState.ChargeAnyway:
                        stopCharging()
                        break
                    default:
                        println "ignore $event in state $chargeState"
                }
                chargeState = ChargeState.ChargingStopped
                break
        }
        println "$evTrigger $chargeState"
    }

    private ChargeState enterActive() {
        ChargeState result
        WallboxMonitor.monitor.subscribeState this
        def chargingState = WallboxMonitor.monitor.current.state
        println "enterActive, chargingState: $chargingState"
        if (chargingState == WallboxMonitor.CarChargingState.NO_CAR) {
            result = ChargeState.NoCarConnected
        } else { // carConnected
            result = enterCarConnected()
        }
        result
    }

    private exitActive() {
        WallboxMonitor.monitor.unsubscribeState this

    }

    private ChargeState enterCarConnected() {
        def chargingState = WallboxMonitor.monitor.current.state
        if (chargingState == WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_CAR ||
                chargingState == WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_APP ||
                chargingState == WallboxMonitor.CarChargingState.FULLY_CHARGED ||
                chargeCmd == ChargeCommand.CHARGE_STOP
        ) {
            stopCharging()
            ChargeState.ChargingStopped
        } else {
            switch (chargeCmd) {
                case ChargeCommand.CHARGE_ANYWAY:
                    startCharging()
                    setCurrent(Wallbox.wallbox.maxCurrent)
                    ChargeState.ChargeAnyway
                    break
                case ChargeCommand.CHARGE_TIBBER:
                    startTibberStrategy()
                    ChargeState.ChargeTibber
                    break
                case ChargeCommand.CHARGE_PV_SURPLUS:
                    chargeStrategy = PvChargeStrategySM.chargeStrategy
                    chargeStrategy.startStrategy this
                    stopCharging()
                    ChargeState.NoSurplus
                    break
            }
        }
    }

    /**
     * If charging was stopped by program, external start command
     * from car or app will override program stop with default
     */
    private ChargeCommand forceDefaultIfStopped() {
        if (chargeCmd == ChargeCommand.CHARGE_STOP) {
            ChargeCommand.CHARGE_ANYWAY
        } else {
            chargeCmd
        }
    }

    private stopCharging() {
        println "stop charging"
        Wallbox.wallbox.stopCharging()
        setCurrent(0)
    }

    private startCharging() {
        print " start charging"
        Wallbox.wallbox.startCharging()
        println " --> $Wallbox.wallbox.wallboxValues"
    }

    private setCurrent(int amp = 0) {
        print " set current to $amp"
        Wallbox.wallbox.chargingCurrent = amp
        println " --> $Wallbox.wallbox.wallboxValues"
    }

    private startTibberStrategy() {
        println "start tibber monitor"
    }

    private stopTibberStrategy() {
        println "stop tibber monitor"
    }

    static void main(String[] args) {
        CarChargingManager manager = new CarChargingManager()
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                print "shutting down gently ..."
                println ' done'

            }
        })

//        PvChargeStrategy.chargeStrategy.chargingManager = manager
        PvChargeStrategyParams params = new PvChargeStrategyParams(toleranceStackSize: 5)
        PvChargeStrategySM.chargeStrategy.params = params
//        manager.chargeStrategy = PvChargeStrategySM
//        manager.active = true
//        Thread.sleep(3000)
        manager.takeChargeCmd(ChargeCommand.CHARGE_PV_SURPLUS)
//        Thread.sleep(2 * 60 * 60 * 1000) // 1 hour
        Thread.sleep(1 * 60 * 1000) // 3 minutes
        manager.active = false
        Thread.sleep(3000)
        manager.shutDown()
    }
}
