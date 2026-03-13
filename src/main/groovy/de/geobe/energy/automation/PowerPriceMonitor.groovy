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
import groovy.transform.EqualsAndHashCode
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.LocalTime
import org.joda.time.Seconds

import java.util.concurrent.TimeUnit

@ActiveObject
class PowerPriceMonitor {

    /** check for changed power prices at these hours */
    private LocalTime newPricesAt = new LocalTime(13, 0)
    private LocalTime updatePricesAt = new LocalTime(18, 0)
    private LocalTime atMidnight = new LocalTime(23, 59, 59)
    /** if no new prices, try again after */
    private long tryAgain = 5 * 60
    /** time unit */
    private TimeUnit timeUnit = TimeUnit.SECONDS
    /** time between two clicks */
    private int clickPeriod = 5
    /** counter for clicks */
    private int clickCount = 0
    private int clickLimit
    /** Reference to Power Price source */
    private static IPowerQueryRunner powerPriceSource
    /** all valueSubscribers to power price values */
    private volatile List<PowerPriceSubscriber> subscribers = []
    /** last updated prices */
    volatile CurrentPowerPrices latestPrices

    /** singleton traceMonitor object */
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
    }

    /**
     * cyclic states
     *
     */
    enum MonitorState {
        Activate,
        WaitNewPrices,
        ReadNewPrices,
        WaitUpdatedPrices,
        ReadUpdatedPrices,
        WaitMidnight,
        ReadAtZero,
        None
    }

    private MonitorState monitorState = MonitorState.None

    /* event methods */

    /**
     * activate state chart of PowerPriceMonitor:<br>
     * implicitely execute actions of Activate state
     * @param clickPeriod period of click events that trigger traceMonitor
     */
    void activate(int clickPeriod) {
        this.clickPeriod = clickPeriod
        if(publishLatestPrices()) {
            if (!latestPrices.tomorrow) {
                monitorState = MonitorState.WaitNewPrices
                stateEntry()
            } else {
                LocalTime now = LocalTime.now()
                LocalTime updateDue = new LocalTime(updatePricesAt)
                if (now.isBefore(updateDue)) {
                    monitorState = MonitorState.WaitUpdatedPrices
                    stateEntry()
                } else {
                    monitorState = MonitorState.ReadUpdatedPrices
                    stateEntry()
                }
            }
        } else {
            // retry after 30 seconds
            clickCount = 0
            clickLimit = 30.intdiv(clickPeriod)
        }
    }

    /**
     * As we have a very simple state chart, it can be implemented in only two methods with a switch statement each.<br>
     * Here are actions to be performed on entry of a state
     */
    private void stateEntry() {
        def oldState = monitorState
        switch (monitorState) {
            case MonitorState.WaitNewPrices:
                calcClicksToWait(newPricesAt)
                break
            case MonitorState.ReadNewPrices:
                getOrWaitForPrices(MonitorState.WaitUpdatedPrices)
                break
            case MonitorState.WaitUpdatedPrices:
                calcClicksToWait(updatePricesAt)
                break
            case MonitorState.ReadUpdatedPrices:
                getOrWaitForPrices(MonitorState.WaitMidnight,true, true)
                break
            case MonitorState.WaitMidnight:
                calcClicksToWait(atMidnight)
                // wait until 1 minute after midnight
                clickLimit += 60.intdiv(clickPeriod)
                break
            case MonitorState.ReadAtZero:
                getOrWaitForPrices(MonitorState.WaitNewPrices, false)
                break
        }
        println "PowerPriceMonitor.stateEntry(): $oldState -> $monitorState"
    }

    /**
     * As we have a very simple state chart, it can be implemented in only two methods with a switch statement each.<br>
     * Here are actions to be performed on a click event in a state
     */
    void stateOnClick() {
        def oldState = monitorState
        if (++clickCount >= clickLimit) {
            switch (monitorState) {
                case MonitorState.Activate:
                    // reached only if get failed in activate() method
                    activate(clickPeriod)
                    break
                case MonitorState.WaitNewPrices:
                    monitorState = MonitorState.ReadNewPrices
                    stateEntry()
                    break
                case MonitorState.ReadNewPrices:
                    // reached only if get failed in stateEntry method
                    getOrWaitForPrices(MonitorState.WaitUpdatedPrices)
                    break
                case MonitorState.WaitUpdatedPrices:
                    monitorState = MonitorState.ReadUpdatedPrices
                    stateEntry()
                    break
                case MonitorState.ReadUpdatedPrices:
                    // reached only if get failed in stateEntry method
                    getOrWaitForPrices(MonitorState.WaitMidnight,true, true)
                    break
                case MonitorState.WaitMidnight:
                    monitorState = MonitorState.WaitNewPrices
                    publishLatestPrices()
                    stateEntry()
                    break
                case MonitorState.ReadAtZero:
                    // reached only if get failed in stateEntry method
                    getOrWaitForPrices(MonitorState.WaitNewPrices, false)
                    break
            }
        }
        if (oldState != monitorState) {
            PowerCommunicationRecorder.logMessage "PowerPriceMonitor.stateOnClick(): $oldState -> $monitorState"
        }
    }

    /**
     * if tomorrow prices are available, proceed to next state else set click counter to wait time
     */
    private void getOrWaitForPrices(MonitorState nextState, boolean hasTomorrow = true, boolean checkChanged = false) {
        if(publishLatestPrices(checkChanged)) {
            def okay = (latestPrices.tomorrow && hasTomorrow) || (!latestPrices.tomorrow && !hasTomorrow)
            if (okay) {
                monitorState = nextState
                stateEntry()
                return
            }
        }
        clickCount = 0
        clickLimit = tryAgain.intdiv(clickPeriod)
    }

    /**
     * Calculate clicks to wait until to go to next state
     * @param until LocalTime for next state transition
     */
    private void calcClicksToWait(LocalTime until) {
        LocalTime now = LocalTime.now()
        int secondsToWait = Seconds.secondsBetween(now, until).seconds
        clickLimit = secondsToWait.intdiv(clickPeriod) + 1
        clickCount = 0
//        println "PowerPriceMonitor.calcClicksToWait(): $now -> $until = $clickLimit clicks"
    }

    /**
     * read and record current power prices and distribute
     * them to all interested objects.
     */
//    @ActiveMethod(blocking = true)
    boolean publishLatestPrices(boolean checkChanged = false) {
        def priceRecord
        try {
            LogMessageRecorder.recorder.logMessage "PowerPriceMonitor: run price query at ${DateTime.now()}"
            priceRecord = powerPriceSource.runPriceQuery()
            LogMessageRecorder.recorder.logMessage "PowerPriceMonitor: got ${priceRecord ? 'a' : 'no'} price record at ${DateTime.now()}"
        } catch (exception) {
            PowerCommunicationRecorder.logMessage "PowerPriceMonitor exception $exception"
            LogMessageRecorder.recorder.logStackTrace('PowerPriceMonitor', exception)
            return false
        }
        if (priceRecord) {
            if (checkChanged) {
                def updatedPrices = new CurrentPowerPrices(yesterday: priceRecord.yesterday, today: priceRecord.today, tomorrow: priceRecord.tomorrow)
                if (updatedPrices == latestPrices) {
                    LogMessageRecorder.recorder.logMessage "PowerPriceMonitor.publishLatestPrices(): no update at $updatedPrices.timestamp "
                    return true
                } else {
                    LogMessageRecorder.recorder.logMessage "PowerPriceMonitor.publishLatestPrices(): update at $updatedPrices.timestamp "
                    latestPrices = updatedPrices
                }
            } else {
                latestPrices = new CurrentPowerPrices(yesterday: priceRecord.yesterday, today: priceRecord.today, tomorrow: priceRecord.tomorrow)
                LogMessageRecorder.recorder.logMessage "PowerPriceMonitor.publishLatestPrices(): get at $latestPrices.timestamp "
            }
            if (latestPrices.today || latestPrices.tomorrow) {
                subscribers.each {
                    it.takePriceUpdate(latestPrices)
                }
            }
            return true
        } else {
            return false
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

}

interface PowerPriceSubscriber {
    void takePriceUpdate(CurrentPowerPrices prices)
}

@EqualsAndHashCode(includes = ['today', 'tomorrow'])
class CurrentPowerPrices {
    DateTime timestamp = DateTime.now()
    List<PriceAt> today
    List<PriceAt> tomorrow
    List<PriceAt> yesterday
}