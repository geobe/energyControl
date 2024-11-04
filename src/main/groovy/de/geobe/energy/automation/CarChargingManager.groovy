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

import de.geobe.energy.automation.WallboxMonitor.CarChargingState
import de.geobe.energy.go_e.Wallbox
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

/**
 * Top level controller that manages charging of car by controlling the wallbox.
 * Four different charging modes are supported:
 * <ol>
 *     <li>Photovoltaic charging, trying to optimally use power produced by sunlight</li>
 *     <li>Tibber charging, using low price hours for charging</li>
 *     <li>Charging anyway, </li>
 *     <li>No charging</li>
 * </ol>
 */
@ActiveObject
class CarChargingManager implements WallboxStateSubscriber {

    private static CarChargingManager carChargingManager

    static synchronized getCarChargingManager() {
        if (! carChargingManager) {
            carChargingManager = new CarChargingManager()
        }
        carChargingManager
    }

    private CarChargingManager() {

    }

    static enum ChargeManagerState {
        Inactive,
        Active,             // compound state
            NoCarConnected,
            CarIsConnected,     // compound state
                Chargeable,     // compound state
                    ChargeTibber,
                    ChargeAnyway,
                    ChargingStopped,
                    ChargePvSurplus,    // compound state
                        HasSurplus,
                        NoSurplus,
                        WaitForExtCharge,
                FullyCharged
    }

    static enum ChargeManagerStrategy {
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
        ExtStoppedByCar,
        ExtStoppedByApp,
        FullyCharged,
        ChargeCommandChanged,
        Surplus,
        NoSurplus,
        AnyStop,
        TibberGo,
        TibberStop,
        MonitorException
    }

    private ChargeManagerState chargeState = ChargeManagerState.Inactive
    private ChargeManagerStrategy chargeManagerStrategy = ChargeManagerStrategy.CHARGE_STOP
    private ChargeManagerStrategy defaultChargeManagerStrategy = ChargeManagerStrategy.CHARGE_PV_SURPLUS
    private int anywayAmps = Wallbox.wallbox.minCurrent
    private de.geobe.energy.automation.ChargeStrategy chargeStrategy
    private CarChargingState newState, actualCarChargingState

//    @ActiveMethod(blocking = true)
//    void setChargeStrategy(ChargeManagerStrategy strategy) {
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
    void setDefaultChargeManagerStrategy(ChargeManagerStrategy cmd) {
        defaultChargeManagerStrategy = cmd
    }

    @ActiveMethod(blocking = true)
    void shutDown() {
        executeEvent(ChargeEvent.Deactivate)
        println "manager shutdown"
//        WallboxMonitor.monitor.shutdown()
    }

    /**
     * ToDo: make individual events and handle them
     * @param carState
     */
    @ActiveMethod(blocking = true)
    @Override
    void takeWallboxState(WallboxMonitor.CarChargingState carState) {
        newState = carState
        if (newState != actualCarChargingState) {
//            println "WB state: $carState -> "
            actualCarChargingState = newState
            switch (carState) {
                case WallboxMonitor.CarChargingState.NO_CAR:
                case WallboxMonitor.CarChargingState.UNDEFINED:
                    executeEvent(ChargeEvent.CarDisconnected)
                    break
                case WallboxMonitor.CarChargingState.FORCE_CHARGING:
                    chargeManagerStrategy = ChargeManagerStrategy.CHARGE_ANYWAY
                case WallboxMonitor.CarChargingState.WAIT_CAR:
                case WallboxMonitor.CarChargingState.CHARGING:
                    executeEvent(ChargeEvent.ExtChargeCmd)
                    break
                case WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_CAR:
                    executeEvent(ChargeEvent.ExtStoppedByCar)
                    break
                case WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_APP:
                    executeEvent(ChargeEvent.ExtStoppedByApp)
                    break
                case WallboxMonitor.CarChargingState.FULLY_CHARGED:
                    executeEvent(ChargeEvent.FullyCharged)
            }
        }
    }

    @Override
    void takeMonitorException(Exception exception) {
        executeEvent(ChargeEvent.MonitorException)
    }

    @Override
    void resumeAfterMonitorException() {
        // ignore
    }

    @ActiveMethod(blocking = true)
    void takeChargeCmd(ChargeManagerStrategy cmd, int amps = Wallbox.wallbox.minCurrent) {
        if (cmd != chargeManagerStrategy) {
            chargeManagerStrategy = cmd
            if (cmd == ChargeManagerStrategy.CHARGE_ANYWAY) {
                anywayAmps = amps
            }
            executeEvent(ChargeEvent.ChargeCommandChanged)
        }
    }

    ChargeManagerStrategy getChargeManagerStrategy() {
        chargeManagerStrategy
    }

    ChargeManagerState getChargeManagerState() {
        chargeState
    }

    def getChargeManagerStrategyDetail() {
        def detail
        switch (chargeManagerStrategy) {
            case ChargeManagerStrategy.CHARGE_PV_SURPLUS:
                detail = (chargeStrategy?.state?:ChargeStrategy.inactive).toString()
                break
            default: detail = ChargeStrategy.inactive
        }
        detail
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
        executeEvent(ChargeEvent.FullyCharged)
    }

    private void executeEvent(ChargeEvent event, def param = null) {
        def evTrigger = "$chargeState --$event${param ? '(' + param + ')' : ''}-> "
        switch (event) {
            case ChargeEvent.Activate:
                switch (chargeState) {
                    case ChargeManagerState.Inactive:
                        chargeState = enterActive()
                        break
                }
                break
            case ChargeEvent.Deactivate:
                switch (chargeState) {
                    case ChargeManagerState.ChargingStopped:
                    case ChargeManagerState.NoCarConnected:
                    case ChargeManagerState.FullyCharged:
                        break
                    case ChargeManagerState.ChargeAnyway:
                        stopCharging()
                        break
                    case ChargeManagerState.ChargeTibber:
                        stopTibberStrategy()
                        break
                    case ChargeManagerState.WaitForExtCharge:
                    case ChargeManagerState.HasSurplus:
                        stopCharging()
                    case ChargeManagerState.NoSurplus:
                        PvChargeStrategy.chargeStrategy.stopStrategy()
                        break
                }
                exitActive()
                chargeState = ChargeManagerState.Inactive
                break
            case ChargeEvent.ExtChargeCmd:
                switch (chargeState) {
                    case ChargeManagerState.NoCarConnected:
                        chargeState = enterCarConnected()
                        break
                    case ChargeManagerState.ChargingStopped:
                        chargeState = enterCarConnected()
                        break
                    case ChargeManagerState.WaitForExtCharge:
                        chargeState = ChargeManagerState.HasSurplus
                        break
                }
                break
            case ChargeEvent.ExtStoppedByCar:
                switch (chargeState) {
                    case ChargeManagerState.WaitForExtCharge:
//                    case ChargeManagerState.NoSurplus:
                        break           // ignore, artifact of state change
                    default:
                        execStop()
                        chargeState = ChargeManagerState.ChargingStopped
                }
                break
            case ChargeEvent.ExtStoppedByApp:
                switch (chargeState) {
                    case ChargeManagerState.NoSurplus:
                        break           // ignore, artifact of state change
                    default:
                        execStop()
                        chargeState = ChargeManagerState.ChargingStopped
                }
                break
            case ChargeEvent.CarDisconnected:
                switch (chargeState) {
                    case ChargeManagerState.ChargeAnyway:
                        stopCharging()
                    case ChargeManagerState.ChargeTibber:
                        stopTibberStrategy()
                        break
                    case ChargeManagerState.HasSurplus:
                        stopCharging()
                    case ChargeManagerState.NoSurplus:
                        PvChargeStrategy.chargeStrategy.stopStrategy()
                        break
                    case ChargeManagerState.ChargingStopped:
                        forceDefault()
                        break
                }
                chargeState = ChargeManagerState.NoCarConnected
                break
            case ChargeEvent.ChargeCommandChanged:
                switch (chargeState) {
                    case ChargeManagerState.ChargeTibber:
                        stopTibberStrategy()
                        chargeState = enterCarConnected()
                        break
                    case ChargeManagerState.ChargeAnyway:
                        stopCharging()
                        chargeState = enterCarConnected()
                        break
                    case ChargeManagerState.ChargingStopped:
                        chargeState = overrideExtChargeCmd()
                        break
                    case ChargeManagerState.HasSurplus:
                    case ChargeManagerState.WaitForExtCharge:
                        stopCharging()
                    case ChargeManagerState.NoSurplus:
                        PvChargeStrategy.chargeStrategy.stopStrategy()
                        chargeState = enterCarConnected()
                        break
                    case ChargeManagerState.Inactive:
                        break
                }
                break
            case ChargeEvent.Surplus:
                switch (chargeState) {
                    case ChargeManagerState.NoSurplus:
                        chargeState = ChargeManagerState.WaitForExtCharge
                        setCurrent(param)
                        startCharging()
                        break
                    case ChargeManagerState.WaitForExtCharge:
                        // check for missed event
                        if (WallboxMonitor.monitor.current.state == WallboxMonitor.CarChargingState.CHARGING) {
                            chargeState = ChargeManagerState.HasSurplus
                        }
                        // blocking, if not started charging by car
                        // just go to charging state hasSurplus without adjusting current
//                        chargeState = ChargeManagerState.HasSurplus
//                        break
                    case ChargeManagerState.HasSurplus:
                        setCurrent(param)
                        break
                }
                break
            case ChargeEvent.NoSurplus:
                switch (chargeState) {
                    case ChargeManagerState.HasSurplus:
                    case ChargeManagerState.WaitForExtCharge:
                        stopCharging()
                        chargeState = ChargeManagerState.NoSurplus
                        break
                    case ChargeManagerState.NoSurplus:
                        break
                }
                break
            case ChargeEvent.AnyStop:
                execStop()
                chargeState = ChargeManagerState.ChargingStopped
                break
            case ChargeEvent.FullyCharged:
                execStop()
                chargeState = ChargeManagerState.FullyCharged
        }
//        println "CarChargingManager -> $evTrigger $chargeState @ ${WallboxMonitor.monitor.current.state}"
    }

    /**
     * Superstate, find out substate to go to. See state chart.
     */
     private ChargeManagerState enterActive() {
        ChargeManagerState result
        def chargingState = WallboxMonitor.monitor.current.state
//        println "enterActive, chargingState: $chargingState"
        if (chargingState == WallboxMonitor.CarChargingState.NO_CAR) {
            result = ChargeManagerState.NoCarConnected
        } else { // carConnected
            result = enterCarConnected(false)
        }
        // start receiving state change events from wallbox monitor
        WallboxMonitor.monitor.subscribeState this
        result
    }

    private exitActive() {
        WallboxMonitor.monitor.unsubscribeState this

    }

    private ChargeManagerState overrideExtChargeCmd() {
        enterCarConnected(false)
    }

    /**
     * Superstate, find out substate to go to. See state chart.
     * @param extCmdHasPrio if stop command from app or car has priority
     * @return none
     */
    private ChargeManagerState enterCarConnected(boolean extCmdHasPrio = true) {
        // remember original chargingState and avoid getting it sent repeatedly
        actualCarChargingState = WallboxMonitor.monitor.current.state
        def externalStopCmd = extCmdHasPrio &&
                (actualCarChargingState == WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_CAR ||
                        actualCarChargingState == WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_APP)
//        println "\t\t ecc: set actualCarChargingState to $actualCarChargingState,\n\t\t " +
//                "externalStopCmd: $externalStopCmd, chargeManagerStrategy: $chargeManagerStrategy"
        if (externalStopCmd ||
                actualCarChargingState == WallboxMonitor.CarChargingState.FULLY_CHARGED ||
                chargeManagerStrategy == ChargeManagerStrategy.CHARGE_STOP
        ) {
            stopCharging()
            ChargeManagerState.ChargingStopped
        } else {
            if (actualCarChargingState == WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_CAR) {
                // switch to CHARGING_STOPPED_BY_APP
                stopCharging()
            }
            switch (chargeManagerStrategy) {
                case ChargeManagerStrategy.CHARGE_ANYWAY:
                    startCharging()
                    setCurrent(Wallbox.wallbox.maxCurrent)
                    ChargeManagerState.ChargeAnyway
                    break
                case ChargeManagerStrategy.CHARGE_TIBBER:
                    startTibberStrategy()
                    ChargeManagerState.ChargeTibber
                    break
                case ChargeManagerStrategy.CHARGE_PV_SURPLUS:
                    chargeStrategy = PvChargeStrategy.chargeStrategy
                    chargeStrategy.startStrategy this
                    if (actualCarChargingState in [
                            WallboxMonitor.CarChargingState.WAIT_CAR,
                            WallboxMonitor.CarChargingState.CHARGING,
                            WallboxMonitor.CarChargingState.FORCE_CHARGING]) {
                        ChargeManagerState.WaitForExtCharge
                    } else {
                        ChargeManagerState.NoSurplus
                    }
                    break
            }
        }
    }

    private execStop() {
        switch (chargeState) {
            case ChargeManagerState.WaitForExtCharge:
            case ChargeManagerState.HasSurplus:
                stopCharging()
            case ChargeManagerState.NoSurplus:
                chargeStrategy.stopStrategy()
                break
            case ChargeManagerState.ChargeTibber:
                stopTibberStrategy()
                break
            case ChargeManagerState.ChargeAnyway:
                stopCharging()
                break
        }
//        chargeState = nextState
    }

    /**
     * If charging was stopped by program, external start command
     * from car or app will override program stop with default
     */
    private ChargeManagerStrategy forceDefault() {
//        if (chargeManagerStrategy == ChargeManagerStrategy.CHARGE_STOP) {
        chargeManagerStrategy = defaultChargeManagerStrategy
//            ChargeManagerStrategy.CHARGE_ANYWAY
//        }
        chargeManagerStrategy
    }

    private stopCharging() {
        WallboxMonitor.monitor.stopCharging()
    }

    private startCharging() {
        WallboxMonitor.monitor.startCharging()
    }

    private startChargingRemote() {
        WallboxMonitor.monitor.forceStartCharging()
    }

    private setCurrent(int amp = 0) {
        WallboxMonitor.monitor.current = amp
    }

    private startTibberStrategy() {
        println "start tibber monitor"
    }

    private stopTibberStrategy() {
        println "stop tibber monitor"
    }

    static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                print "shutting down gently ..."
                carChargingManager.shutDown()
                WallboxMonitor.monitor.shutdown()
                PowerMonitor.monitor.shutdown()
                Thread.sleep 1000
                println ' done'
            }
        })
        PowerStrategyParams params =
                new PowerStrategyParams(toleranceStackSize: 5, batStartHysteresis: 0, maxBatUnloadPower: 2000)
        PvChargeStrategy.chargeStrategy.params = params
        // activate manager first
        carChargingManager.active = true
        // let it initialize a while, then set command
        Thread.sleep 3000
        carChargingManager.takeChargeCmd(ChargeManagerStrategy.CHARGE_PV_SURPLUS)
        for (i in 1..90) {
            Thread.sleep(1 * 60 * 1000) // 1 minute
            println "-----------------> running $i minute${i > 1?'s':''} <--------------------------"
        }
//        Thread.sleep(2 * 60 * 60 * 1000) // 2 hours
        carChargingManager.shutDown()
        PeriodicExecutor.shutdown()
        println "Testrun finished"
    }
}
