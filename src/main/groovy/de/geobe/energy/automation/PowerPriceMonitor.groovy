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

package de.geobe.energy.automation

import de.geobe.energy.recording.LogMessageRecorder
import de.geobe.energy.recording.PowerCommunicationRecorder
import de.geobe.energy.tibber.IPowerQueryRunner
import de.geobe.energy.tibber.PriceAt
import de.geobe.energy.tibber.TibberQueryRunner
import de.geobe.energy.web.GraphController
import groovy.io.GroovyPrintWriter
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject
import org.codehaus.groovy.runtime.StringBufferWriter
import org.joda.time.DateTime
import org.joda.time.Duration

import java.util.concurrent.TimeUnit

@ActiveObject
class PowerPriceMonitor {

    /** subscription cycle time 15 minutes */
    private long cycle = 15 * 60
    /** small offset 10 seconds to let external data settle */
    private long settlingOffset = 11
    /** subscription time unit */
    private TimeUnit timeUnit = TimeUnit.SECONDS
    /** first subscription initial delay, to be recalculated on construction */
    private long initialDelay = 0
    /** Reference to Power Price source */
    private static IPowerQueryRunner powerPriceSource
    /** all valueSubscribers to power price values */
    private volatile List<PowerPriceSubscriber> subscribers = []
    /** task to read power values periodically */
    private PeriodicExecutor executor
    /** last updated prices */
    volatile CurrentPowerPrices latestPrices

    /** singleton monitor object */
    private static PowerPriceMonitor monitor

    static synchronized PowerPriceMonitor getMonitor() {
        if (!monitor) {
            monitor = new PowerPriceMonitor(new TibberQueryRunner())
        }
        monitor
    }

    private PowerPriceMonitor(IPowerQueryRunner powerQueryRunner) {
        powerPriceSource = powerQueryRunner
        def priceRecord = powerPriceSource.runPriceQuery()
        latestPrices = new CurrentPowerPrices(yesterday: priceRecord.yesterday, today: priceRecord.today, tomorrow: priceRecord.tomorrow)
        start()
    }

    /**
     * task that repeatedly reads current power prices and distributes
     * them to all interested objects
     */
    private Runnable powerPrices = new Runnable() {
        @Override
        void run() {
            try {
                def priceRecord = powerPriceSource.runPriceQuery()
                if (priceRecord) {
                    latestPrices = new CurrentPowerPrices(yesterday: priceRecord.yesterday, today: priceRecord.today, tomorrow: priceRecord.tomorrow)
                    subscribers.each {
                        it.takePriceUpdate(latestPrices)
                    }
                }
            } catch (exception) {
                PowerCommunicationRecorder.logMessage "PowerPriceMonitor exception $exception"
                def sbw = new StringBufferWriter(new StringBuffer())
                exception.printStackTrace(new GroovyPrintWriter(sbw))
                LogMessageRecorder.recorder.logMessage "PowerPriceMonitor ${sbw.toString()}"
            }
        }
    }

    @ActiveMethod(blocking = false)
    void subscribe(PowerPriceSubscriber subscriber) {
        subscribers.add subscriber
        subscriber.takePriceUpdate(latestPrices)
    }

    @ActiveMethod
    void unsubscribe(PowerPriceSubscriber subscriber) {
        subscribers.remove subscriber
    }

    private start() {
        def now = DateTime.now()
        def nextFull = now.hourOfDay().roundCeilingCopy()
        def secondsAway = new Duration(now, nextFull).standardSeconds
        def secondsToPeriod = secondsAway % cycle
        println "PowerPriceMonitor started with $cycle $timeUnit period, " +
                "starting in ${secondsToPeriod + settlingOffset} $timeUnit"
        executor = new PeriodicExecutor(powerPrices, cycle, timeUnit, secondsToPeriod + settlingOffset)
        executor.start()
    }

//    private stop() {
//        executor?.stop()
//    }

    @ActiveMethod
    def shutdown() {
        executor?.shutdown()
        executor = null
    }
}

interface PowerPriceSubscriber {
    void takePriceUpdate(CurrentPowerPrices prices)
}

class CurrentPowerPrices {
    List<PriceAt> today
    List<PriceAt> tomorrow
    List<PriceAt> yesterday
}