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

import de.geobe.energy.e3dc.PowerValues
import de.geobe.energy.e3dc.E3dcInteractionRunner
import de.geobe.energy.e3dc.IStorageInteractionRunner
import de.geobe.energy.go_e.WallboxValues
import de.geobe.energy.recording.LogMessageRecorder
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

import java.util.concurrent.TimeUnit

/**
 * Periodically read power values from storage system. If values
 * have changed more than hysteresis, send new values to PvChargeStrategy
 */
@ActiveObject
class PowerMonitor implements WallboxValueSubscriber {
    /** subscription cycle time */
    private long cycle = 5
    /** subscription time unit */
    private TimeUnit timeUnit = TimeUnit.SECONDS
    /** first subscription initial delay */
    private long initialDelay = 0
    /** Reference to Power System */
    private final IStorageInteractionRunner powerInfo
    /** Reference to current dynamic prices */
    private final PowerPriceMonitor powerPriceInfo
    /** Reference to WallboxMonitor which provides wallbox values */
    private final WallboxValueProvider wbValuesProvider
    /** wallbox values that are periodically updated by wbValuesProvider */
    private volatile WallboxValues wallboxValues
    /** all valueSubscribers to power values */
    private volatile List<PowerValueSubscriber> subscribers = []
    /** all valueSubscribers to power values */
    private volatile List<LogMessageRecorder> messageRecorders = []
    /** task to read power values periodically */
    private PeriodicExecutor executor
    /** remember exception state */
    private volatile boolean stoppedByException = false
    private volatile int exceptionCount = 0
    /** store wallbox exception to propagate it */
    private volatile Exception wallboxException
    private volatile boolean hasWallboxException = false

    private static PowerMonitor monitor
    private static boolean constructionFailed = false
    static Exception startupFailure

    /**
     * only for functional tests to inject monitor mock object
     * @param testMonitor
     */
    static synchronized void setMonitor(PowerMonitor testMonitor) {
        if (monitor) {
            throw new RuntimeException('PowerMonitor Singleton must not be overwritten')
        }
        monitor = testMonitor
    }

    /**
     * on first call, create power monitor singleton
     * @return
     */
    static synchronized PowerMonitor getMonitor() {
        if (!monitor && !constructionFailed) {
            try {
                monitor = new PowerMonitor(E3dcInteractionRunner.interactionRunner, WallboxMonitor.monitor)
            } catch (Exception e) {
                constructionFailed = true
                startupFailure = e
                throw e
            }
        }
        monitor
    }

    private PowerMonitor(IStorageInteractionRunner interactionRunner, WallboxValueProvider wallboxValueProvider) {
        powerInfo = interactionRunner
//        powerPriceInfo = PowerPriceMonitor.monitor
        wbValuesProvider = wallboxValueProvider
    }

    /**
     * task that repeatedly reads current power values and distributes
     * them to all interested objects
     */
    private Runnable readPower = new Runnable() {
        PMValues lastValues
        String last
        @Override
        void run() {
            try {
                if(hasWallboxException) {
                    throw wallboxException
                }
                def pmValues = new PMValues(powerInfo.currentValues, wallboxValues)
                if (stoppedByException) {
                    // exception cause was repaired, so we can notify subscibers
                    subscribers.each {
                        it.resumeAfterMonitorException()
                    }
                    stoppedByException = false
                }
                subscribers.each {
                    it.takePMValues(pmValues)
                }
            } catch (Exception ex) {
//                ex.printStackTrace()
                // notify about exception only once every 10 minutes
                if(++exceptionCount >= 120) {
                    exceptionCount = 0
                    stoppedByException = false
                }
                if (!stoppedByException) {
                    subscribers.each {
                        it.takeMonitorException(ex)
                    }
                    stoppedByException = true
                }
            }
        }

        boolean stateChange(PMValues pmValues) {

        }
    }

    @ActiveMethod
    void initCycle(long cycle, long initialDelay) {
        this.cycle = cycle
        this.initialDelay = initialDelay
    }

    /**
     *
     * @return current values from storage system
     * @throws Exception, if connection to storage system fails
     */
    @ActiveMethod(blocking = true)
    PowerValues getCurrent() throws Exception {
        powerInfo.currentValues
    }

    @Override
    void takeWallboxValues(WallboxValues values) {
        wallboxValues = values
        wallboxException = null
        hasWallboxException = false
    }

    @Override
    void takeMonitorException(Exception exception) {
        wallboxException = exception
        hasWallboxException = true
    }

    @Override
    void resumeAfterMonitorException() {
        wallboxException = null
        hasWallboxException = false
    }

    @ActiveMethod(blocking = false)
    void subscribe(PowerValueSubscriber subscriber) {
        def willStart = subscribers.size() == 0
        subscribers.add subscriber
        if (willStart) {
            start()
        }
    }

    @ActiveMethod
    void unsubscribe(PowerValueSubscriber subscriber) {
        subscribers.remove subscriber
        if (subscribers.size() == 0)
            stop()
    }

    void subscribeMessages(LogMessageRecorder recorder) {
        messageRecorders.add recorder
    }

    void unsubscribeMessages(LogMessageRecorder recorder) {
        messageRecorders.remove recorder
    }

    private start() {
        println "PowerMonitor started with $cycle $timeUnit period"
        wbValuesProvider.subscribeValue(this)
        executor = new PeriodicExecutor(readPower, cycle, timeUnit)
        executor.start()
    }

    private stop() {
        println "PowerMonitor stopped "
        executor?.stop()
    }

    @ActiveMethod
    def shutdown() {
        executor?.shutdown()
        executor = null
    }

    static void main(String[] args) {
        def m = getMonitor()
        PowerValueSubscriber p = new PowerValueSubscriber() {
            @Override
            void takePMValues(PMValues pmValues) {
                println pmValues
            }

            @Override
            void takeMonitorException(Exception exception) {
                println "$exception -> $exception.cause"
            }

            @Override
            void resumeAfterMonitorException() {
                println "resumeAfterPMException"
            }
        }
        m.subscribe p
        Thread.sleep(60000)
        m.stop()
        m.shutdown()
    }
}

interface PowerValueSubscriber extends MonitorExceptionSubscriber {
    void takePMValues(PMValues pmValues)
}

class PMValues {
    PowerValues powerValues
    WallboxValues wallboxValues
//    Float currentPrice

    PMValues(PowerValues powerValues, WallboxValues wallboxValues) {
        this.powerValues = powerValues
        this.wallboxValues = wallboxValues
//        this.currentPrice = currentPrice
    }

    @Override
    String toString() {
        "$powerValues\n$wallboxValues"
    }
}

