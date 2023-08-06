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
        WaitForExtCharge
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
        ExtStoppedByCar,
        ExtStoppedByApp,
        ExtFullyCharged,
        ChargeCommandChanged,
        Surplus,
        NoSurplus,
        AnyStop,
        TibberGo,
        TibberStop
    }

    private ChargeState chargeState = ChargeState.Inactive
    private ChargeCommand chargeCmd = ChargeCommand.CHARGE_STOP
    private ChargeCommand defaultChargeCmd = ChargeCommand.CHARGE_PV_SURPLUS
    private int anywayAmps = Wallbox.wallbox.minCurrent
    private ChargeStrategy chargeStrategy
    private CarChargingState newState, actualCarChargingState

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
            println "WB state: $carState -> "
            actualCarChargingState = newState
            switch (carState) {
                case WallboxMonitor.CarChargingState.NO_CAR:
                case WallboxMonitor.CarChargingState.UNDEFINED:
                    executeEvent(ChargeEvent.CarDisconnected)
                    break
                case WallboxMonitor.CarChargingState.CHARGING_ANYWAY:
                    chargeCmd = ChargeCommand.CHARGE_ANYWAY
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
                    executeEvent(ChargeEvent.AnyStop)
            }
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

    ChargeCommand getChargeCommand() {
        chargeCmd
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
        def evTrigger = "CCMgr $chargeState --$event${param ? '(' + param + ')' : ''}-> "
        switch (event) {
            case ChargeEvent.Activate:
                switch (chargeState) {
                    case ChargeState.Inactive:
                        chargeState = enterActive()
                        break
                }
                break
            case ChargeEvent.Deactivate:
                switch (chargeState) {
                    case ChargeState.ChargingStopped:
                    case ChargeState.NoCarConnected:
                        break
                    case ChargeState.ChargeAnyway:
                        stopCharging()
                        break
                    case ChargeState.ChargeTibber:
                        stopTibberStrategy()
                        break
                    case ChargeState.WaitForExtCharge:
                    case ChargeState.HasSurplus:
                        stopCharging()
                    case ChargeState.NoSurplus:
                        PvChargeStrategySM.chargeStrategy.stopStrategy()
                        break
                }
                exitActive()
                chargeState = ChargeState.Inactive
                break
            case ChargeEvent.ExtChargeCmd:
                switch (chargeState) {
                    case ChargeState.NoCarConnected:
                        chargeState = enterCarConnected()
                        break
                    case ChargeState.ChargingStopped:
                        chargeState = enterCarConnected()
                        break
                    case ChargeState.WaitForExtCharge:
                        chargeState = ChargeState.HasSurplus
                        break
                }
                break
            case ChargeEvent.ExtStoppedByCar:
                switch (chargeState) {
                    case ChargeState.WaitForExtCharge:
//                    case ChargeState.NoSurplus:
                        break           // ignore, artifact of state change
                    default:
                        execStop()
                }
                break
            case ChargeEvent.ExtStoppedByApp:
                switch (chargeState) {
                    case ChargeState.NoSurplus:
                        break           // ignore, artifact of state change
                    default:
                        execStop()
                }
                break
            case ChargeEvent.CarDisconnected:
                switch (chargeState) {
                    case ChargeState.ChargeAnyway:
                        stopCharging()
                    case ChargeState.ChargeTibber:
                        stopTibberStrategy()
                        break
                    case ChargeState.HasSurplus:
                        stopCharging()
                    case ChargeState.NoSurplus:
                        PvChargeStrategySM.chargeStrategy.stopStrategy()
                        break
                    case ChargeState.ChargingStopped:
                        forceDefault()
                        break
                }
                chargeState = ChargeState.NoCarConnected
                break
            case ChargeEvent.ChargeCommandChanged:
                switch (chargeState) {
                    case ChargeState.ChargeTibber:
                        stopTibberStrategy()
                        chargeState = enterCarConnected()
                        break
                    case ChargeState.ChargeAnyway:
                        stopCharging()
                        chargeState = enterCarConnected()
                        break
                    case ChargeState.ChargingStopped:
                        chargeState = overrideExtChargeCmd()
                        break
                    case ChargeState.HasSurplus:
                    case ChargeState.WaitForExtCharge:
                        stopCharging()
                    case ChargeState.NoSurplus:
                        PvChargeStrategySM.chargeStrategy.stopStrategy()
                        chargeState = enterCarConnected()
                        break
                }
                break
            case ChargeEvent.Surplus:
                switch (chargeState) {
                    case ChargeState.NoSurplus:
                        chargeState = ChargeState.WaitForExtCharge
                        setCurrent(param)
                        startCharging()
                        break
                    case ChargeState.WaitForExtCharge:
                        // check for missed event
                        if (WallboxMonitor.monitor.current.state == WallboxMonitor.CarChargingState.CHARGING) {
                            chargeState = ChargeState.HasSurplus
                        }
                    case ChargeState.HasSurplus:
                        setCurrent(param)
                        break
                }
                break
            case ChargeEvent.NoSurplus:
                switch (chargeState) {
                    case ChargeState.HasSurplus:
                    case ChargeState.WaitForExtCharge:
                        stopCharging()
                        chargeState = ChargeState.NoSurplus
                        break
                    case ChargeState.NoSurplus:
                        break
                }
                break
            case ChargeEvent.AnyStop:
                execStop()
                break
        }
        println "$evTrigger $chargeState"
    }

    private ChargeState enterActive() {
        ChargeState result
        def chargingState = WallboxMonitor.monitor.current.state
//        println "enterActive, chargingState: $chargingState"
        if (chargingState == WallboxMonitor.CarChargingState.NO_CAR) {
            result = ChargeState.NoCarConnected
        } else { // carConnected
            result = enterCarConnected()
        }
        // start receiving state change events from wallbox monitor
        WallboxMonitor.monitor.subscribeState this
        result
    }

    private exitActive() {
        WallboxMonitor.monitor.unsubscribeState this

    }

    private ChargeState overrideExtChargeCmd() {
        enterCarConnected(false)
    }

    private ChargeState enterCarConnected(boolean extCmdHasPrio = true) {
        // remember original chargingState and avoid getting it sent repeatedly
        actualCarChargingState = WallboxMonitor.monitor.current.state
        def externalStopCmd = extCmdHasPrio &&
                (actualCarChargingState == WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_CAR ||
                        actualCarChargingState == WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_APP)
        println "\t\t ecc: set actualCarChargingState to $actualCarChargingState,\n\t\t " +
                "externalStopCmd: $externalStopCmd, chargeCmd: $chargeCmd"
        if (externalStopCmd ||
                actualCarChargingState == WallboxMonitor.CarChargingState.FULLY_CHARGED ||
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
                    if (actualCarChargingState in [
                            WallboxMonitor.CarChargingState.WAIT_CAR,
                            WallboxMonitor.CarChargingState.CHARGING,
                            WallboxMonitor.CarChargingState.CHARGING_ANYWAY]) {
                        ChargeState.WaitForExtCharge
                    } else {
                        ChargeState.NoSurplus
                    }
                    break
            }
        }
    }

    private execStop() {
        switch (chargeState) {
            case ChargeState.WaitForExtCharge:
            case ChargeState.HasSurplus:
                stopCharging()
            case ChargeState.NoSurplus:
                chargeStrategy.stopStrategy()
                break
            case ChargeState.ChargeTibber:
                stopTibberStrategy()
                break
            case ChargeState.ChargeAnyway:
                stopCharging()
                break
        }
        chargeState = ChargeState.ChargingStopped
    }

    /**
     * If charging was stopped by program, external start command
     * from car or app will override program stop with default
     */
    private ChargeCommand forceDefault() {
//        if (chargeCmd == ChargeCommand.CHARGE_STOP) {
        chargeCmd = defaultChargeCmd
//            ChargeCommand.CHARGE_ANYWAY
//        }
        chargeCmd
    }

    private stopCharging() {
//        print " -stop charging- "
        Wallbox.wallbox.stopCharging()
        setCurrent(0)
    }

    private startCharging() {
//        print " -start charging- "
        Wallbox.wallbox.startCharging()
//        println " --> $Wallbox.wallbox.wallboxValues"
    }

    private setCurrent(int amp = 0) {
//        print " -set current to $amp- "
        Wallbox.wallbox.chargingCurrent = amp
//        println " --> $Wallbox.wallbox.wallboxValues"
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
                manager.shutDown()
                WallboxMonitor.monitor.shutdown()
                PowerMonitor.monitor.shutdown()
                Thread.sleep 1000
                println ' done'
            }
        })
        PvChargeStrategyParams params =
                new PvChargeStrategyParams(toleranceStackSize: 5, batStartHysteresis: 0, maxBatUnloadPower: 2000)
        PvChargeStrategySM.chargeStrategy.params = params
        // activate manager first
        manager.active = true
        // let it initialize a while, then set command
        Thread.sleep 3000
        manager.takeChargeCmd(ChargeCommand.CHARGE_PV_SURPLUS)
        for (i in 1..90) {
            Thread.sleep(1 * 60 * 1000) // 1 minute
            println "-----------------> running $i minute${i > 1?'s':''} <--------------------------"
        }
//        Thread.sleep(2 * 60 * 60 * 1000) // 2 hours
        manager.shutDown()
        PeriodicExecutor.shutdown()
        println "Testrun finished"
    }
}
