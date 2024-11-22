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
 * observe and record communication behaviour between program and e3dc storage system<br>
 * record state sequence of car charging
 */
class PowerCommunicationRecorder implements PowerValueSubscriber {
    private static long CYCLE_TIME = 3600
    private static boolean AT_HOUR = true
    private static RecordingFile recordingFile
    private static RecordingFile stateMessageFile
    /** recording cycle time */
    private long cycle
    /** recording time unit */
    private TimeUnit timeUnit = TimeUnit.SECONDS
    /** first recording initial delay */
    private static long initialDelay = 20
    /** interval count */
    private volatile long intervalCount
    private volatile long intervalTotal
    private volatile long minReadInterval
    private volatile long maxReadInterval
    private volatile long lastReading = 0
    private PeriodicExecutor executor
    static DateTimeFormatter stamp = DateTimeFormat.forPattern('dd.MM.yy HH:mm:ss')

    private static PowerCommunicationRecorder recorder

    static synchronized PowerCommunicationRecorder getRecorder() {
        if (!recorder) {
            recorder = new PowerCommunicationRecorder()
            try {
            if (PowerMonitor.monitor) {
                    recorder.start()
            } else {
                def protocol = "${stamp.print(DateTime.now())}\tStartup Failure\t$PowerMonitor.startupFailure"
//                println protocol
                recordingFile.appendReport(protocol)
            }
        } catch (Exception ex) {
            def protocol = "${stamp.print(DateTime.now())}\tStartup Failure\t$PowerMonitor.startupFailure"
//            println protocol
            recordingFile.appendReport(protocol)
            System.exit(1)
        }
        }
        recorder
    }

    static void stopRecorder() {
        recorder?.stop()
    }

    private PowerCommunicationRecorder(int cyc = CYCLE_TIME) {
        recordingFile = new RecordingFile(".$EnergySettings.SETTINGS_DIR", 'CommRecord', RecordingFile.Span.MONTH)
        cycle = cyc
    }

    def start() {
        if (AT_HOUR) {
            def now = DateTime.now()
            def nextHour = now.hourOfDay().roundCeilingCopy()
            initialDelay = new Duration(now, nextHour).standardSeconds + 1
        }
        executor = new PeriodicExecutor(periodicProtocol, cycle, timeUnit, initialDelay)
        PowerMonitor.monitor.subscribe(this)
        def protocol = "${stamp.print(DateTime.now())}\tRecording\t$cycle $timeUnit\tafter $initialDelay $timeUnit"
//        println protocol
        recordingFile.appendReport(protocol)
        executor.start()
    }

    def stop() {
        PowerMonitor.monitor?.unsubscribe(this)
        executor?.stop()
    }

    private Runnable periodicProtocol = new Runnable() {
        @Override
        void run() {
            if (intervalCount > 0) {
                def meanReadInterval = intervalTotal.intdiv(intervalCount)
                def protocol = "${stamp.print(DateTime.now())}\tProtocol\t$minReadInterval\t$meanReadInterval\t$maxReadInterval"
                intervalTotal = 0
                intervalCount = 0
                minReadInterval = 0
                maxReadInterval = 0
//                println protocol
                recordingFile.appendReport(protocol)
            }
        }
    }

    @Override
    void takePMValues(PMValues pmValues) {
        def now = System.currentTimeMillis()
        if (lastReading) {
            def interval = now - lastReading
            intervalCount++
            intervalTotal += interval
            minReadInterval = minReadInterval > 0 ? Math.min(minReadInterval, interval) : interval
            maxReadInterval = Math.max(maxReadInterval, interval)
        }
        lastReading = now
    }

    @Override
    void takeMonitorException(Exception exception) {
        def protocol = "${stamp.print(DateTime.now())}\tException\t$exception\t$exception.cause"
//        println protocol
        recordingFile.appendReport(protocol)
    }

    @Override
    void resumeAfterMonitorException() {
        def protocol = "${stamp.print(DateTime.now())}\tResume"
//        println protocol
        recordingFile.appendReport(protocol)
    }

    void powerStorageModeChanged(PowerStorageStatic.StorageMode storageMode) {
        def protocol = "${stamp.print(DateTime.now())}\tbattery switched to\t$storageMode"
        recordingFile.appendReport(protocol)
    }

    void powerStoragePresetChanged(PowerStorageStatic.StorageMode storageMode, int hour) {
        def protocol = "${stamp.print(DateTime.now())}\tbattery preset at\t$hour to\t$storageMode"
        recordingFile.appendReport(protocol)
    }

    static void main(String[] args) {
        CYCLE_TIME = 30
        AT_HOUR = false
        initialDelay = 0
        PowerCommunicationRecorder recorder = getRecorder()
        Thread.sleep(120000)
        recorder.stop()
        PowerMonitor.monitor.shutdown()
    }
}
