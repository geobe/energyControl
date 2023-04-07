/*
 *  MIT License
 *
 *  Copyright (c) 2023. Georg Beier
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package de.geobe.energy.e3dc

import org.joda.time.DateTime
import org.joda.time.DateTimeZone

/**
 *
 */
class E3dcInteractionRunner implements IStorageInteractionRunner {

    static final MINUTE = 60L
    static final HOUR = 3600L
    static final DAY = 24 * 3600L
    static final WEEK = 7 * 24 * 3600L
    static final byte AUTO = 0x00
    static final byte IDLE = 0x01
    static final byte UNLOAD = 0x02
    static final byte LOAD = 0x03
    static final byte GRIDLOAD = 0x04

    String storageIp = ''
    int storagePort
    String storagePassword = ''
    String e3dcPortalUser = ''
    String e3dcPortalPassword = ''
    int e3dcUtcOffset
    E3dcInteractions interactions

    static void main(String[] args) {
        def runner = new E3dcInteractionRunner()
        def auth =  runner.interactions.sendAuthentication(runner.e3dcPortalUser, runner.e3dcPortalPassword)
        println auth
        def live = runner.currentValues
        println "initial $live"
//        def load
//        for (i in 0..2) {
//            load = runner.storageLoadMode(GRIDLOAD, 3000)
//            println "Gridload: <- $load"
//            Thread.sleep(10000)
//            live = runner.currentValues
//            println "loop $i, live: $live"
//            Thread.sleep(10000)
//        }
//        live = runner.currentValues
//        println "after loop: $live"
//        load = runner.storageLoadMode(IDLE, 0)
//        println "Idle: <- $load"
//        Thread.sleep(10000)
//        live = runner.currentValues
//        println "set to idle: $live"
//        load = runner.storageLoadMode(AUTO, 3000)
//        println "Auto: <- $load"
//        Thread.sleep(10000)
//        live = runner.currentValues
//        println "reset to auto: $live"
//        Thread.sleep(10000)
//        live = runner.currentValues
//        println "reset to auto: $live"
//        Thread.sleep(10000)
//        live = runner.currentValues
//        println "reset to auto: $live"
//        Thread.sleep(10000)
//        live = runner.currentValues
//        println "reset to auto: $live"
        runner.interactions.closeConnection()
    }

    /**
     * Create and initialize object
     * @param filename of properties file
     */
    E3dcInteractionRunner(String filename = '/E3DC.properties') {
        def props = loadProperties(filename)
        storageIp = props.storageIp
        storagePort = Integer.decode(props.storagePort.toString())
        e3dcUtcOffset = Integer.decode(props.e3dcUtcOffset.toString())
        storagePassword = props.storagePassword
        e3dcPortalUser = props.e3dcPortalUser
        e3dcPortalPassword = props.e3dcPortalPassword
        interactions = new E3dcInteractions(storageIp, storagePort, storagePassword)
    }

    /**
     * {@link IStorageInteractionRunner#getCurrentValues()}
     */
    @Override
    def getCurrentValues() {
        def current = interactions.sendRequest(E3dcRequests.liveDataRequests)
        new CurrentValues(
                current.Timestamp,
                current.EMS_POWER_BAT,
                current.EMS_POWER_GRID,
                current.EMS_POWER_PV,
                current.EMS_POWER_HOME,
                current.EMS_BAT_SOC
        )
    }

    /**
     * E3DC seems to ignore summer time. DateTime values given in UTC time seem to retrieve the intended
     * values for local winter time :=( . So the result map corrects this
     * {@link IStorageInteractionRunner#getHistoryValues} ()}
     */
    @Override
    def getHistoryValues(DateTime start, long interval, int count) {
        def normalisedStart = start.minusSeconds(1) // step back one ms else we would get into the next interval
        def localTime = new DateTime(start.minusHours(e3dcUtcOffset), DateTimeZone.forOffsetHours(e3dcUtcOffset))
        Map<DateTime, HistoryValues> historyMap = new LinkedHashMap<DateTime, HistoryValues>()
        // we have to loop and call with count = 1 else we would get the summed up value over all intervals
        for (i in 0..<count) {
            def vals =
                    interactions.sendRequest(E3dcRequests.historyDataRequest(normalisedStart, interval, 1))
            def valMap =
                    interactions.extractMapFromList(vals.DB_HISTORY_DATA_DAY[0].DB_SUM_CONTAINER)
            def hisrec = new HistoryValues(
                    valMap.DB_BAT_POWER_IN, valMap.DB_BAT_POWER_OUT, valMap.DB_BAT_POWER_OUT,
                    valMap.DB_GRID_POWER_IN, valMap.DB_GRID_POWER_OUT, valMap.DB_CONSUMPTION,
                    valMap.DB_CONSUMED_PRODUCTION

            )
            historyMap[localTime] = hisrec
            localTime = localTime.plusSeconds((int) interval)
            normalisedStart = normalisedStart.plusSeconds((int) interval)
        }
        historyMap
    }

    /**
     * {@link IStorageInteractionRunner#setLoadFromGrid(int)} ()}
     */
    @Override
    def storageLoadMode(byte mode, int watts) {
        if(mode in [AUTO, IDLE]) {
            watts = 0
        }
        def response = interactions.sendRequest(E3dcRequests.loadFromGridRequest(mode, watts))
        response.EMS_SET_POWER
    }

    /**
     * {@link IStorageInteractionRunner#loadProperties(java.lang.String)} ()}
     */
    @Override
    def loadProperties(String filename) {
        Properties props = new Properties()
        def r = this.getClass().getResource(filename)
        r.withInputStream {
            props.load(it)
        }
        props
    }
}
