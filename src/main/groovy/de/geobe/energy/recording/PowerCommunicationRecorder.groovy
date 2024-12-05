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

import de.geobe.energy.automation.PMValues
import de.geobe.energy.automation.PeriodicExecutor
import de.geobe.energy.automation.PowerMonitor
import de.geobe.energy.automation.PowerStorageStatic
import de.geobe.energy.automation.PowerValueSubscriber
import de.geobe.energy.web.EnergySettings
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

import java.util.concurrent.TimeUnit

/**
 * record state sequence of car charging and other messages in a monthly log file
 */
class PowerCommunicationRecorder /*implements PowerValueSubscriber*/ {
    private static RecordingFile recordingFile
    /** first recording initial delay */
    static DateTimeFormatter stamp = DateTimeFormat.forPattern('dd.MM.yy HH:mm:ss')

    private static PowerCommunicationRecorder recorder

    static synchronized PowerCommunicationRecorder getRecorder() {
        if (!recorder) {
            try {
            recorder = new PowerCommunicationRecorder()
            } catch (Exception ex) {
                logMessage "Startup Failure\t$PowerMonitor.startupFailure"
                System.exit(1)
            }
        }
        recorder
    }

    private PowerCommunicationRecorder(/*int cyc = CYCLE_TIME*/) {
        recordingFile = new RecordingFile(".$EnergySettings.SETTINGS_DIR", 'CommRecord', RecordingFile.Span.MONTH)
    }

    void powerStorageModeChanged(PowerStorageStatic.StorageMode storageMode) {
        logMessage "battery switched to\t$storageMode"
    }

    void powerStoragePresetChanged(PowerStorageStatic.StorageMode storageMode, int hour) {
        def day = hour > 23 ? ' +1D' : ''
        logMessage "battery preset at\t${hour%24}$day to\t$storageMode"
    }

    void powerStorageControlModeChanged(PowerStorageStatic.ChargeControlMode controlMode) {
        logMessage "set battery control mode $controlMode"
    }

    static void logMessage(String msg) {
        def protocol = "${stamp.print(DateTime.now())}\t$msg"
        recordingFile.appendReport(protocol)
    }
}
