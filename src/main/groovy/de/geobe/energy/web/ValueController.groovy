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
import de.geobe.energy.e3dc.E3dcError
import de.geobe.energy.e3dc.PowerValues
import de.geobe.energy.go_e.WallboxValues
import de.geobe.energy.recording.LogMessageRecorder
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.template.PebbleTemplate
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WriteCallback
import org.eclipse.jetty.websocket.api.annotations.*
import org.joda.time.DateTime
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
    private PowerMonitor powerMonitor
    private WallboxMonitor wbMonitor
    private LogMessageRecorder logMessageRecorder
    private PowerStorageStatic powerStorage
//    private PowerPriceMonitor powerPriceMonitor
    /** shortcut to car charging manager singleton */
    private carChargingManager = CarChargingManager.carChargingManager
    /** current Values */
    volatile WallboxValues wbValues
    volatile PowerValues pwrValues
    volatile WallboxMonitor.CarChargingState carChargingState
    volatile CarChargingManager.ChargeManagerState chargeManagerState
    volatile CarChargingManager.ChargeManagerStrategy chargeStrategy
    volatile String chargingDetail
//    volatile currentPrice
    volatile Boolean networkError = false
    volatile Boolean networkErrorFatal = false
    volatile Boolean networkErrorResume = false
    volatile Exception networkException
    volatile String errorTimestamp
    volatile defaultUiLanguage = 'de'
    // display values that could be migrated to session variables
    // global display values
    volatile String uiLanguage = 'de'

    /** store for websocket sessions */
    private ConcurrentLinkedDeque<Session> sessions = new ConcurrentLinkedDeque<>()

    /** all static template strings for spark */
    UiStringsI18n stringsI18n = new UiStringsI18n()
    def tGlobal = stringsI18n.translationsFor(uiLanguage)
    /** more helper objects */
    EnergySettings es = new EnergySettings(this, tGlobal)
    GraphController gc = new GraphController(tGlobal)
    TibberController tibc = new TibberController(this)
    StorageController stoc = new StorageController(this)

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
    def graphButton = engine.getTemplate('template/chartbutton.peb')
    def tibberGraph = engine.getTemplate('template/tibbergraph.peb')
    def networkErrorTemplate = engine.getTemplate('template/networkerror.peb')
    def storageControlTemplate = engine.getTemplate('template/storagecontrol.peb')

    void init() {
        try {
            powerMonitor = PowerMonitor.monitor
            wbMonitor = WallboxMonitor.monitor
            logMessageRecorder = LogMessageRecorder.recorder
//            powerPriceMonitor = PowerPriceMonitor.monitor
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
            chargeStrategy = carChargingManager.chargeManagerStrategy
            chargingDetail = carChargingManager.chargeManagerStrategyDetail
            chargeManagerState = carChargingManager.chargeManagerState
//            currentPrice = currentPowerPrice()
            powerMonitor.initCycle(PowerMonitor.CYCLE_TIME, 0)
            powerMonitor.subscribe(this)
            powerStorage = PowerStorageStatic.powerStorage
            wbMonitor.subscribeState(this)
            logMessageRecorder.takeStateValues(carChargingState, chargeManagerState, chargeStrategy, chargingDetail)
        } catch (Exception exception) {
            takeMonitorException(exception)
        }
    }

    @Override
    void takePMValues(PMValues pmValues) {
        wbValues = pmValues.getWallboxValues()
        pwrValues = pmValues.powerValues
        gc.saveSnapshot(pwrValues, wbValues)
        carChargingState = wbMonitor.current.state
        chargeStrategy = carChargingManager.chargeManagerStrategy
        chargingDetail = carChargingManager.chargeManagerStrategyDetail
        chargeManagerState = carChargingManager.chargeManagerState
        logMessageRecorder.takeStateValues(carChargingState, chargeManagerState, chargeStrategy, chargingDetail)
//        currentPrice = currentPowerPrice()
        updateWsValues(powerValuesString(tGlobal) + chargeInfoString(tGlobal))// + statesInfoString)
//        updateWsValues(statesInfoString(tGlobal))
        gc.updateCounter++
        if (!gc.updatePause && gc.updateCounter >= gc.updateFrequency) {
            gc.updateCounter = 0
            updateWsValues(graphInfoString(tGlobal, gc.graphDataSize))
        }
    }

    @Override
    void takeMonitorException(Exception exception) {
        networkError = true
        networkException = exception
        def reason = exception.message
        errorTimestamp = gc.full.print DateTime.now()
        networkErrorFatal = reason.contains(E3dcError.AUTH) || reason.contains(E3dcError.IP)
        updateWsValues(errorMessageString(tGlobal))
        if (networkErrorFatal) {
            EnergyControlUI.shutdown()
        }
    }

    @Override
    void resumeAfterMonitorException() {
        networkError = false
        networkErrorFatal = false
        networkException = null
        networkErrorResume = true
        errorTimestamp = gc.full.print DateTime.now()
        updateWsValues(errorMessageString(tGlobal))
    }

    @Override
    void takeWallboxState(WallboxMonitor.CarChargingState carState) {
        carChargingState = carState
//        println "carChargingState changed: $carChargingState"
    }

    void showStopServer() {
        networkError = true
        networkErrorFatal = false
        networkException = new Exception('Shutdown')
        networkErrorResume = false
        errorTimestamp = gc.full.print DateTime.now()
        updateWsValues(errorMessageString(tGlobal))
    }

    /***************** Routing methods **************/

    Route indexRoute = { Request req, Response resp ->
        def submission = req.queryParams()
        if (submission.contains('language')) {
            def language = req.queryParams('language')
            if (language) {
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
            if (language) {
                tGlobal = stringsI18n.translationsFor(language)
                uiLanguage = language
                gc.updateDateFormat(uiLanguage)
            }
        }
//        resp.redirect("/".toString())
        def ctx = bodyCtx(req, resp)
        ctx.put('newCanvas', true)
        def out = streamOut(body, ctx)
        updateWsValues(out)
        out
    }

    def bodyCtx(Request req, Response resp) {
        def strategy = chargeStrategy
        def ti18n = tGlobal
        def ctx = setChargeCommandContext(strategy, ti18n)
        ctx.putAll setChargeManagementContext(chargeManagerState, ti18n)
        ctx.putAll(joinDashboardContext(ti18n))
        ctx.putAll(es.settingsFormContext(ti18n))
        ctx.putAll(gc.createSnapshotCtx(gc.graphDataSize, ti18n, 0))
//        ctx.putAll(gc.createSizeValues(gc.graphDataSize))
        ctx.putAll(tibc.createTibberDataCtx(ti18n))
        ctx.putAll(stringsI18n.i18nCtx(ti18n, uiLanguage))
        ctx.putAll(gc.graphControlValues())
        ctx.putAll(stoc.storageControlContext(ti18n))
        ctx.putAll(errorContext(ti18n))
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
                        CarChargingManager.ChargeManagerStrategy.CHARGE_PV_SURPLUS)
                break
            case cc.cmdTibber:
                carChargingManager.takeChargeCmd(
                        CarChargingManager.ChargeManagerStrategy.CHARGE_TIBBER)
                break
            case cc.cmdAnyway:
                carChargingManager.takeChargeCmd(
                        CarChargingManager.ChargeManagerStrategy.CHARGE_ANYWAY)
                break
            case cc.cmdStop:
                carChargingManager.takeChargeCmd(
                        CarChargingManager.ChargeManagerStrategy.CHARGE_STOP)
                break
            default:
                throw new RuntimeException("unexpected action $action")
        }
        resp.status 200
        def o = owner
        def t = this
        owner.chargeStrategy = carChargingManager.chargeManagerStrategy
        owner.chargeManagerState = carChargingManager.chargeManagerState
        def upd = statesInfoString(ti18n)
        updateWsValues(upd)
        upd = chargeInfoString(ti18n)
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

    Route storagePost = { Request req, Response resp ->
        def accept = req.headers('Accept')
        def action = req?.params(':action')
        if (action?.isInteger()) {
            int hour = action?.toInteger()
            if (hour in 0..47) {
                powerStorage.incModeAt(hour)
            }
        } else {
//            def qraw = req.queryParams(action)?.split()
            def qparam = req.queryParams(action) //qraw?[0]
            def value = qparam?.integer ? qparam.toInteger() : 0
            switch (action) {
                case 'stop':
                    powerStorage.setChargeControlMode(PowerStorageStatic.ChargeControlMode.INACTIVE)
                    break
                case 'manual':
                    powerStorage.setChargeControlMode(PowerStorageStatic.ChargeControlMode.MANUAL)
                    break
                case 'auto':
                    powerStorage.setChargeControlMode(PowerStorageStatic.ChargeControlMode.AUTO)
                    break
                case 'planReset':
                    powerStorage.resetTomorrow()
                    break
                case 'bufCtlPowerFactor':
                    PowerStorageAutomation.setUnloadFactor(value)
                case 'planCreate':
                    powerStorage.optimizeTomorrow()
                    break
                case 'bufCtlSocDay':
                    powerStorage.socDay = value
                    break
                case 'bufCtlSocNight':
                    powerStorage.socNight = value
                    break
                case 'bufCtlSocReserve':
                    powerStorage.socReserve = value
                    break
                default:
                    println ":action $action"
            }
        }
        def ti18n = tGlobal
        def ctx = stoc.storageControlContext(ti18n)
        def out = streamOut(storageControlTemplate, ctx)
        def sess = req.session()
        updateWsValues(out)
        resp.status 200
        out//''
    }

    Route graphPost = { Request req, Response resp ->
        def accept = req.headers('Accept')
        resp.status 200
        def ti18n = tGlobal
        def ctx = gc.evalGraphPost(req, resp)
        ctx.putAll(gc.getSnapshotCtx(ti18n))
        ctx.put('newChart', true)
        def out = streamOut(graph, ctx)
        updateWsValues(out)
        ''
    }

    Route graphDataPost = { Request req, Response resp ->
        def accept = req.headers('Accept')
        resp.status 200
//        def ti18n = tGlobal
//        def ctx = gc.evalGraphPost(req, resp)
//        ctx.putAll(gc.getSnapshotCtx(ti18n))
//        ctx.put('newChart', true)
//        def out = streamOut(graph, ctx)
//        updateWsValues(out)
        graphInfoString(tGlobal, gc.graphDataSize)
    }

    Route graphUpdatePost = { Request req, Response resp ->
        def accept = req.headers('Accept')
        resp.status 204
        def ti18n = tGlobal
        def params = gc.evalGraphPost(req, resp)
        def ctx = gc.createGraphControlCtx(ti18n)
        ctx.putAll(params)
        def out = streamOut(graphCommands, ctx)
        updateWsValues(out)
        ''
    }

    /** to be called at shutdown to save accumulated energy values */
    def shutdown() {
        println 'write snapshots'
        gc.writeSnapshots()
    }


    /***************** websocket methods **************/

    @OnWebSocketConnect
    void onConnect(Session user) {
        sessions.add user
        println "Websocket connected"
    }

    @OnWebSocketClose
    void onClose(Session user, int statusCode, String reason) {
        if (sessions.remove user) {
            println "Websocket removed on close, status: $statusCode, $reason"
        }
    }

    @OnWebSocketMessage
    void onMessage(Session user, String message) {
        println "message from $user: $message"
    }

    @OnWebSocketError
    void onError(Session session, Throwable error) {
        println "Websocket error $error"
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

    /************* end websocket methods **************/

    def currentPowerPrice() {
        def prices = tibc?.tibberPrices?.today
        def index = DateTime.now().hourOfDay
        def price = ((prices[index]?.price ?: -.99f) * 100).round(2)
        "$price"
    }

    def powerValuesString(Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        ctx.putAll(ti18n.powerStrings)
        ctx.putAll(powerValues())
        def out = streamOut(powerValuesTemplate, ctx)
        out
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

    /**
     * output graph data to web frontend, sent to webservice
     * @param ti18n
     * @param size
     * @return
     */
    def graphInfoString(Map<String, Map<String, String>> ti18n, int size = 120) {
        def ctx = [:]
        ctx.putAll(gc.createSnapshotCtx(size, ti18n, 0))
        ctx.put('newChart', true)
        streamOut(graphData, ctx)
    }

    def errorMessageString(Map<String, Map<String, String>> ti18n) {
        streamOut(networkErrorTemplate, errorContext(ti18n))
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
        def detail = carChargingManager.chargeManagerStrategyDetail
        def strategyValue = ("${ti18n.stateTx.get(chargeStrategy.toString())}(" +
                "${ti18n.stateTx.get(chargingDetail)})").toString()
        def ctx = [
                chargingStateValue     : ti18n.stateTx.get(carChargingState.toString()),
                chargeStrategyValue    : strategyValue,
                chargeManagerStateValue: ti18n.stateTx.get(chargeManagerState.toString()),
                tibberStrategyValue    : 'none',
                tibberPriceValue       : currentPowerPrice()
        ]
    }

    /** realtime analog values for dashboard */
    def powerValues() {
        def energy = (wbValues?.energy) ?: 0
        def ctx = [
                pvValue     : pwrValues?.powerSolar,
                gridValue   : pwrValues?.powerGrid,
                batteryValue: pwrValues?.powerBattery,
                homeValue   : pwrValues ? pwrValues?.consumptionHome - energy : null,
                carValue    : energy,
                socValue    : pwrValues?.socBattery,
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

    def setChargeCommandContext(CarChargingManager.ChargeManagerStrategy strategy, Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        ctx.putAll ti18n.chargeComandStrings
        switch (strategy) {
            case CarChargingManager.ChargeManagerStrategy.CHARGE_PV_SURPLUS:
                ctx['checkedSurplus'] = 'checked'
                break
            case CarChargingManager.ChargeManagerStrategy.CHARGE_TIBBER:
                ctx['checkedTibber'] = 'checked'
                break
            case CarChargingManager.ChargeManagerStrategy.CHARGE_ANYWAY:
                ctx['checkedAnyway'] = 'checked'
                break
            case CarChargingManager.ChargeManagerStrategy.CHARGE_STOP:
//            case null:
                ctx['checkedStop'] = 'checked'
//                break
//            default:
//                throw new IllegalArgumentException()
        }
        ctx
    }

    def errorContext(Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        String cause = ''
        def arg = ''
        ctx.put('networkError', networkError)
        ctx.put('errorResume', networkErrorResume)
        if (networkError) {
            def exceptionParts = networkException.toString().split(/:/)
            if (exceptionParts.size() > 1 && exceptionParts[1]) {
                def key = exceptionParts[1].trim()
                if (key?.contains(' ')) {
                    def msg = key.split(/ /)
                    key = msg[0]
                    arg = msg.size() > 1 ? msg[1] : arg
                }
                cause = ti18n.errorStrings.get(key)
            }
            cause = cause ? cause : networkException.toString()
            String pre
            if (networkErrorFatal) {
                pre = ti18n.errorStrings.StopException + '<br>'
            } else {
                pre = ''
            }
            ctx.put('errorMessage', "$errorTimestamp: $pre$cause $arg")
        }
        if (networkErrorResume) {
            def about = ti18n.errorStrings.E3dcErrorResume
            ctx.put('resumeMessage', "$errorTimestamp: $about")
        }
        ctx
    }

}
