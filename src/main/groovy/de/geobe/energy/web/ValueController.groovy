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

import java.util.concurrent.ConcurrentLinkedDeque

@WebSocket
class ValueController implements PowerValueSubscriber, WallboxStateSubscriber {

    /** access to energy values */
    private PowerMonitor powerMonitor = PowerMonitor.monitor
    private WallboxMonitor wbMonitor = WallboxMonitor.monitor
    /** current Values */
    volatile WallboxValues wbValues
    volatile PowerValues pwrValues
    volatile WallboxMonitor.CarChargingState carChargingState
    /** store for websocket sessions */
    private ConcurrentLinkedDeque<Session> sessions = new ConcurrentLinkedDeque<>()
    /** translate pebble templates to java code */
    PebbleEngine engine = new PebbleEngine.Builder().build()

    def index = engine.getTemplate('template/index.peb')
    def stateButtons = engine.getTemplate('template/statebuttons.peb')
    def dashboard = engine.getTemplate('template/dashboard.peb')
    def powerValuesTemplate = engine.getTemplate('template/powervalues.peb')

    @Override
    void takePMValues(PMValues pmValues) {
        wbValues = pmValues.getWallboxValues()
        pwrValues = pmValues.powerValues
        updatePowerValues()
    }

    @Override
    void takeWallboxState(WallboxMonitor.CarChargingState carState) {
        carChargingState = carState
    }

    void init() {
        pwrValues = powerMonitor.current
        wbValues = wbMonitor.current.values
        carChargingState = wbMonitor.current.state
//        println "wallbox: $wbValues \ncar state: $carChargingState"
        powerMonitor.initCycle(5, 5)
        powerMonitor.subscribe(this)
    }

    /***************** Routing methods **************/

    Route indexRoute = { Request req, Response resp ->
        def state = randomCmd()
        def ctx = setChargeCommandContext(state)
        ctx['websiteTitle'] = 'PowerManagement'
        ctx.putAll(joinDashboardContext())
        resp.status 200
        streamOut(index, ctx)
    }

    Route dashboardRoute = { Request req, Response resp ->
        def accept = req.headers('Accept')
        def state = randomCmd()
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
        def chargeCommand = randomCmd()
        resp.status 200
//        if (accept.endsWith('json')) {
//            def json = [
//                    state : chargeCommand,
//                    status: 'OK'
//            ]
//            JsonOutput.toJson json
//        } else {
        def ctx = setChargeCommandContext(chargeCommand)
        streamOut(stateButtons, ctx)
//        }
    }

    /***************** webservice methods **************/

    @OnWebSocketConnect
    void onConnect(Session user) {
        sessions.add user
//        try {
//            user.remote.sendString(resp('Connected', 'connected2'))
//        } catch (Exception e) {
//            e.printStackTrace()
//        }
    }

    @OnWebSocketClose
    void onClose(Session user, int statusCode, String reason) {
        if (sessions.remove user) {
            println "removed on close: $user, status: $statusCode, $reason"
        }
    }

    @OnWebSocketMessage
    void onMessage(Session user, String message) {
        println "message from $user: $message"
//        try {
//            user.remote.sendString(resp(message, 'content'))
//        } catch (Exception e) {
//            e.printStackTrace()
//        }
    }

    def updatePowerValues() {
        def out = powerValuesString()
        sessions.findAll {it.isOpen()}.each {socket ->
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

    private def cmdIndex = 5

    def randomCmd() {
//        int randomIndex = ThreadLocalRandom.current().nextInt(1, 4)
        if (++cmdIndex >= 4) cmdIndex = 0
        CarChargingManager.ChargeCommand.values()[cmdIndex]
    }

    def Map<Object, Object> setChargeCommandContext(CarChargingManager.ChargeCommand cmd) {
        def ctx = chargeComandStrings()
        ctx['cmdSurplus'] = CarChargingManager.ChargeCommand.CHARGE_PV_SURPLUS
        ctx['cmdTibber'] = CarChargingManager.ChargeCommand.CHARGE_TIBBER
        ctx['cmdAnyway'] = CarChargingManager.ChargeCommand.CHARGE_ANYWAY
        ctx['cmdStop'] = CarChargingManager.ChargeCommand.CHARGE_STOP
        switch (cmd) {
            case CarChargingManager.ChargeCommand.CHARGE_PV_SURPLUS:
                ctx['checkedSurplus'] = 'checked'
                ctx['cmdColor'] = 'w3-yellow'
                break
            case CarChargingManager.ChargeCommand.CHARGE_TIBBER:
                ctx['checkedTibber'] = 'checked'
                ctx['cmdColor'] = 'w3-lime'
                break
            case CarChargingManager.ChargeCommand.CHARGE_ANYWAY:
                ctx['checkedAnyway'] = 'checked'
                ctx['cmdColor'] = 'w3-orange'
                break
            case CarChargingManager.ChargeCommand.CHARGE_STOP:
                ctx['checkedStop'] = 'checked'
                ctx['cmdColor'] = 'w3-light-grey'
                break
            default:
                throw new IllegalArgumentException()
        }
        ctx
    }

    def final chargeComandStrings() {
        def ctx = [
                wallboxStrategyHeader: 'Ladestrategie',
                pvStrategy           : 'Solar laden',
                tibberStrategy       : 'Tibber laden',
                anywayStrategy       : 'Sofort laden',
                stopStrategy         : 'Nicht laden',
        ]
        ctx
    }

    def LinkedHashMap<Object, Object> joinDashboardContext() {
        def ctx = [:]
        ctx.putAll(headingStrings)
        ctx.putAll(stateStrings)
        ctx.putAll(powerStrings)
        ctx.putAll(stateValues())
        ctx.putAll(powerValues())
        ctx
    }

    /**
     * translation values for dashboard template abel parameters (prepare for i18n)
     */
    final stateStrings = [
            chargingStateLabel   : 'Auto Ladestatus',
            chargingStrategyLabel: 'Auto Ladestrategie',
            tibberStrategyLabel  : 'Tibber Ladestrategie',
            tibberPriceLabel     : 'Tibber Preis'
    ]

    final headingStrings = [
            powerTitle           : 'Energiewerte',
            statesTitle          : 'Statuswerte',
    ]

    final powerStrings = [
            pvLabel              : 'PV',
            gridLabel            : 'Netz',
            batteryLabel         : 'Batterie',
            homeLabel            : 'Haus',
            carLabel             : 'Auto',
            socLabel             : 'Speicher',
    ]

    /** Translations for various state values (enum values) */
    def stateTx() {
        def tx = [:]
        tx.put(CarChargingManager.ChargeState.Inactive.toString(), 'inaktiv')
        tx.put(CarChargingManager.ChargeState.NoCarConnected.toString(), 'kein Auto')
        tx.put(CarChargingManager.ChargeState.ChargeTibber.toString(), 'Tibber laden')
        tx.put(CarChargingManager.ChargeState.ChargeAnyway.toString(), 'Sofort laden')
        tx.put(CarChargingManager.ChargeState.ChargingStopped.toString(), 'Nicht laden')
        tx.put(CarChargingManager.ChargeState.HasSurplus.toString(), 'Solar Überschuss')
        tx.put(CarChargingManager.ChargeState.NoSurplus.toString(), 'Kein Solar Überschuss')
        tx.put(CarChargingManager.ChargeState.WaitForExtCharge.toString(), 'Auf Befehl warten')
        tx.put(CarChargingManager.ChargeCommand.CHARGE_PV_SURPLUS.toString(), 'Solar laden')
        tx.put(CarChargingManager.ChargeCommand.CHARGE_TIBBER.toString(), 'Tibber laden')
        tx.put(CarChargingManager.ChargeCommand.CHARGE_ANYWAY.toString(), 'Sofort laden')
        tx.put(CarChargingManager.ChargeCommand.CHARGE_STOP.toString(), 'Nicht laden')
        tx.put(WallboxMonitor.CarChargingState.NO_CAR.toString(), 'kein Auto')
        tx.put(WallboxMonitor.CarChargingState.WAIT_CAR.toString(), 'Auf Auto warten')
        tx.put(WallboxMonitor.CarChargingState.CHARGING.toString(), 'Wird geladen')
        tx.put(WallboxMonitor.CarChargingState.CHARGING_ANYWAY.toString(), 'wird sofort geladen')
        tx.put(WallboxMonitor.CarChargingState.FULLY_CHARGED.toString(), 'aufgeladen')
        tx.put(WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_APP.toString(), 'Stopp (App)')
        tx.put(WallboxMonitor.CarChargingState.CHARGING_STOPPED_BY_CAR.toString(), 'Stopp (Auto)')
        tx
    }

    /** realtime values for dashboard */
    def stateValues() {
        def ctx = [
                chargingStateValue   : stateTx().get(carChargingState.toString()),
                chargingStrategyValue: stateTx().get(CarChargingManager.ChargeCommand.CHARGE_PV_SURPLUS.toString()),
                tibberStrategyValue  : 'none',
                tibberPriceValue     : (int) (Math.random() * 30 + 15)
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
