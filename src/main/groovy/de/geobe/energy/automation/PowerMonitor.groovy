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
import de.geobe.energy.go_e.IWallboxValueSource
import de.geobe.energy.go_e.Wallbox
import de.geobe.energy.go_e.WallboxValues
import groovy.transform.ImmutableOptions
import groovy.transform.RecordType
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

import java.util.concurrent.TimeUnit

/**
 * Periodically read power values from storage system. If values
 * have changed more than hysteresis, send new values to PvChargeStrategy
 */
@ActiveObject
class PowerMonitor {
    /** cycle time */
    private long cycle = 5
    /** Time unit */
    private TimeUnit timeUnit = TimeUnit.SECONDS
    /** Reference to Power System */
    private final IStorageInteractionRunner powerInfo
    /** Reference to Wallbox values */
    private final IWallboxValueSource wbValues
    /** all valueSubscribers to power values */
    private volatile List<PowerValueSubscriber> subscribers = []
    /** task to read power values periodically */
    private PeriodicExecutor executor

    private static PowerMonitor monitor

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

    static synchronized PowerMonitor getMonitor() {
        if(! monitor) {
            monitor = new PowerMonitor(E3dcInteractionRunner.interactionRunner, Wallbox.wallbox)
        }
        monitor
    }

    private PowerMonitor(IStorageInteractionRunner interactionRunner, IWallboxValueSource wallboxValueSource) {
        powerInfo = interactionRunner
        wbValues = wallboxValueSource
    }

    /**
     * task that repeatedly reads current power values and distributes
     * them to all interested objects
     */
    private Runnable readPower = new Runnable() {
        @Override
        void run() {
            def cuv = powerInfo.currentValues
            def wbv = wbValues.values
            def pmv = new PMValues(cuv, wbv.energy, wbv.requestedCurrent, wbv.carState)
            subscribers.each {
                it.takePowerValues(pmv)
            }
        }
    }

    @ActiveMethod(blocking = true)
    PowerValues getCurrent() {
        powerInfo.currentValues
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

    private start() {
        println "PowerMonitor started with $cycle $timeUnit period"
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
            void takePowerValues(PMValues pmValues) {
                println pmValues
            }
        }
        m.subscribe p
        Thread.sleep(20000)
        m.stop()
        m.shutdown()
    }
}

interface PowerValueSubscriber {
    void takePowerValues(PMValues powerValues)
}

@ImmutableOptions(knownImmutableClasses = [PowerValues])
record PMValues (PowerValues powerValues, short wbEnergy, short requestedCurrent, Wallbox.CarState carState) {
    @Override
    String toString() {
        "$powerValues, wbEnergy: $wbEnergy, req: $requestedCurrent, carState: $carState"
    }
}

