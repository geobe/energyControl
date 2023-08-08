/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2023. Georg Beier. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
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

package de.geobe.energy.web

import de.geobe.energy.automation.CarChargingManager
import de.geobe.energy.automation.ChargeStrategy
import de.geobe.energy.automation.PMValues
import de.geobe.energy.automation.PowerMonitor
import de.geobe.energy.automation.PowerValueSubscriber
import de.geobe.energy.automation.WallboxMonitor
import de.geobe.energy.automation.WallboxStateSubscriber
import de.geobe.energy.e3dc.PowerValues
import de.geobe.energy.go_e.WallboxValues
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.template.PebbleTemplate
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage
import org.eclipse.jetty.websocket.api.annotations.WebSocket
import spark.Request
import spark.Response
import spark.Route
import spark.Spark

import java.util.concurrent.ConcurrentLinkedDeque

@WebSocket
class ValueController implements PowerValueSubscriber, WallboxStateSubscriber {

    /** access to energy values */
    private PowerMonitor powerMonitor = PowerMonitor.monitor
    private WallboxMonitor wbMonitor = WallboxMonitor.monitor
    /** shortcut to car charging manager singleton */
    private carChargingManager = CarChargingManager.carChargingManager
    /** current Values */
    volatile WallboxValues wbValues
    volatile PowerValues pwrValues
    volatile WallboxMonitor.CarChargingState carChargingState
    volatile CarChargingManager.ChargeManagerState chargeManagerState
    volatile CarChargingManager.ChargeStrategy chargeStrategy
    /** store for websocket sessions */
    private ConcurrentLinkedDeque<Session> sessions = new ConcurrentLinkedDeque<>()
    /** translate pebble templates to java code */
    PebbleEngine engine = new PebbleEngine.Builder().build()

    def index = engine.getTemplate('template/index.peb')
    def stateButtons = engine.getTemplate('template/statebuttons.peb')
    def dashboard = engine.getTemplate('template/dashboard.peb')
    def powerValuesTemplate = engine.getTemplate('template/powervalues.peb')
    def chargeInfo = engine.getTemplate('template/chargeinfo.peb')

    @Override
    void takePMValues(PMValues pmValues) {
        wbValues = pmValues.getWallboxValues()
        pwrValues = pmValues.powerValues
        chargeStrategy = carChargingManager.chargeStrategy
        chargeManagerState = carChargingManager.chargeManagerState
        updateWsValues(powerValuesString() + chargeInfoString())
    }

    @Override
    void takeWallboxState(WallboxMonitor.CarChargingState carState) {
        carChargingState = carState
        updateWsValues(chargeInfoString())
    }

    void init() {
        pwrValues = powerMonitor.current
        wbValues = wbMonitor.current.values
        carChargingState = wbMonitor.current.state
        chargeStrategy = carChargingManager.chargeStrategy
        chargeManagerState = carChargingManager.chargeManagerState
//        println "wallbox: $wbValues \ncar state: $carChargingState"
        powerMonitor.initCycle(5, 5)
        powerMonitor.subscribe(this)
    }

    /***************** Routing methods **************/

    Route indexRoute = { Request req, Response resp ->
        def strategy = chargeStrategy
        def ctx = setChargeCommandContext(strategy)
        ctx.putAll setChargeManagementContext(chargeManagerState)
        ctx['websiteTitle'] = 'PowerManagement'
        ctx.putAll(joinDashboardContext())
        resp.status 200
        streamOut(index, ctx)
    }

    Route dashboardRoute = { Request req, Response resp ->
        def accept = req.headers('Accept')
//        def state = chargeStrategy
        resp.status 200
//        if (accept.endsWith('json')) {
//            def json = [
//                    state : state,
//                    status: 'OK'
//            ]
//            JsonOutput.toJson json
//        } else {
        def ctx = joinDashboardContext()
        streamOut(dashboard, ctx)
//        }
    }

    Route wallboxStrategyRoute = { Request req, Response resp ->
        def accept = req.headers('Accept')
        def chargeStrategy = chargeStrategy
        resp.status 200
        def ctx = setChargeCommandContext(chargeStrategy)
        ctx.putAll setChargeManagementContext(chargeManagerState)
        streamOut(stateButtons, ctx)
    }

    Route wallboxStrategyPost = { Request req, Response resp ->
        def accept = req.headers('Accept')
        def action = req?.params(':action')
        def mg = mgmtStrings
        def cc = chargeComandStrings
        switch (action) {
            case mg.mgmtActivate:
                carChargingManager.setActive(true)
                break
            case mg.mgmtDeactivate:
                carChargingManager.setActive(false)
                break
            case cc.cmdSurplus:
                carChargingManager.takeChargeCmd(
                        CarChargingManager.ChargeStrategy.CHARGE_PV_SURPLUS)
                break
            case cc.cmdTibber:
                carChargingManager.takeChargeCmd(
                        CarChargingManager.ChargeStrategy.CHARGE_TIBBER)
                break
            case cc.cmdAnyway:
                carChargingManager.takeChargeCmd(
                        CarChargingManager.ChargeStrategy.CHARGE_ANYWAY)
                break
            case cc.cmdStop:
                carChargingManager.takeChargeCmd(
                        CarChargingManager.ChargeStrategy.CHARGE_STOP)
                break
            default:
                throw new RuntimeException("unexpected action $action")
        }
        resp.status 200
        chargeStrategy = carChargingManager.chargeStrategy
        chargeManagerState = carChargingManager.chargeManagerState
        def upd = statesInfoString() + chargeInfoString()
        updateWsValues(upd)
        ''
    }

    /***************** webservice methods **************/

    @OnWebSocketConnect
    void onConnect(Session user) {
        sessions.add user
    }

    @OnWebSocketClose
    void onClose(Session user, int statusCode, String reason) {
        if (sessions.remove user) {
//            println "removed on close: $user, status: $statusCode, $reason"
        }
    }

    @OnWebSocketMessage
    void onMessage(Session user, String message) {
        println "message from $user: $message"
    }

    def updateWsValues(String out) {
        sessions.findAll { it.isOpen() }.each { socket ->
            try {
                socket.remote.sendString(out)
            } catch (Exception ex) {
                ex.printStackTrace()
            }
        }
    }

    def powerValuesString() {
        def ctx = [swapOob: /hx-swap-oob='true'/]
        ctx.putAll(powerStrings)
        ctx.putAll(powerValues())
        streamOut(powerValuesTemplate, ctx)
    }

    def chargeInfoString() {
        def ctx = [swapOob: /hx-swap-oob='true'/]
        ctx.putAll(stateStrings)
        ctx.putAll(mgmtStrings)
        ctx.putAll(stateValues())
        streamOut(chargeInfo, ctx)
    }

    def statesInfoString() {
        def ctx = [swapOob: /hx-swap-oob='true'/]
        ctx.putAll(setChargeCommandContext(chargeStrategy))
        ctx.putAll(setChargeManagementContext(chargeManagerState))
        streamOut(stateButtons, ctx)
    }

    /********** helper methods ******/

    /**
     * populate template and stream out into a string
     * @param template
     * @param ctx Map of context values for the template
     * @return
     */
    def streamOut(PebbleTemplate template, Map<Object, Object> ctx) {
        def out = new StringWriter()
        template.evaluate(out, ctx)
        out.toString()
    }

    def setChargeManagementContext(CarChargingManager.ChargeManagerState state) {
        def ctx = [:]
        ctx.putAll mgmtStrings
        switch (state) {
            case CarChargingManager.ChargeManagerState.Inactive:
                ctx.checkedInactive = 'checked'
                ctx.mgmtColor = 'w3-light-grey'
                break
            default:
                ctx.checkedActive = 'checked'
                ctx.mgmtColor = 'w3-yellow'
        }
        ctx
    }

    def setChargeCommandContext(CarChargingManager.ChargeStrategy strategy) {
        def ctx = [:]
        ctx.putAll chargeComandStrings
        switch (strategy) {
            case CarChargingManager.ChargeStrategy.CHARGE_PV_SURPLUS:
                ctx['checkedSurplus'] = 'checked'
                break
            case CarChargingManager.ChargeStrategy.CHARGE_TIBBER:
                ctx['checkedTibber'] = 'checked'
                break
            case CarChargingManager.ChargeStrategy.CHARGE_ANYWAY:
                ctx['checkedAnyway'] = 'checked'
                break
            case CarChargingManager.ChargeStrategy.CHARGE_STOP:
                ctx['checkedStop'] = 'checked'
                break
            default:
                throw new IllegalArgumentException()
        }
        ctx
    }

    final mgmtStrings = [
            mgmtActive           : 'Lademanagement aktiv',
            mgmtInactive         : 'Lademanagement inaktiv',
            mgmtActivate         : 'Activate',
            mgmtDeactivate       : 'Deactivate',
            mgmtActivateConfirm  : 'Lademanagement aktivieren?',
            mgmtDeactivateConfirm: 'Lademanagement deaktivieren?',
    ]

    final chargeComandStrings = [
            cmdSurplus           : 'CHARGE_PV_SURPLUS',
            cmdTibber            : 'CHARGE_TIBBER',
            cmdAnyway            : 'CHARGE_ANYWAY',
            cmdStop              : 'CHARGE_STOP',
            wallboxStrategyHeader: 'Ladesteuerung',
            noStrategy           : 'Steuerung aus',
            pvStrategy           : 'Solar laden',
            tibberStrategy       : 'Tibber laden',
            anywayStrategy       : 'Sofort laden',
            stopStrategy         : 'Nicht laden',
    ]

    def LinkedHashMap<Object, Object> joinDashboardContext() {
        def ctx = [:]
        ctx.putAll(headingStrings)
        ctx.putAll(stateStrings)
        ctx.putAll(powerStrings)
        ctx.putAll(mgmtStrings)
        ctx.putAll(stateValues())
        ctx.putAll(powerValues())
        ctx
    }

    /**
     * translation values for dashboard template abel parameters (prepare for i18n)
     */
    final stateStrings = [
            chargingStateLabel     : 'Auto Ladestatus',
            chargeManagerStateLabel: 'Lademanager',
            chargeStrategyLabel    : 'Auto Ladestrategie',
            tibberStrategyLabel    : 'Tibber Ladestrategie',
            tibberPriceLabel       : 'Tibber Preis'
    ]

    final headingStrings = [
            powerTitle : 'Energiewerte',
            statesTitle: 'Statuswerte',
    ]

    final powerStrings = [
            pvLabel     : 'PV',
            gridLabel   : 'Netz',
            batteryLabel: 'Batterie',
            homeLabel   : 'Haus',
            carLabel    : 'Auto',
            socLabel    : 'Speicher',
    ]

    /** Translations for various state values (enum values) */
    def stateTx = [
            // CarChargingManager.ChargeManagerState
            Inactive               : 'inaktiv',
            NoCarConnected         : 'kein Auto',
            ChargeTibber           : 'Tibber laden',
            ChargeAnyway           : 'Sofort laden',
            ChargingStopped        : 'Nicht laden',
            HasSurplus             : 'Solar Überschuss',
            NoSurplus              : 'Kein Solar Überschuss',
            WaitForExtCharge       : 'Auf Befehl warten',
            // CarChargingManager.ChargeStrategy
            CHARGE_PV_SURPLUS      : 'Solar laden',
            CHARGE_TIBBER          : 'Tibber laden',
            CHARGE_ANYWAY          : 'Sofort laden',
            CHARGE_STOP            : 'Nicht laden',
            // WallboxMonitor.CarChargingState
            NO_CAR                 : 'kein Auto',
            WAIT_CAR               : 'Auf Auto warten',
            CHARGING               : 'lädt',
            CHARGING_ANYWAY        : 'sofort laden',
            FULLY_CHARGED          : 'aufgeladen',
            CHARGING_STOPPED_BY_APP: 'Stopp (App)',
            CHARGING_STOPPED_BY_CAR: 'Stopp (Auto)',
    ]

    /** realtime values for dashboard */
    def stateValues() {
        def ctx = [
                chargingStateValue     : stateTx.get(carChargingState.toString()),
                chargeStrategyValue    : stateTx.get(chargeStrategy.toString()),
                chargeManagerStateValue: stateTx.get(chargeManagerState.toString()),
                tibberStrategyValue    : 'none',
                tibberPriceValue       : (int) (Math.random() * 30 + 15)
        ]
    }

    def powerValues() {
        def ctx = [
                pvValue     : pwrValues.powerSolar,
                gridValue   : pwrValues.powerGrid,
                batteryValue: pwrValues.powerBattery,
                homeValue   : pwrValues.consumptionHome,
                carValue    : wbValues.energy,
                socValue    : pwrValues.socBattery,
        ]
    }

}
