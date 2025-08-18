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

import de.geobe.energy.automation.utils.SpikeFilter
import de.geobe.energy.e3dc.E3dcError
import de.geobe.energy.e3dc.E3dcException
import de.geobe.energy.e3dc.PowerValues
import de.geobe.energy.e3dc.E3dcInteractionRunner
import de.geobe.energy.e3dc.IStorageInteractionRunner
import de.geobe.energy.go_e.WallboxValues
import de.geobe.energy.recording.LogMessageRecorder
import de.geobe.energy.recording.PowerCommunicationRecorder
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject
import org.joda.time.DateTime

import java.util.concurrent.TimeUnit

/**
 * Heartbeat of read actions:
 * <ol>
 * <li>Periodically read power values from storage system.</li>
 * <li>Have WallboxManager read Wallbox values and combine them with power values</li>
 * <li>If values have changed more than hysteresis, send new values to PvChargeStrategy
 * and other subscribers</li>
 */
@ActiveObject
class PowerMonitor /* implements WallboxValueSubscriber */ {
    static final CYCLE_TIME = 5
    static final TimeUnit TIME_UNIT = TimeUnit.SECONDS
    /** subscription cycle time */
    private long cycle = CYCLE_TIME
    /** subscription time unit */
    private TimeUnit timeUnit = TIME_UNIT
    /** first subscription initial delay */
    private long initialDelay = 0
    /** Reference to Power System */
    private final IStorageInteractionRunner powerInfo
    /** Reference to current dynamic prices */
    private final PowerPriceMonitor powerPriceInfo
    /** Reference to WallboxMonitor which provides wallbox values */
    private final WallboxMonitor wbValuesProvider
    /** wallbox values that are periodically updated by wbValuesProvider */
    private volatile WallboxValues wallboxValues
    /** all valueSubscribers to power values and recoverable exceptions*/
    private volatile List<PowerValueSubscriber> subscribers = new LinkedList<>().asSynchronized()
    /** list of subscribers to record or handle fatal exceptions */
    private volatile List<FatalExceptionSubscriber> fatalExceptionSubscribers = new LinkedList<>().asSynchronized()
    /** all message recorders */
    private volatile List<LogMessageRecorder> messageRecorders = new LinkedList<>().asSynchronized()
    /** filter spikes in realtime values and provide list of filtered values */
    private SpikeFilter spikeFilter = new SpikeFilter()
    /** task to read power values periodically */
    private PeriodicExecutor executor
    /** remember exception state */
    private volatile boolean resumeAfterException = false
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

        @Override
        void run() {
            try {
                PowerValues powerValues = powerInfo.currentValues
                WallboxMonitorValues monitorValues = wbValuesProvider.readWallbox()
                wallboxValues = monitorValues.wallboxValues
                // filter spikes resulting from sudden change of car charging power
                PMValues pmValues = new PMValues(powerValues, wallboxValues, monitorValues.chargingState)
                pmValues = spikeFilter.filterSpikes(pmValues)
                if (resumeAfterException) {
                    // exception cause was repaired, so we can notify subscibers
                    exceptionSubscribers().each {
                        it.resumeAfterMonitorException()
                    }
                    LogMessageRecorder.recorder.resumeAfterMonitorException()
                    resumeAfterException = false
                }
                synchronized (subscribers) {
                    subscribers.each {
                        it.takePMValues(pmValues)
                    }
                }
            } catch (E3dcException e3dcEx) {
                // found no way to recover from this error, so completely stop and restart this service
                PowerCommunicationRecorder.logMessage "PowerMonitor exception $e3dcEx"
//                LogMessageRecorder.recorder.logStackTrace('PowerMonitor', e3dcEx)
                // wait 30 seconds before restarting service
                Thread.sleep(3000)
                fatalExceptionSubscribers.each {
                    it.restartService(e3dcEx)
                }
            } catch (Exception ex) {
//                ex.printStackTrace()
                // notify about exception only once every 10 minutes
                if (++exceptionCount >= 120) {
                    exceptionCount = 0
                    resumeAfterException = false
                }
                if (!resumeAfterException) {
                    PowerCommunicationRecorder.logMessage "PowerMonitor exception $ex"
                    LogMessageRecorder.recorder.logStackTrace('PowerMonitor', ex)
                    exceptionSubscribers().each {
                        it.takeMonitorException(ex)
                    }
                    resumeAfterException = true
                }
            }
        }

        def exceptionSubscribers() {
            Set<MonitorExceptionSubscriber> xSubs = new HashSet<>(subscribers)
            xSubs.addAll(wbValuesProvider.subscribers)
            xSubs
        }

        boolean stateChange(PMValues pmValues) {

        }
    }

    @ActiveMethod
    void initCycle(long cycle, long initialDelay) {
        this.cycle = cycle
        this.initialDelay = initialDelay
    }

    List<PMValues> getValueTrace() {
        spikeFilter.valueTrace
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

//    @Override
//    void takeWallboxValues(WallboxValues values) {
//        wallboxValues = values
//        wallboxException = null
//        hasWallboxException = false
//    }
//
//    @Override
//    void takeMonitorException(Exception exception) {
//        wallboxException = exception
//        hasWallboxException = true
//    }
//
//    @Override
//    void resumeAfterMonitorException() {
//        wallboxException = null
//        hasWallboxException = false
//    }

    @ActiveMethod(blocking = true)
    void subscribe(PowerValueSubscriber subscriber) {
        def willStart = subscribers.size() == 0
//        synchronized (subscribers) {
        subscribers.add subscriber
//        }
        if (willStart) {
            start()
        }
    }

    @ActiveMethod(blocking = true)
    void unsubscribe(PowerValueSubscriber subscriber) {
        boolean removed
//        synchronized (subscribers) {
        removed = subscribers.remove subscriber
//        }
        if (removed && subscribers.size() == 0)
            stop()
    }

//    void subscribeMessages(LogMessageRecorder recorder) {
//        messageRecorders.add recorder
//    }

//    void unsubscribeMessages(LogMessageRecorder recorder) {
//        messageRecorders.remove recorder
//    }

    void subscribeFatalErrors(FatalExceptionSubscriber fes) {
        fatalExceptionSubscribers.add(fes)
    }

    void unsubscribeFatalErrors(FatalExceptionSubscriber fes) {
        fatalExceptionSubscribers.remove(fes)
    }

    private start() {
        println "PowerMonitor started with $cycle $timeUnit period"
//        wbValuesProvider.subscribeValue(this)
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
    private static int lastDay
    PowerValues powerValues
    WallboxValues wallboxValues
    WallboxMonitor.CarChargingState chargingState
    DateTime timeStamp = DateTime.now()
    boolean nextDay = false
//    Float currentPrice

    PMValues(PowerValues powerValues, WallboxValues wallboxValues, WallboxMonitor.CarChargingState chargingState) {
        this.powerValues = powerValues
        this.wallboxValues = wallboxValues
        this.chargingState = chargingState
        nextDay = lastDay && timeStamp.dayOfMonth != lastDay
        lastDay = timeStamp.dayOfMonth
//        this.currentPrice = currentPrice
    }

    @Override
    String toString() {
        "$powerValues\n$wallboxValues"
    }
}

