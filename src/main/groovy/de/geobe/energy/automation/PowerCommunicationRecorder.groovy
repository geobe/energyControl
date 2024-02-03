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

package de.geobe.energy.automation

import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

import java.util.concurrent.TimeUnit

/**
 * observe and record communication behaviour between program and e3dc storage system
 */
class PowerCommunicationRecorder implements PowerValueSubscriber {
    static final long CYCLE_TIME
    /** recording cycle time */
    private long cycle
    /** recording time unit */
    private TimeUnit timeUnit = TimeUnit.SECONDS
    /** first recording initial delay */
    private long initialDelay = 20
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
            if (PowerMonitor.monitor) {
                recorder.start()
            } else {
                def protocol = "${stamp.print(DateTime.now())}\tStartup Failure\t$PowerMonitor.startupFailure"
                println protocol
            }
        }
        recorder
    }

    static void stopRecorder() {
        recorder?.stop()
    }

    private PowerCommunicationRecorder(int cyc = CYCLE_TIME) {
        cycle = cyc
    }

    def start() {
        if (cycle == CYCLE_TIME) {
            def now = DateTime.now()
            def nextHour = now.hourOfDay().roundCeilingCopy()
            initialDelay = new Duration(now, nextHour).standardSeconds
        }
        executor = new PeriodicExecutor(periodicProtocol, cycle, timeUnit, initialDelay)
        PowerMonitor.monitor.subscribe(this)
        def protocol = "${stamp.print(DateTime.now())}\tRecording\t$cycle $timeUnit\tafter $initialDelay $timeUnit"
        println protocol
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
                println protocol
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
    void takePMException(Exception exception) {
        def protocol = "${stamp.print(DateTime.now())}\tException\t$exception\t$exception.cause"
        println protocol
    }

    @Override
    void resumeAfterPMException() {
        def protocol = "${stamp.print(DateTime.now())}\tResume"
        println protocol
    }



    static void main(String[] args) {
        PowerCommunicationRecorder recorder = new PowerCommunicationRecorder(15)
        Thread.sleep(300000)
        recorder.stop()
        PowerMonitor.monitor.shutdown()
    }
}
