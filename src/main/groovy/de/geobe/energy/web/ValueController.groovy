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

import de.geobe.energy.automation.*
import de.geobe.energy.e3dc.PowerValues
import de.geobe.energy.go_e.WallboxValues
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.template.PebbleTemplate
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WriteCallback
import org.eclipse.jetty.websocket.api.annotations.*
import spark.Request
import spark.Response
import spark.Route

import java.util.concurrent.ConcurrentLinkedDeque

@WebSocket
//@ActiveObject
class ValueController implements PowerValueSubscriber, WallboxStateSubscriber {

    ValueController(PebbleEngine engine) {
        this.engine = engine
    }

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
    // global display values
    volatile defaultUiLanguage = 'de'
    // display values that could be migrated to session variables
    volatile int graphDataSize = 180
    volatile int updateFrequency = 1
    volatile int updateCounter = 0
    volatile boolean updatePause = false
    volatile String uiLanguage = 'de'

    /** store for websocket sessions */
    private ConcurrentLinkedDeque<Session> sessions = new ConcurrentLinkedDeque<>()

    /** all static template strings for spark */
    UiStringsI18n stringsI18n = new UiStringsI18n()
    def tGlobal = stringsI18n.translationsFor(uiLanguage)
    /** more helper objects */
    EnergySettings es = new EnergySettings(this, tGlobal)
    GraphController gc = new GraphController(tGlobal)

    /** translate pebble templates to java code */
    PebbleEngine engine
    def index = engine.getTemplate('template/index.peb')
    def body = engine.getTemplate('template/bodyi18n.peb')
    def stateButtons = engine.getTemplate('template/statebuttons.peb')
    def dashboard = engine.getTemplate('template/dashboard.peb')
    def powerValuesTemplate = engine.getTemplate('template/powervalues.peb')
    def chargeInfo = engine.getTemplate('template/chargeinfo.peb')
//    def settings = engine.getTemplate('template/settings.peb')
    def settingsform = engine.getTemplate('template/settingsform.peb')
    def graph = engine.getTemplate('template/graph.peb')
    def graphData = engine.getTemplate('template/graphdata.peb')
    def graphCommands = engine.getTemplate('template/graphcommands.peb')

    void init() {
        pwrValues = powerMonitor.current
        wbValues = wbMonitor.current.values
        short cHome = pwrValues.consumptionHome - wbValues.energy
        Snapshot snapshot = new Snapshot(
                pwrValues.timestamp.toEpochMilli(),
                (short) pwrValues.powerSolar,
                (short) pwrValues.powerBattery,
                (short) pwrValues.powerGrid,
                cHome,
                wbValues.energy,
                (short) pwrValues.socBattery
        )
        gc.init(snapshot)
        carChargingState = wbMonitor.current.state
        chargeStrategy = carChargingManager.chargeStrategy
        chargeManagerState = carChargingManager.chargeManagerState
//        println "wallbox: $wbValues \ncar state: $carChargingState"
        powerMonitor.initCycle(5, 0)
        powerMonitor.subscribe(this)
        wbMonitor.subscribeState(this)
    }

    @Override
//    @ActiveMethod(blocking = false)
    void takePMValues(PMValues pmValues) {
        wbValues = pmValues.getWallboxValues()
        pwrValues = pmValues.powerValues
//        println "pwrValues: $pwrValues"
        gc.saveSnapshot(pwrValues, wbValues)
        chargeStrategy = carChargingManager.chargeStrategy
        chargeManagerState = carChargingManager.chargeManagerState
        updateWsValues(powerValuesString(tGlobal) + chargeInfoString(tGlobal))// + statesInfoString)
        updateWsValues(statesInfoString(tGlobal))
        updateCounter++
        if (! updatePause && updateCounter >= updateFrequency) {
            updateCounter = 0
            updateWsValues(graphInfoString(tGlobal, graphDataSize))
        }
    }

    @Override
//    @ActiveMethod(blocking = false)
    void takeWallboxState(WallboxMonitor.CarChargingState carState) {
        carChargingState = carState
//        updateWsValues(chargeInfoString())
        println "carChargingState changed: $carChargingState"
    }

    /***************** Routing methods **************/

    Route indexRoute = { Request req, Response resp ->
        def submission = req.queryParams()
        if (submission.contains('language')) {
            def language = req.queryParams('language')
            if(language) {
                tGlobal = stringsI18n.translationsFor(language)
                uiLanguage = language
                gc.updateDateFormat(uiLanguage)
            }
        }
        def ctx = bodyCtx(req, resp)
        ctx.put('newCanvas', true)
        streamOut(index, ctx)
    }

    Route languagePost = { Request req, Response resp ->
        def submission = req.queryParams()
        if (submission.contains('language')) {
            def language = req.queryParams('language')
            if(language) {
                tGlobal = stringsI18n.translationsFor(language)
                uiLanguage = language
                gc.updateDateFormat(uiLanguage)
            }
        }
//        resp.redirect("/".toString())
        def ctx = bodyCtx(req, resp)
        ctx.put('newCanvas', true)
        streamOut(body, ctx)
    }

    def bodyCtx(Request req, Response resp) {
        def strategy = chargeStrategy
        def ti18n = tGlobal
        def ctx = setChargeCommandContext(strategy, ti18n)
        ctx.putAll setChargeManagementContext(chargeManagerState, ti18n)
        ctx.putAll(joinDashboardContext(ti18n))
        ctx.putAll(es.settingsFormContext(ti18n))
        ctx.putAll(gc.createSnapshotCtx(graphDataSize, ti18n))
        ctx.putAll(gc.createSizeValues(graphDataSize))
        ctx.putAll(stringsI18n.i18nCtx(ti18n, uiLanguage))
        ctx
    }

    Route dashboardRoute = { Request req, Response resp ->
        def accept = req.headers('Accept')
        resp.status 200
        def ti18n = tGlobal
        def ctx = joinDashboardContext(ti18n)
        streamOut(dashboard, ctx)
    }

    Route wallboxStrategyRoute = { Request req, Response resp ->
        def accept = req.headers('Accept')
        resp.status 200
        def ti18n = tGlobal
        def ctx = setChargeCommandContext(chargeStrategy, ti18n)
        ctx.putAll setChargeManagementContext(chargeManagerState, ti18n)
        streamOut(stateButtons, ctx)
    }

    Route wallboxStrategyPost = { Request req, Response resp ->
        def accept = req.headers('Accept')
        def action = req?.params(':action')
        def ti18n = tGlobal
        def mg = ti18n.mgmtStrings
        def cc = ti18n.chargeComandStrings
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
        def upd = statesInfoString(ti18n) + chargeInfoString(ti18n)
        updateWsValues(upd)
        ''
    }

    Route energySettingsPost = { Request req, Response resp ->
        def accept = req.headers('Accept')
        def submission = req.queryParams()
        def formValues = [:]
        submission.each { param ->
            def val = req.queryParams(param)
            if (val?.isInteger()) {
                formValues.put(param, val.toInteger())
            }
        }
        def changed = es.processUpdate(formValues)
//        println formValues
        resp.status 200
        def ti18n = tGlobal
        def upd = streamOut(settingsform, es.settingsFormContext(ti18n))
        if (changed) {
            updateWsValues(upd)
            ''
        } else {
            upd
        }
    }

    Route graphPost = { Request req, Response resp ->
        def accept = req.headers('Accept')
        def params = evalGraphPost(req, resp)
        resp.status 200
        def ti18n = tGlobal
        graphDataSize = params.size
        def ctx = gc.createSnapshotCtx(params.size, ti18n, 100 - params.graphOffset)
        ctx.put('newChart', true)
        ctx.putAll(params)
        def out = streamOut(graph, ctx)
        out
    }

    Route graphUpdatePost = { Request req, Response resp ->
        def accept = req.headers('Accept')
        def params = evalGraphPost(req, resp)
        resp.status 200
        def ti18n = tGlobal
        def ctx = gc.createGraphControlCtx(ti18n)
        ctx.putAll(params)
        def out = streamOut(graphCommands, ctx)
        out
    }

    /** to be called at shutdown to save accumulated energy values */
    def shutdown() {
        println 'write snapshots'
        gc.writeSnapshots()
    }

    Map evalGraphPost(Request req, Response resp) {
        def accept = req.headers('Accept')
        def submission = req.queryParams()
        def graphUpdate
        def graphPaused = req.queryParams('graphpause') != null
        int size
        int offset
        if (submission.contains('graphsize') && req.queryParams('graphsize').isInteger()) {
            size = req.queryParams('graphsize').toInteger()
        } else {
            size = 360
        }
        if (submission.contains('graphoffset') && req.queryParams('graphoffset').isInteger()) {
            offset = req.queryParams('graphoffset').toInteger()
        } else {
            offset = 100
        }
        if (offset < 100) {
            graphPaused = true
        }
        if (submission.contains('graphupdate') && req.queryParams('graphupdate').isInteger()) {
            graphUpdate = req.queryParams('graphupdate').toInteger()
        } else {
            graphUpdate = 1
        }
        updatePause = graphPaused
        updateFrequency = graphUpdate
        def params = [:]
        params.put('graphPaused', graphPaused)
        params.put('graphUpdate', graphUpdate)
        params.put('size', size)
        params.put('graphOffset', offset)
        params.putAll(gc.createSizeValues(size))
        params
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

    @OnWebSocketError
    void onError(Session session, Throwable error) {
        println error
    }

    def updateWsValues(String out) {
        sessions.findAll { it.isOpen() }.each { socket ->
            try {
                socket.remote.sendString(out, writeCallback)
            } catch (Exception ex) {
                ex.printStackTrace()
            }
        }
    }

    private WriteCallback writeCallback = new WriteCallback() {
        @Override
        void writeFailed(Throwable x) {
            if (x instanceof IllegalStateException) {
                println "Websocket exception: ${((java.lang.IllegalStateException) x.message)}"
            }
        }

        @Override
        void writeSuccess() {

        }
    }

    /************* end webservice methods **************/

    def powerValuesString(Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        ctx.putAll(ti18n.powerStrings)
        ctx.putAll(powerValues())
        streamOut(powerValuesTemplate, ctx)
    }

    def chargeInfoString(Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        ctx.putAll(ti18n.stateStrings)
        ctx.putAll(ti18n.mgmtStrings)
        ctx.putAll(stateValues(ti18n))
        streamOut(chargeInfo, ctx)
    }

    def statesInfoString(Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        ctx.putAll(setChargeCommandContext(chargeStrategy, ti18n))
        ctx.putAll(setChargeManagementContext(chargeManagerState, ti18n))
        streamOut(stateButtons, ctx)
    }

    def graphInfoString(Map<String, Map<String, String>> ti18n, int size = 120) {
        def ctx = [:]
        ctx.putAll(gc.createSnapshotCtx(size, ti18n))
        ctx.put('newChart', true)
        streamOut(graphData, ctx)
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


    def joinDashboardContext(Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        ctx.putAll(ti18n.headingStrings)
        ctx.putAll(ti18n.stateStrings)
        ctx.putAll(ti18n.powerStrings)
        ctx.putAll(ti18n.mgmtStrings)
        ctx.putAll(ti18n.languages)
        ctx.putAll(stateValues(ti18n))
        ctx.putAll(powerValues())
        ctx
    }

    /** realtime state values for dashboard */
    def stateValues(Map<String, Map<String, String>> ti18n) {
        def ctx = [
                chargingStateValue     : ti18n.stateTx.get(carChargingState.toString()),
                chargeStrategyValue    : ti18n.stateTx.get(chargeStrategy.toString()),
                chargeManagerStateValue: ti18n.stateTx.get(chargeManagerState.toString()),
                tibberStrategyValue    : 'none',
                tibberPriceValue       : (int) (Math.random() * 30 + 15)
        ]
    }

    /** realtime analog values for dashboard */
    def powerValues() {
        def ctx = [
                pvValue     : pwrValues.powerSolar,
                gridValue   : pwrValues.powerGrid,
                batteryValue: pwrValues.powerBattery,
                homeValue   : pwrValues.consumptionHome - wbValues.energy,
                carValue    : wbValues.energy,
                socValue    : pwrValues.socBattery,
        ]
    }

    def setChargeManagementContext(CarChargingManager.ChargeManagerState state, Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        ctx.putAll ti18n.mgmtStrings
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

    def setChargeCommandContext(CarChargingManager.ChargeStrategy strategy, Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        ctx.putAll ti18n.chargeComandStrings
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

}
