/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2025. Georg Beier. All rights reserved.
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

package de.geobe.energy.obsolete

import de.geobe.energy.automation.CurrentPowerPrices
import de.geobe.energy.automation.PowerPriceMonitor
import de.geobe.energy.automation.PowerStrategyParams
import de.geobe.energy.e3dc.E3dcChargingModeController
import de.geobe.energy.e3dc.E3dcInteractionRunner
import de.geobe.energy.tibber.PriceAt
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

class PowerBufferingStrategy {

    static final HOUR = 3600L
    static final int MEAN_OVER_DAYS = 1

    PowerStrategyParams powerStrategyParams = new PowerStrategyParams()
    PowerBufferingParams bufferingParams = new PowerBufferingParams()
    def nightCharging = bufferingParams.startNightCharging..<bufferingParams.endNightCharging
    def daySupplying = bufferingParams.startMorningUnload..23
    int hourlySupplyForecast = powerStrategyParams.batCapacity.intdiv(bufferingParams.initialSupplyHours)
    int suppliedHours = 0
    Map<String, TimeInterval> nightChargingIntervals
    List<ConsumptionAt> supplyPlan
    List<StorageModeSwitch> switchingTimes

    /**
     * add >> (rightShift) method to IntRange for adjusting intervals
     */
    PowerBufferingStrategy() {
        IntRange.metaClass.rightShift = { Integer shift ->
            (delegate.from + shift)..(delegate.to + shift)
        }

    }

    E3dcChargingModeController chargingModeController = new E3dcChargingModeController()

    /**
     * Calculate charging, idle and supplying hours of energy storage for next day
     * @param at
     */
    def planDay(DateTime at = DateTime.now().withTimeAtStartOfDay().plusDays(1)) {
        Date runAt = at.toDate()

    }

    def updatePlan() {

    }

    /**
     * get most actual tibber prices from powerPrices map
     * @param powerPrices from tibber of totay and maybe tomorrow
     * @return most actual prices available
     */
    List<PriceAt> calculationPrices(CurrentPowerPrices powerPrices) {
        List<PriceAt> prices = powerPrices.today[(bufferingParams.startNightCharging)..<powerPrices.today.size()]
        if (powerPrices.tomorrow) {
            prices.addAll powerPrices.tomorrow
        }
        assert prices[0].start.hourOfDay == bufferingParams.startNightCharging
        prices
    }

    int indexOffset() {
        bufferingParams.startNightCharging < bufferingParams.endNightCharging ?
                0 :    // start at or after midnight
                24 - bufferingParams.startNightCharging // start before midnight
    }

    /**
     * Indexes of hours for night charging in calculation prices list
     * @return
     */
    def nightChargingInterval() {
        0..<bufferingParams.endNightCharging + indexOffset()
    }

    /**
     * calculate optimal charging periods at coming night for power storage
     * based on hourly changing tibber prices
     * @param prices most actual tibber prices
     * @param soc state of charge of energy storage
     * @return a sorted list of on/off charging times and the average price per kWh
     */
    def calculateNightCharging(List<PriceAt> prices, int soc) {
        assert prices[0].start.hourOfDay == bufferingParams.startNightCharging
        def idx = nightChargingInterval()
        def nightPrices = prices[idx]
        def sortedPrices = nightPrices.sort(false) { it.price }
        // how long does iot take to fully charge
        float chargingDuration = ((100 - soc) / 100) * bufferingParams.hoursToFullyCharge
        // full hours to charge
        def full = (int) Math.floor(chargingDuration)
        def chargeFullAt = sortedPrices[0..full - 1].collect { it.start() }.sort()
        float sumCost = sortedPrices[0..full - 1].sum { it.price }
        if (chargingDuration - full > 0.05) {    // more than 3 minutes
            sumCost += sortedPrices[full].price * (chargingDuration - full)
        }
        float priceAverage = sumCost / chargingDuration
        List<TimeInterval> onOffList = []
        def last = new TimeInterval(start: chargeFullAt[0], end: chargeFullAt[0].plusHours(1))
        if (full == 1) {
            onOffList << last
        } else {
            for (i in 1..<chargeFullAt.size()) {
                def next = new TimeInterval(start: chargeFullAt[i], end: chargeFullAt[i].plusHours(1))
                if (last.canJoin(next)) {
                    last = last.join(next)
                } else {
                    onOffList << last
                    last = next
                }
            }
            onOffList << last
        }
        if (sortedPrices.size > full + 1 && chargingDuration - full > 0.05) {
            def partSeconds = (int) ((chargingDuration - full) * 3600)
            def startPart = sortedPrices[full].start
            def partIntv = new TimeInterval(start: startPart, end: startPart.plusSeconds(partSeconds))
            for (i in 0..<onOffList.size()) {
                def intv = onOffList[0]
                if (intv.canJoin(partIntv)) {
                    onOffList[i] = intv.join(partIntv)
                    break
                } else if (intv.start.isAfter(partIntv.start)) {
                    onOffList.add(i, partIntv)
                    break
                } else if (partIntv.start.isAfter(intv.start) && i == onOffList.size() - 1) {
                    onOffList << partIntv
                }
            }
        }
        [onOff: onOffList, price: priceAverage]
    }

    def calculateStepwiseCharging(List<PriceAt> prices, int discharge = 70) {
        def hiLoPrices = prices.findAll { it.start }.sort(false) { -it.price }
        def loHiPrices = []
        loHiPrices.addAll(prices[nightChargingInterval()].sort(false) { it.price }[0..3])
        // remove all before end of charging
        def endCharging = (loHiPrices.max { it.start }.start).plusHours 1
        hiLoPrices -= hiLoPrices.findAll { it.start.isBefore(endCharging) }
        def loHiPairs = []
//        int discharge = 70
        int hi = 0
        int supplied = 0
        int charge
        for (i in 0..<loHiPrices.size()) {
            int canCharge = 100
            while (canCharge > 0) {
                charge = Math.min(discharge - supplied, canCharge)
                canCharge -= charge
                supplied += charge
                loHiPairs << [loHiPrices[i], hiLoPrices[hi], charge]
                if (supplied >= discharge) {
                    hi++
                    if(hi >= hiLoPrices.size()) {
                        break                    }
                    supplied = 0
                }
            }
            i++
        }
        float save = 0f
        loHiPairs.each {
            PriceAt low = it[0]
            PriceAt high = it[1]
            save += (high.price - low.price) * it[2]
        }
        [pairs: loHiPairs, save: save / 100]


//        while (loHiPrices) {
//            def lo = loHiPrices[0]
//            boolean found = false
//            for (i in 0..<hiLoPrices.size()) {
//                PriceAt hi = hiLoPrices[i]
//                if (!found && hi.start.isAfter(lo.start) && hi.price > lo.price * bufferingParams.loadCycleLoss) {
////                    hi = hiLoPrices[i]
//                    loHiPairs << [lo, hi]
//                    supplied = available
//                    available -= discharge
//                    loHiPrices -= hi
//                    if(available < 0) {
//                        loHiPrices -= lo
//                        hiLoPrices -= lo
//                    } else {
//
//                    }
//                    found = true
//                }
//            }
//            loHiPrices -= lo
//            hiLoPrices -= lo
//        }
//        float maxSaving = 0.0f
//        loHiPairs.each { List<PriceAt> lohi ->
//            maxSaving += lohi[1].price - lohi[0].price * bufferingParams.loadCycleLoss
//        }
//        maxSaving *= powerStrategyParams.batPower / 1000.0f
//        [maxSaving: maxSaving, loHi: loHiPairs]
    }

    def meanConsumptionHistory(List<PriceAt> prices) {
        def history = consumptionHistory
        IntRange mor = bufferingParams.morning >> indexOffset()
        IntRange mid = bufferingParams.midday >> indexOffset()
        IntRange eve = bufferingParams.evening
        int morningConsumption = history[bufferingParams.morning].sum()
        int middayConsumption = history[bufferingParams.midday].sum()
        int eveningConsumption = history[bufferingParams.evening].sum()
        int full = powerStrategyParams.batCapacity
        [
                c   : [morning: morningConsumption, midday: middayConsumption, evening: eveningConsumption],
                cavg: [avgcMorning: (morningConsumption / (mor.size())).round(2),
                       avgcMidday : (middayConsumption / (mid.size())).round(2),
                       avgcEvening: (eveningConsumption / (eve.size())).round(2)],
                p   : [dsocMorning: (morningConsumption / full).round(2),
                       dsocMidday : (middayConsumption / full).round(2),
                       dsocEvening: (eveningConsumption / full).round(2)],
                avgp: [priceAvgMorning: (prices[mor].sum { it.price } / mor.size()).round(3),
                       priceAvgMidday : (prices[mid].sum { it.price } / mid.size()).round(3),
                       priceAvgEvening: (prices[eve].sum { it.price } / eve.size()).round(3)],
        ]
    }

    /**
     * get power consumption per hour of last 24 hours as a base for estimating optimal supply hours
     * @return a list of consumption values of the last 24 hours
     */
    List<Integer> getConsumptionHistory() {
        def start = new DateTime().withTimeAtStartOfDay().minusDays(1)
        def history = E3dcInteractionRunner
                .interactionRunner.getHistoryValues(start, HOUR, 2 * 24)
        List<List> houseConsumption = []
        def dayConsumption = []
//        def ddmmyy = DateTimeFormat.forPattern('dd.MM.yy HH:mm:ss')
        history.keySet().each { dateTime ->
            if (dateTime.hourOfDay == 0) {
                if (dayConsumption) {
                    houseConsumption << dayConsumption
                    dayConsumption = []
                }
            }
            dayConsumption << (int) history[dateTime].homeConsumption().round()
        }
        if (dayConsumption) {
            houseConsumption << dayConsumption
        }
        List<Integer> consumptionHistory = []
        def hoursToday = houseConsumption[1].size() - 1
        consumptionHistory << houseConsumption[1][0..<hoursToday]
        consumptionHistory << houseConsumption[0][hoursToday..23]
        consumptionHistory.flatten()
    }

    /**
     * Find hours during which supply will probably generate maximal saving, based on hourly prices
     * @param powerPrices tibber prices of day to plan
     * @param supplyPrice average tibber price during charging
     * @param hours range of hours included in calculation, default is morning until midnight
     * @return ConsumptionAt list sorted by saving in descending order
     */
    List<PriceAt> estimateOptimalSupply(
            List<PriceAt> powerPrices, float supplyPrice = .27, IntRange hours = daySupplying
    ) {
        // get state of charge
        int soc = E3dcInteractionRunner.interactionRunner.currentValues.socBattery
        int canSupply = 8//(int) Math.ceil((soc / 100) * bufferingParams.initialSupplyHours)
        // interesting prices for planning
        def dayPrices = powerPrices[hours]
        def sortedDayPrices = dayPrices.sort(false) { -it.price }
        int upper = Math.min(canSupply, sortedDayPrices.size())
        sortedDayPrices[0..<upper].sort { it.start }
    }

    /**
     * To estimate cost saving for a given hourly consumption, try to guess how long heatpump was active
     * during that hour. This is necessary because heat pump consumes more power than energy storage can supply.
     * @param consumption power consumption of a certain hour
     * @param price difference between power from storage and power from grid
     * @return
     */
    private estimateSaving(int consumption, float price) {
        def heatpumpCycle =
                Math.min(1.0, (consumption - bufferingParams.baseConsumption) / bufferingParams.heatpumpConsumption)
        def saveableConsumption =
                heatpumpCycle * powerStrategyParams.batPower + (1.0 - heatpumpCycle) * bufferingParams.baseConsumption
        def estimate = saveableConsumption * price
        [estimate: (float) estimate, supply: (int) saveableConsumption]
    }

    List<StorageModeSwitch> generateChargingModeTimeTable(
            List<TimeInterval> chargeOnOffList, List<ConsumptionAt> supplyList
    ) {
        DateTime chargingEnd = chargeOnOffList.last().end
        List<StorageModeSwitch> modeSwitches = []
        chargeOnOffList.each { chargeInterval ->
            modeSwitches << new StorageModeSwitch(at: chargeInterval.start, setMode: E3dcInteractionRunner.GRIDLOAD)
            modeSwitches << new StorageModeSwitch(at: chargeInterval.end, setMode: E3dcInteractionRunner.IDLE)
        }
        DateTime base = chargingEnd.withTimeAtStartOfDay()
        List<DateTime> supplyingHours = supplyList.sort { it.hour }.collect { base.plusHours(it.hour) }
        int i = 0
        while (i < supplyingHours.size()) {
            if (supplyingHours[i].isBefore(chargingEnd)) {
                supplyingHours.remove(i)
            } else {
                break
            }
            i++
        }
        supplyingHours.each { supply ->
            modeSwitches << new StorageModeSwitch(at: supply, setMode: E3dcInteractionRunner.AUTO)
            modeSwitches << new StorageModeSwitch(at: supply.plusHours(1), setMode: E3dcInteractionRunner.IDLE)
        }
        i = 0
        // remove first of two swiches at same hour
        while (i < modeSwitches.size() - 1) {
            if (modeSwitches[i].at == modeSwitches[i + 1].at) {
                modeSwitches.remove(i)
            }
            i++
        }
        i = 0
        // remove identical switches at consecutive hours
        while (i < modeSwitches.size() - 1) {
            if (modeSwitches[i].setMode == modeSwitches[i + 1].setMode) {
                modeSwitches.remove(i + 1)
            } else {
                i++
            }
        }
        modeSwitches
    }

    static void main(String[] args) {
        PowerBufferingStrategy strategy = new PowerBufferingStrategy()
        def monitor = PowerPriceMonitor.monitor
        def e3dc = E3dcInteractionRunner.interactionRunner
//        def soc = 0 //e3dc.currentValues.socBattery
        def prices = strategy.calculationPrices(monitor.latestPrices)
        def steps = strategy.calculateStepwiseCharging(prices,30)
        println "could save: ${(steps.save * strategy.powerStrategyParams.batPower / 1000).round(3)}"
        steps.pairs.each { pair ->
            def lt = pair[0].start.hourOfDay
            def ht = pair[1].start.hourOfDay
            def save = pair[2] * (pair[1].price - pair[0].price) * strategy.powerStrategyParams.batPower / 100000
            println "($lt -> $ht): ${pair[2]}% ${save.round(3)}"
        }
//        println "max. saving: ${(steps.maxSaving).round(3)}"
        def nightCharging = strategy.calculateNightCharging(prices, 0)
        def chargeAt = nightCharging.onOff
        float lowPrice = nightCharging.price * strategy.bufferingParams.loadCycleLoss
        def supplyAt = strategy.estimateOptimalSupply(monitor.latestPrices.tomorrow, lowPrice)
        float estimate = 0.0f
        println "average night price ${lowPrice.round(3)}"
        supplyAt.each {
            print "${it.start.hourOfDay}: ${it.price.round(3)}, "
        }
        println()
        println "${strategy.bufferingParams.morning >> 2}"
        IntRange ir = strategy.bufferingParams.morning >> 2
        println "${(prices[ir]).collect { [it.start.hourOfDay, it.price] }}"
        ir = strategy.bufferingParams.midday >> 2
        println "${(prices[ir]).collect { [it.start.hourOfDay, it.price] }}"
        ir = strategy.bufferingParams.evening >> 2
        println "${(prices[ir]).collect { [it.start.hourOfDay, it.price] }}"
        def cHist = strategy.meanConsumptionHistory(prices)
        println "\nyesterday W: $cHist.c \nyesterday W/h: $cHist.cavg \nyesterday %: $cHist.p \nyesterday avg: $cHist.avg"
//        supplyAt.each { def entry ->
//            estimate += entry.estimate / 1000
//            print "@${entry.hour} save: ${(entry.estimate / 1000).round(2)} € \t"
//        }
//        println "\nestimate: ${estimate.round(3)}, charging at: $chargeAt"
//        def switchTable = strategy.generateChargingModeTimeTable(chargeAt, supplyAt)
//        switchTable.each {
//            println it
//        }
        monitor.shutdown()
        e3dc.interactions.closeConnection()
    }
}

class TimeInterval {
    DateTime start
    DateTime end
    long secondsTolerance = 10
    static DateTimeFormatter hmmss = DateTimeFormat.forPattern('HH:mm:ss')


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

class ConsumptionAt {
    int hour
    int consumption
    float price
    float estimate
    int supply
    int socAtEnd

    @Override
    String toString() {
        return "[$hour, $consumption, $supply, $price, $estimate]".toString()
    }
}

class StorageModeSwitch {
    DateTime at
    byte setMode

    @Override
    String toString() {
        "${TimeInterval.hmmss.print(at)}: $setMode"
    }
}
