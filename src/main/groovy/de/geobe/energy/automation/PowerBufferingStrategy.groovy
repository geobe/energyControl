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

import de.geobe.energy.e3dc.E3dcInteractionRunner
import de.geobe.energy.tibber.PriceAt
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

class PowerBufferingStrategy {

    def calculateNightCharging(CurrentPowerPrices powerPrices, int soc, PowerStrategyParams params) {
        List<PriceAt> prices = powerPrices.tomorrow ?: powerPrices.today
        assert prices[0].start.hourOfDay == 0
        def nightPrices = prices[0..5]
        def sortedPrices = nightPrices.sort(false) { it.price }
        def chargingDuration = ((100 - soc) / 100) * (params.batCapacity / params.batPower)
        def full = (int) Math.floor(chargingDuration)
        def chargeFullAt = sortedPrices[0..full - 1].collect { it.start() }.sort()
        List<TimeInterval> onOffList = []
        def last = new TimeInterval(start:  chargeFullAt[0], end: chargeFullAt[0].plusHours(1))
        if(full == 1) {
            onOffList << last
        } else {
            for (i in 1..<chargeFullAt.size()) {
                def next = new TimeInterval(start:  chargeFullAt[i], end: chargeFullAt[i].plusHours(1))
                if(last.canJoin(next)) {
                    last = last.join(next)
                } else {
                    onOffList << last
                    last = next
                }
            }
            onOffList << last
        }
        if(sortedPrices.size > full + 1) {
            def partSeconds = (int) ((chargingDuration - full) * 3600)
            def startPart = sortedPrices[full].start
            def partIntv = new TimeInterval(start: startPart, end: startPart.plusSeconds(partSeconds))
            for (i in 0..<onOffList.size()) {
                def intv = onOffList[0]
                if(intv.canJoin(partIntv)) {
                    onOffList[i] = intv.join(partIntv)
                    break
                } else if(intv.start.isAfter(partIntv.start)) {
                    onOffList.add(i, partIntv)
                    break
                } else if(partIntv.start.isAfter(intv.start) && i == onOffList.size() - 1) {
                    onOffList << partIntv
                }
            }
        }
        onOffList
    }

    static void main(String[] args) {
        PowerBufferingStrategy strategy = new PowerBufferingStrategy()
        def monitor = PowerPriceMonitor.monitor
        def e3dc = E3dcInteractionRunner.interactionRunner
        def soc = 60 //e3dc.currentValues.socBattery
        def params = new PowerStrategyParams()
        def result = strategy.calculateNightCharging(monitor.latestPrices, soc, params)
        println(result)
        monitor.shutdown()
        e3dc.interactions.closeConnection()
    }
}

class TimeInterval {
    DateTime start
    DateTime end
    long secondsTolerance = 10
    static DateTimeFormatter hmmss = DateTimeFormat.forPattern('H:mm:ss')


    boolean canJoin(TimeInterval other) {
        if (start.compareTo(other.start) <= 0) {
            if (new Duration(end, other.start).abs().standardSeconds < secondsTolerance) {
                return true
            }
        } else if (new Duration(other.end, start).abs().standardSeconds < secondsTolerance) {
            return true
        }
        false
    }

    TimeInterval join(TimeInterval other) {
        assert canJoin(other)
        if (start.compareTo(other.start) <= 0) {
            new TimeInterval(start: start, end: other.end)
        } else {
            new TimeInterval(start: other.start, end: end)
        }
    }

    @Override
    String toString() {
        "<${hmmss.print(start)} - ${hmmss.print(end)}>"
    }
}