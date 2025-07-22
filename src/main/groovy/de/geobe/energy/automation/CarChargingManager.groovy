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
import de.geobe.energy.recording.LogMessageRecorder
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
class CarChargingManager implements PowerValueSubscriber {

    private static CarChargingManager carChargingManager

    static enum ChargeManagerState {
        Inactive,
        NoCarConnected,     //Active.NoCarConnected
        FullyCharged,       // Active.FullyCharged
        ChargeTibber,         // Active.Chargeable.ChargeTibber
        ChargeAnyway,         // Active.Chargeable.ChargeAnyway
        ChargingStopped,      // Active.Chargeable.ChargingStopped
        ChargeSurplus,          // Active.Chargeable.ChargePvSurplus.ChargeSurplus
        NoSurplus,              // Active.Chargeable.ChargePvSurplus.NoSurplus
        StartChargeSurplus,     // Active.Chargeable.ChargePvSurplus.StartChargeSurplus
     }

    static enum ChargeManagerStrategy {
        CHARGE_PV_SURPLUS,
        CHARGE_TIBBER,
        CHARGE_ANYWAY,
        CHARGE_STOP
    }

    static enum ChargeEvent {
        CarCharging,        // events generated from WallboxMonitor state changes
        CarStopsCharging,
        CarDisconnected,
        CarIsConnected,
        FullyCharged,
        StoppedAgain,
        StartedAgain,
        Activate,           // events from user interface
        Deactivate,
        StopCharging,
        ChargeCommandChanged,
        Surplus,            // events from PvChargeStrategy
        NoSurplus,
        TibberGo,           // events from TibberChargeStrategy
        TibberStop,
        MonitorException,    // error event
        Ignore               //  nothing to do
    }

    static record EventMessage(
            ChargeEvent event = ChargeEvent.Ignore,
            int param = 0
    ) {}

    private ChargeManagerState chargeState = ChargeManagerState.Inactive
    private ChargeManagerStrategy chargeManagerStrategy = ChargeManagerStrategy.CHARGE_STOP
    private ChargeManagerStrategy defaultChargeManagerStrategy = ChargeManagerStrategy.CHARGE_PV_SURPLUS
    private int anywayAmps = Wallbox.wallbox.minCurrent
    private PvChargeStrategy chargeStrategy
    private CarChargingState currentCarChargingState = CarChargingState.UNDEFINED
    private boolean managerIsActive = false

    static synchronized getCarChargingManager() {
        if (!carChargingManager) {
            carChargingManager = new CarChargingManager()
        }
        carChargingManager
    }

    private CarChargingManager() {
        chargeStrategy = PvChargeStrategy.chargeStrategy
        chargeStrategy.enableStrategy this
        PowerMonitor.monitor.subscribe(this)
    }

    //
    // asynchronous methods called from PowerManager
    //

    @Override
    void takePMValues(PMValues pmValues) {
        if (managerIsActive) {
            evaluateAndExecute(pmValues.chargingState)
        } else {
            currentCarChargingState = pmValues.chargingState
        }
        // let PvChargeStrategy evaluate energy values even if manager not active
        EventMessage eventMessage = chargeStrategy.evalPMValues(pmValues)
        if (managerIsActive && eventMessage.event != ChargeEvent.Ignore) {
            evaluateAndExecute(eventMessage.event, eventMessage.param)
        }
    }

    @Override
    void takeMonitorException(Exception exception) {
        executeEvent(ChargeEvent.MonitorException)
    }

    @Override
    void resumeAfterMonitorException() {
        // handled elsewhere
    }

    //
    // asynchronous methods called directly or indirectly by ui
    //

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

    @ActiveMethod(blocking = true)
    void setActive(boolean active) {
        if (active) {
            executeEvent(ChargeEvent.Activate)
        } else {
            executeEvent(ChargeEvent.Deactivate)
        }
    }

    @ActiveMethod(blocking = true)
    void shutDown() {
        executeEvent(ChargeEvent.Deactivate)
        println "manager shutdown"
//        WallboxMonitor.monitor.shutdown()
    }

    @ActiveMethod
    void setDefaultChargeManagerStrategy(ChargeManagerStrategy cmd) {
        defaultChargeManagerStrategy = cmd
    }

    def setAnywayAmps(int amps) {
        anywayAmps = Math.min(Wallbox.wallbox.maxCurrent,
                Math.max(Wallbox.wallbox.minCurrent, amps))
    }

    /**
     * Transform changes in CarChangingState into events of CarChargingManager and
     * execute state chart
     * @param carState current CarChargingState
     */
    void evaluateAndExecute(CarChargingState carState) {
        CarChargingState newState = carState
        if (newState != currentCarChargingState) {
//            println "WB state: $carState -> "
            currentCarChargingState = newState
            switch (carState) {
            // car not connected, just connecting, or software newly started
                case WallboxMonitor.CarChargingState.UNDEFINED:
                case WallboxMonitor.CarChargingState.WAIT_CAR:
                case WallboxMonitor.CarChargingState.NO_CAR:
                    executeEvent(ChargeEvent.CarDisconnected)
                    break
                    // car seems to be ready to charge, maybe "fully charged" is not yet identified after pgm start
                case WallboxMonitor.CarChargingState.NOT_CHARGING:
                    executeEvent(ChargeEvent.CarIsConnected)
                    break
                    // car is charging
                case WallboxMonitor.CarChargingState.CHARGE_REQUEST:
                case WallboxMonitor.CarChargingState.STARTUP_CHARGING:
                case WallboxMonitor.CarChargingState.CHARGING:
                case WallboxMonitor.CarChargingState.FINISH_CHARGING:
                    executeEvent(ChargeEvent.CarCharging)
                    break
                case WallboxMonitor.CarChargingState.FULLY_CHARGED:
                    executeEvent(ChargeEvent.FullyCharged)
                    break
                case WallboxMonitor.CarChargingState.CHARGE_STOPPING:
                    executeEvent(ChargeEvent.CarStopsCharging)
                    break
                case WallboxMonitor.CarChargingState.START_AGAIN:
                    executeEvent(ChargeEvent.StartedAgain)
                    break
                case WallboxMonitor.CarChargingState.STOP_AGAIN:
                    executeEvent(ChargeEvent.StoppedAgain)
            }
        }
    }

    /**
     * implementation of the state machine as described in uml model CarChargingMannager.puml
     * @param event
     * @param param
     */
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
                    case ChargeManagerState.StartChargeSurplus:
                    case ChargeManagerState.ChargeSurplus:
                        stopCharging()
                    case ChargeManagerState.NoSurplus:
                        PvChargeStrategy.chargeStrategy.deactivateStrategy()
                        break
                }
                exitActive()
                chargeState = ChargeManagerState.Inactive
                break
            case ChargeEvent.CarIsConnected:
                switch (chargeState) {
                    case ChargeManagerState.NoCarConnected:
                        chargeState = initChargeable()
                }
                break
            case ChargeEvent.CarCharging:
                switch (chargeState) {
                    case ChargeManagerState.NoCarConnected:
                        chargeState = initChargeable()
                        break
                    case ChargeManagerState.ChargingStopped:
                        chargeState = initChargeable()
                        break
                    case ChargeManagerState.StartChargeSurplus:
                        chargeState = ChargeManagerState.ChargeSurplus
                        break
                }
                break
            case ChargeEvent.StartedAgain:
                switch (chargeState) {
                    case ChargeManagerState.StartChargeSurplus:
                        break           // ignore, artifact of state change
                    case ChargeManagerState.NoSurplus:
                        stopCharging()
                    case ChargeManagerState.ChargingStopped:
//                        initChargeable()
//                        break
                    default:
                        // ToDo implement strategies for each Chargexxx state with a reinit method
                        initChargeable()
                }
                break
            case ChargeEvent.StoppedAgain:
                switch (chargeState) {
                    case ChargeManagerState.StartChargeSurplus:
//                    case ChargeManagerState.NoSurplus:
                        break           // ignore, artifact of state change
                    default:
                        execStop()
                        chargeState = ChargeManagerState.ChargingStopped
                }
                break
            case ChargeEvent.CarDisconnected:
                execStop()
                chargeState = ChargeManagerState.NoCarConnected
                break
            case ChargeEvent.ChargeCommandChanged:
                switch (chargeState) {
                    case ChargeManagerState.ChargeTibber:
                        stopTibberStrategy()
                        chargeState = initChargeable()
                        break
                    case ChargeManagerState.ChargeAnyway:
                        stopCharging()
                        chargeState = initChargeable()
                        break
                    case ChargeManagerState.ChargingStopped:
                        chargeState = initChargeable()
                        break
                    case ChargeManagerState.ChargeSurplus:
                    case ChargeManagerState.StartChargeSurplus:
                        stopCharging()
                    case ChargeManagerState.NoSurplus:
                        PvChargeStrategy.chargeStrategy.deactivateStrategy()
                        chargeState = initChargeable()
                        break
                    case ChargeManagerState.FullyCharged:
                    case ChargeManagerState.NoCarConnected:
                    case ChargeManagerState.Inactive:
                        break
                }
                break
            case ChargeEvent.Surplus:
                switch (chargeState) {
                    case ChargeManagerState.NoSurplus:
                        chargeState = ChargeManagerState.StartChargeSurplus
                        setCurrent(param)
                        startCharging()
                        break
                    case ChargeManagerState.StartChargeSurplus:
                        // check for missed event
                        if (WallboxMonitor.monitor.current.state == WallboxMonitor.CarChargingState.CHARGING) {
                            chargeState = ChargeManagerState.ChargeSurplus
                        }
                        // blocking, if not started charging by car
                        // just go to charging state hasSurplus without adjusting current
//                        chargeState = ChargeManagerState.ChargeSurplus
//                        break
                    case ChargeManagerState.ChargeSurplus:
                        setCurrent(param)
                        break
                }
                break
            case ChargeEvent.NoSurplus:
                switch (chargeState) {
                    case ChargeManagerState.ChargeSurplus:
                    case ChargeManagerState.StartChargeSurplus:
                        stopCharging()
                        chargeState = ChargeManagerState.NoSurplus
                        break
                    case ChargeManagerState.NoSurplus:
                        break
                }
                break
            case ChargeEvent.StopCharging:
                execStop()
                chargeState = ChargeManagerState.ChargingStopped
                break
            case ChargeEvent.FullyCharged:
                execStop()
                chargeState = ChargeManagerState.FullyCharged
        }
        LogMessageRecorder.logMessage "CarChargingManager -> $evTrigger $chargeState @ $currentCarChargingState".toString()
    }

    /**
     * Entering superstate Active, find out substate to go to and execute onEntry actions.
     * See state chart for details.
     */
    private ChargeManagerState enterActive() {
        ChargeManagerState result
        managerIsActive = true
        if (currentCarChargingState == CarChargingState.NO_CAR) {
            result = ChargeManagerState.NoCarConnected
        } else { // carConnected
            result = initChargeable()
        }
        result
    }

    private exitActive() {
        managerIsActive = false
    }

    /**
     * Superstate, find out substate to go to. See state chart.<br>
     * Ways that lead to this state:
     * <ol>
     *     <li>Triggered by activate event and a car already connected to the wallbox ->
     *         then the car is either charging or not charging as selected by external controls,
     *         e.g. in the car or in a smartphone app. WallboxManager should have detected this situation
     *         and provide appropriate state information.
     *     </li><li>
     *         Triggered by carConnected event in state NoCarConnected -> external charge state is
     *         controlled by default wallbox behaviour which is startCharging.
     *     </li>
     * </ol>
     * So in any case go to substate defined by current defaultChargeManagerStrategy,
     * usually CHARGE_PV_SURPLUS.
     *
     * @return none
     */
    private ChargeManagerState initChargeable() {
        if (currentCarChargingState in [CarChargingState.FULLY_CHARGED, CarChargingState.UNDEFINED] ||
                chargeManagerStrategy == ChargeManagerStrategy.CHARGE_STOP) {
            stopCharging()
            ChargeManagerState.ChargingStopped
        } else {
            switch (defaultChargeManagerStrategy) {
                case ChargeManagerStrategy.CHARGE_ANYWAY:
                    startCharging()
                    setCurrent(anywayAmps)
                    ChargeManagerState.ChargeAnyway
                    break
                case ChargeManagerStrategy.CHARGE_TIBBER:
                    startTibberStrategy()
                    ChargeManagerState.ChargeTibber
                    break
                case ChargeManagerStrategy.CHARGE_PV_SURPLUS:
//                    chargeStrategy = PvChargeStrategy.chargeStrategy
                    chargeStrategy.activateStrategy()
                    ChargeManagerState.NoSurplus
                    break
            }
        }
    }

    private execStop() {
        switch (chargeState) {
            case ChargeManagerState.StartChargeSurplus:
            case ChargeManagerState.ChargeSurplus:
                stopCharging()
            case ChargeManagerState.NoSurplus:
                chargeStrategy.deactivateStrategy()
                break
            case ChargeManagerState.ChargeTibber:
                stopTibberStrategy()
                break
            case ChargeManagerState.ChargeAnyway:
                stopCharging()
                break
        }
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

    private setCurrent(int amp = 0) {
        WallboxMonitor.monitor.current = amp
    }

    private startTibberStrategy() {
        println "start tibber monitor"
    }

    private stopTibberStrategy() {
        println "stop tibber monitor"
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
                detail = chargeStrategy?.state ? 'active': 'inactive'
                break
            default: detail = 'inactive'
        }
        detail
    }

/*
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
            println "-----------------> running $i minute${i > 1 ? 's' : ''} <--------------------------"
        }
//        Thread.sleep(2 * 60 * 60 * 1000) // 2 hours
        carChargingManager.shutDown()
        PeriodicExecutor.shutdown()
        println "Testrun finished"
    }
*/
}
