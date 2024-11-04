/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2024. Georg Beier. All rights reserved.
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

package de.geobe.energy.recording

import de.geobe.energy.automation.CarChargingManager
import de.geobe.energy.automation.WallboxMonitor
import de.geobe.energy.automation.WallboxStateSubscriber
import de.geobe.energy.automation.WallboxValueSubscriber
import de.geobe.energy.go_e.Wallbox
import de.geobe.energy.go_e.WallboxValues
import de.geobe.energy.web.EnergySettings
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

class LogMessageRecorder implements WallboxStateSubscriber, WallboxValueSubscriber {
    private static RecordingFile stateMessageFile
    static DateTimeFormatter stamp = DateTimeFormat.forPattern('dd.MM.yy HH:mm:ss')

    private static LogMessageRecorder recorder

    static synchronized LogMessageRecorder getRecorder() {
        if (!recorder) {
            recorder = new LogMessageRecorder()
        }
        recorder
    }

    LogMessageRecorder() {
        stateMessageFile = new RecordingFile(".$EnergySettings.SETTINGS_DIR", 'StateSequence', RecordingFile.Span.DAY)
//        WallboxMonitor.monitor.subscribeState this
//        WallboxMonitor.monitor.subscribeValue this
    }

    volatile WallboxMonitor.CarChargingState carChargingState
    volatile CarChargingManager.ChargeManagerState chargeManagerState
    volatile CarChargingManager.ChargeManagerStrategy chargeStrategy
    volatile String chargingDetail
    private WallboxValues lastWbValues, prevValues

    void takeStateValues(
            WallboxMonitor.CarChargingState carChargingState,
            CarChargingManager.ChargeManagerState chargeManagerState,
            CarChargingManager.ChargeManagerStrategy chargeStrategy,
            String chargingDetail) {
        if (this.carChargingState != carChargingState ||
                this.chargeManagerState != chargeManagerState ||
                this.chargeStrategy != chargeStrategy ||
                this.chargingDetail != chargingDetail) {
            def message =
                    "car: $carChargingState " +
                            "strategy: $chargeStrategy " +
                            "mgr_state: $chargeManagerState " +
                            "strategy_state: $chargingDetail"
            this.carChargingState = carChargingState
            this.chargeManagerState = chargeManagerState
            this.chargeStrategy = chargeStrategy
            this.chargingDetail = chargingDetail
            logMessage(message)
        }
    }

    @Override
    void takeWallboxValues(WallboxValues values) {
        if(prevValues && !prevValues == values) {
            logMessage(values.toString())
        }
        prevValues = values.clone()
    }

    @Override
    void takeWallboxState(WallboxMonitor.CarChargingState carState) {
        def wbInfo = WallboxMonitor.monitor.current
        def wbValues = wbInfo.values
        if (lastWbValues?.allowedToCharge != wbValues.allowedToCharge ||
                lastWbValues?.requestedCurrent != wbValues.requestedCurrent ||
                lastWbValues?.carState != wbValues.carState ||
                lastWbValues?.forceState != wbValues.forceState) {
//                Math.abs(lastWbValues.energy - wbValues.energy) > 200) {
            def message = "WBmon: $carState <- $wbValues".toString()
            lastWbValues = wbValues.clone()
            logMessage(message)
//            logMessage(lastWbValues.toString())
        }


    }

    @Override
    void takeMonitorException(Exception exception) {
        def exStack = exception.stackTrace
        logMessage "Monitor Exception $exception"
        exStack.each {
            logMessage "        $it"
        }
    }

    @Override
    void resumeAfterMonitorException() {
        logMessage 'resume after Monitor exception'
    }

    void logMessage(String message) {
        def protocol = "${stamp.print(DateTime.now())}\t$message"
        stateMessageFile.appendReport(protocol)
        println protocol
    }
}