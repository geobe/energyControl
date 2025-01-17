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

import de.geobe.energy.tibber.PriceAt
import de.geobe.energy.tibber.TibberQueryRunner
import de.geobe.energy.web.EnergySettings
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

import static de.geobe.energy.automation.PowerStorageStatic.HOURS
import static groovy.io.FileType.FILES
import static de.geobe.energy.automation.PowerStorageStatic.StorageMode

/**
 * Class responsibility: Calculate a good pattern of charging, holding and discharging
 * power storage to save money.<br>
 * Conditions:
 * <ul>
 *     <li>Capacity: It takes 5,75 hours to fully charge an empty storage.</li>
 *     <li>MaxLo: The maximal number of hours of charging along the day.</li>
 *     <li>Loss: Inverter loss is at least 10%.</li>
 *     <li>Load: Charging is 3 kWh/hour</li>
 *     <li>Unload: Average discharging is 2.5 kWh/hour</li>
 *     <li>Saving: At least 0.05 €/kWh should be effective price difference</li>
 * </ul>
 * First experience: Variing the number of potential Load records between 10 and 14 seems to bring best results.
 * Algorithm:
 * <ol>
 *     <li>Start with a list of 24 StorageControlRecord objects sorted by ascending price.</li>
 *     <li>Find and mark the first n (<= 8 !) records that satisfy Saving condition with highest priced record.</li>
 *     <li>Find and mark the lowest high-price record that satisfies Saving condition with lowest priced record.</li>
 *     <li>Build a new list with the marked records in daytime (hour) order.</li>
 *     <li>Eliminate marks for low-price records that have no following high-price record.</li>
 *     <li>Eliminate marks for high-price records that have no preceding low-price record.</li>
 *     <li>Rerun steps above and calculate total Savings for different numbers of Load and Unload records.</li>
 *     <li>Return the combination with maximal Saving.</li>
 * </ol>
 */
class PowerStorageAutomation {

    static final MAX_LO = 12
    static final MAX_LO_LOOP = 10..14
    static final float LOSS_FACTOR = 1.1
    static final float LOAD_PWR = 3.0
//    static final float LOAD_LAST_PWR = 2.0
    static final float LOAD_MAX = 17.6
    /** full hours to completely load storage */
    static final LOAD_COUNT = 6
    /** maximal power drain when unloading */
//    static final float UNLOAD_PWR = 2.8
    static final float SAVING_MIN_HOUR = .04
    static final float SAVING_MIN_DAY = .4
    static final DateTimeFormatter F_DAY = DateTimeFormat.forPattern('yy-MM-dd')
    static final PRICETABLE_FILE = 'pricetable'
    /** which fraction of available power is really used in average */
    static float unloadFactor = 0.75

    static setUnloadFactor(def value) {
        unloadFactor = value
    }

    /**
     * try optimization with different # of Load records to find best solution
     * price calculation is done with a new list of records populated from optimum
     * in a straight simple way.
     * @param prices a list of tibber prices in €/kWh for all 24 hours of a day
     * @return saving and best found combination of StorageControlRecords or empty list
     */
    static optimizeTryBest(List<Float> prices) {
        def optimizations = []
        for (i in MAX_LO_LOOP) {
            optimizations << optimizeOne(prices, i)
        }
        def best = optimizations.min { it.saving }
        if (best.saving <= -SAVING_MIN_DAY) {
            def modes = best.records.collect { it.mode }
            def records = StorageControlRecord.createRecords(prices, modes)
            def saving = calculate(records)
            return [saving : saving,
                    records: records]
        }
        return [saving : 0.0,
                records: []]
    }
    /**
     * Run an optimization for a given maximal number of Load records.
     * @param prices a list of tibber prices in €/kWh for all 24 hours of a day
     * @param maxLo maximal number of Load records at the beginning of the algorithm
     * @return best found combination of StorageControlRecords and calculated saving
     */
    static optimizeOne(List<Float> prices, int maxLo = MAX_LO) {
        assert prices.size() == 24
        def rawRecords = StorageControlRecord.createRecords(prices)
        def records = rawRecords.sort(false)
        markCandidates(records, maxLo)
        eliminateOrphans(records)
        def leftLoad = stepThrough(records)
        markIdle(records, leftLoad)
        [saving : calculate(records),
         records: records]
    }

    /**
     * Mark all StorageControlRecords that are candidates for either charging or discharging power storage
     * @param records List of StorageControlRecords sorted by hourly price
     * @return nothing, but List of StorageControlRecords now sorted by hour and mode marked as AUTO (discharge)
     * or GRID_LOAD (charge) or null (idle)
     */
    static markCandidates(List<StorageControlRecord> records, int maxLo) {
        int maxHi = 24 - maxLo
        def nLo = 0
        def nHi = 0
        for (i in 0..<maxLo) {
            if ((records[i].price * LOSS_FACTOR + SAVING_MIN_HOUR) < records.last().price) {
                nLo++
                records[i].mode = StorageMode.GRID_CHARGE
            } else {
                break
            }
        }
        if (nLo) {
            records.last().mode = StorageMode.AUTO
            nHi++
            for (i in 1..<maxHi) {
                if ((records.first().price * LOSS_FACTOR + SAVING_MIN_HOUR) < records[23 - i].price) {
                    records[23 - i].mode = StorageMode.AUTO
                    nHi++
                }
            }
            records.sort { r1, r2 ->
                r1.compareHour(r2)
            }
        }
    }

    /**
     * Remove leading mode==AUTO and trailing mode==GRID_LOAD marks
     * @param records List of StorageControlRecords sorted by hour
     */
    static eliminateOrphans(List<StorageControlRecord> records) {
        for (i in 0..23) {
            if (records[i].mode == StorageMode.GRID_CHARGE) {
                break
            } else if (records[i].mode == StorageMode.AUTO) {
                records[i].mode = null
            }
        }
        for (i in 23..0) {
            if (records[i].mode == StorageMode.AUTO) {
                break
            } else if (records[i].mode == StorageMode.GRID_CHARGE) {
                records[i].mode = null
            }
        }
    }

    static stepThrough(List<StorageControlRecord> records) {
        float balance = 0.0
        float loadState = 0.0
        int loading = 0
        TreeSet<StorageControlRecord> visitedLo = new TreeSet<>()
        TreeSet<StorageControlRecord> visitedHi = new TreeSet<>()
        for (i in 0..23) {
            if (records[i].mode == StorageMode.GRID_CHARGE) {
                if (loading >= LOAD_COUNT || loadState >= LOAD_MAX) {
                    if (records[i].price < visitedLo.last().price) {
                        // skip most expensive previous record
                        def skip = visitedLo.last()
                        skip.mode = null
                        balance -= skip.cost
                        loadState -= skip.loadPwr
                        visitedLo.remove skip
                        loading--
                    } else {
                        // skip this record
                        records[i].mode = null
                        continue
                    }
                }
                loading++
                float loadPwr = loadState <= LOAD_MAX - LOAD_PWR ? LOAD_PWR : LOAD_MAX - loadState
                loadState += loadPwr
                float cost = records[i].price * LOSS_FACTOR * loadPwr
                balance += cost
                records[i].cost = cost
                records[i].loadPwr = loadPwr
                visitedLo << records[i]
            } else if (records[i].mode == StorageMode.AUTO) {
                if (loadState <= 0.5 * LOAD_PWR * unloadFactor) {
                    if (visitedHi.first() && records[i].price > visitedHi.first().price) {
                        // skip least expensive record
                        def skip = visitedHi.first()
                        skip.mode = null
                        balance -= skip.cost
                        loadState -= skip.loadPwr
                        loading++
                        visitedHi.remove skip
                    } else {
                        // skip this record
                        records[i].mode = null
                        continue
                    }
                }
                float unloadPwr = Math.min(loadState, LOAD_PWR * unloadFactor)
                loadState -= unloadPwr
                loading--
                float save = records[i].price * unloadPwr
                balance -= save
                records[i].cost = -save
                records[i].loadPwr = -unloadPwr
                visitedHi << records[i]
            }
        }
        loadState
    }

    static float calculate(List<StorageControlRecord> records) {
        float result
        records.findAll { it.mode in [StorageMode.GRID_CHARGE, StorageMode.AUTO] }
                .each { record ->
                    result += record.cost
                }
        result
    }

    /**
     * Mark all records with mode == null either as StorageMode.NO_DISCHARGE when between charging or discharging
     * records or as StorageMode.AUTO when before first charging or behind last discharging record. <br>
     * Also allocate any left charge in power storage to trailing records for better cost calculation.
     * @param records
     * @param leftLoad Load in power storage that was not used in tagged records
     */
    static void markIdle(List<StorageControlRecord> records, float leftLoad = 0) {
        def from = records.findIndexOf { it.mode }
        def to = records.findLastIndexOf { it.mode }
        for (i in 0..<from) {
            records[i].mode = StorageMode.AUTO
        }
        for (i in to + 1..<24) {
            records[i].mode = StorageMode.AUTO
            if (leftLoad >= 0.3) {
                float unloadPwr = Math.min(leftLoad, LOAD_PWR * unloadFactor)
                leftLoad -= unloadPwr
                float save = records[i].price * unloadPwr
                records[i].cost = -save
                records[i].loadPwr = -unloadPwr
            }
        }
        for (i in from + 1..<to) {
            if (!records[i].mode) {
                records[i].mode = StorageMode.NO_DISCHARGE
            }
        }

    }

    static List<PriceAt> makeTestData(DateTime dateTime = new DateTime(2024, 11, 1, 1, 0)) {
        TibberQueryRunner homeRunner = new TibberQueryRunner()
        def prices = homeRunner.runIntervalQuery(dateTime, 24)
        prices
    }

    static savePrices(List<PriceAt> prices, DateTime dateTime) {
        def home = System.getProperty('user.home')
        def testDataDir = "$home/.${EnergySettings.SETTINGS_DIR}/testdata"
        def dir = new File(testDataDir)
        if (!dir.exists()) {
            dir.mkdir()
        }
        if (dir.isDirectory()) {
            def out = []
            prices.each { PriceAt entry ->
                out << entry.price
            }

            def json = JsonOutput.toJson(out)
            json = JsonOutput.prettyPrint(json)
            def date = F_DAY.print(dateTime)
            def filename = "$PRICETABLE_FILE-${date}.json"
            new File(testDataDir, filename).withWriter { w ->
                w << json
            }
            return json
        }
    }

    static restorePrices() {
        def home = System.getProperty('user.home')
        def testDataDir = "$home/.${EnergySettings.SETTINGS_DIR}/testdata"
        def dir = new File(testDataDir)
        assert dir.exists()
        assert dir.isDirectory()
        List<File> files = [];
        dir.traverse(type: FILES, maxDepth: 0) { files.add(it) };
        def prices = [:]
        def slurper = new JsonSlurper()
        files.each {
            def json = it.text
            def data = slurper.parseText json
            def key = it.canonicalPath.replaceFirst(/[^-]*-/, '').replaceFirst(/.json/, '')
            prices << [(key): data]
        }
        prices.sort()

    }

    static void main(String[] args) {
//        def testdate = new DateTime(2025, 1, 1, 1, 0)
//        for (i in 0..<12) {
//            def loopdate = testdate.plusDays(i)
//            def testData = makeTestData(loopdate)
//            savePrices(testData, loopdate)
//        }
        def prices = restorePrices()
        float total
        for (i in 1..12) {
            def date = "25-01-${i < 10 ? '0' : ''}$i"
            def day0 = prices[date]
            if (day0) {
                print "$date: "
                def loop = optimizeTryBest(day0)
                def modes = loop.records.collect { it.mode }
                def rec = StorageControlRecord.createRecords(day0, modes)
                def saving = calculate(rec)
                def l = loop.saving ? "${loop.saving}" : "---"
                println "saving from loop: $l, saving from modes: $saving"
                def lrec = loop.records
                if (saving)
                    for (h in 0..<HOURS) {
                        if (rec[h].mode in [StorageMode.GRID_CHARGE, StorageMode.AUTO])
                            println "$h: rec.cost = ${rec[h].cost} lrec.cost = ${lrec[h].cost} => ${(lrec[h].cost - rec[h].cost).round(2)}"
                    }
            } else {
                println "$date: no values"
            }
        }
        println "Monat $total"
//        println optimizations(day0)
//        prices.each {println it}
    }

}

class StorageControlRecord implements Comparable {
    float price
    float cost
    float loadPwr
    int hour
    StorageMode mode

    static List<StorageControlRecord> createRecords(List<Float> prices) {
        def records = []
        prices.eachWithIndex { float price, int hour ->
            def record = new StorageControlRecord(price: price, hour: hour)
            records << record
        }
        records
    }

    static List<StorageControlRecord> createRecords(List<Float> prices, List<StorageMode> modes) {
        def records = []
        float loadHours = 0.0
        float loadHoursLimit = PowerStorageAutomation.LOAD_MAX / PowerStorageAutomation.LOAD_PWR
        prices.eachWithIndex { float price, int hour ->
            def record = new StorageControlRecord(price: price, hour: hour)
            if (modes) {
                def mode = modes[hour]
                float cost = 0.0
                record.mode = mode
                if (mode == StorageMode.GRID_CHARGE && loadHours < loadHoursLimit) {
                    float loadTime = Math.min(1.0, loadHoursLimit - loadHours)
                    loadHours += 1
                    cost = loadTime * price * PowerStorageAutomation.LOSS_FACTOR * PowerStorageAutomation.LOAD_PWR
                } else if (mode == StorageMode.AUTO && loadHours > 0.0) {
                    def unloadDuration = Math.min(loadHours, PowerStorageAutomation.unloadFactor)
                    loadHours -= unloadDuration
                    cost = -price * PowerStorageAutomation.LOAD_PWR * unloadDuration
                }
                record.cost = cost
            }
            records << record
        }
        records
    }

    @Override
    int compareTo(Object o) {
        assert o instanceof StorageControlRecord
        return price.compareTo(o.price)
    }

    int compareHour(StorageControlRecord o) {
        hour - o.hour
    }

    boolean before(StorageControlRecord o) {
        hour < o.hour
    }

    float minus(StorageControlRecord o) {
        price - o.price
    }

    StorageControlRecord multiply(float mul) {
        new StorageControlRecord(price: price * mul, hour: hour, mode: mode)
    }

    @Override
    String toString() {
        "{$hour: $price -> ${mode ? mode : '?'}}"
    }
}
