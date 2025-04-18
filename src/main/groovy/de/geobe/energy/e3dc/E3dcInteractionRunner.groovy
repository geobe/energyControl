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

package de.geobe.energy.e3dc

import io.github.bvotteler.rscp.RSCPTag
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

/**
 * run interactions with e3dc storage system by combining low level commands
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
    E3dcInteractions interactions

    private byte storageMode = AUTO
    private int watts = 0

    private static E3dcInteractionRunner runner

    static synchronized getInteractionRunner() {
        if (!runner) {
            runner = new E3dcInteractionRunner()
            def auth = runner.interactions.sendAuthentication(runner.e3dcPortalUser, runner.e3dcPortalPassword)
            if (auth == -1)
                throw new RuntimeException(E3dcError.AUTH)
        }
        runner
    }

    /**
     * Create and initialize object
     * @param filename of properties file
     */
    E3dcInteractionRunner(String filename = '/E3DC.properties') {
        def props = loadProperties(filename)
        storageIp = props.storageIp
        storagePort = Integer.decode(props.storagePort.toString())
        storagePassword = props.storagePassword
        e3dcPortalUser = props.e3dcPortalUser
        e3dcPortalPassword = props.e3dcPortalPassword
        interactions = new E3dcInteractions(storageIp, storagePort, storagePassword)
        if (!checkSameSubnet(storageIp))
            throw new RuntimeException(
                    "$E3dcError.IP $storageIp"
            )
    }

    /**
     * E3DC S10 storage system will allow RSCP access only to clients allocated on
     * the same ip.v4 subnet
     * @param storageIp ipv4 address of target E3DC system
     * @return true if on same subnet
     */
    static boolean checkSameSubnet(String storageIp) {
        def subnetIp = storageIp.find(~/\d+.\d+.\d+/)
        NetworkInterface.networkInterfaces.any {
            it.inetAddresses.any { adr ->
                adr.hostAddress.startsWith(subnetIp)
            }
        }
    }

    /**
     * E3dc storage system is quite SENSITIVE in setting non-auto storage modes. Modes other than AUTO
     * will return to AUTO automatically (no pun intended) after ~30 sec or when power values are requested,
     * maybe also when other requests are executed.
     * @param mode one of AUTO, IDLE, UNLOAD, LOAD, GRIDLOAD
     * @param watts set load power to watts
     * @return
     */
    def setStorageMode(byte mode, int watts) {
        if(mode in [AUTO, IDLE, UNLOAD, LOAD, GRIDLOAD]) {
            storageMode = mode
            this.watts = watts
        } else {
            storageMode = AUTO
            this.watts = 0
        }
    }

    /**
     * Implementation of {@link IStorageInteractionRunner#getCurrentValues()}
     * Get current values for PV production, grid in, grid out,
     * house consumption, battery SoC and maybe more
     * @return PowerValues record
     * @throws Exception, if connection to storage system fails
     */
    @Override
    PowerValues getCurrentValues() throws Exception {
        int loadPower = 0
        if(storageMode in [IDLE, UNLOAD, LOAD, GRIDLOAD]) {
            loadPower = storageLoadMode(storageMode, watts)
        }
        def current = interactions.sendRequest(E3dcRequests.liveDataRequests)
        new PowerValues(
                current.Timestamp,
                current.EMS_POWER_BAT,
                current.EMS_POWER_GRID,
                current.EMS_POWER_PV,
                current.EMS_POWER_HOME,
                current.EMS_BAT_SOC,
                loadPower
        )
    }

    /**
     * Implementation of {@link IStorageInteractionRunner#storageLoadMode(byte, int)}
     * Set storage system operation mode, e.g. load from grid
     * @param mode operation mode (auto - 0, idle - 1, unload - 2, load - 3, load from grid - 4)
     * All modes except auto will reset to auto after ca. 30 seconds.
     * @param watts set load power to watts
     * @return power that was actually set
     * @throws Exception, if connection to storage system fails
     */
    @Override
    def storageLoadMode(byte mode, int watts) throws Exception {
        if (mode in [AUTO, IDLE]) {
            watts = 0
        }
        def response = interactions.sendRequest(E3dcRequests.loadFromGridRequest(mode, watts))
        response.EMS_SET_POWER
    }

    /**
     * Implementation of {@link IStorageInteractionRunner#getHistoryValues} ()}.
     * E3DC uses java.time.Instant compatible timestamps to tag temporal data.
     * Method uses org.joda.time.DateTime for in and out parameters.
     * Get aggregated values for a number of time intervals
     * @param start starting time as DateTime for local time zone
     * @param interval time resolution in seconds, must be smaller than 68 years
     * @param count number of intervals
     * @return map of historyValue records with locale DateTime objects as keys
     * @throws Exception, if connection to storage system fails
     */
    @Override
    def getHistoryValues(DateTime start, long interval, int count) throws Exception {
        // we have to add difference between local time zone and UTC to DateTime value
        int offsetHours = DateTimeZone.default.getOffset(start).intdiv(3600000L)
        // found NO! maybe step back one ms else we would get into the next interval
        def normalisedStart = start.plusHours(offsetHours)//.minusMillis(0).withZone(DateTimeZone.UTC)
        Map<DateTime, HistoryValues> historyMap = new LinkedHashMap<DateTime, HistoryValues>()
        // we have to loop and call with count = 1 else we would get the summed up value over all intervals
        for (i in 0..<count) {
            def vals =
                    interactions.sendRequest(E3dcRequests.historyDataRequest(normalisedStart, interval, 1))
            if (vals.DB_HISTORY_DATA_DAY[0] == -1)
                break
            def valMap =
                    interactions.extractMapFromList(vals.DB_HISTORY_DATA_DAY[0].DB_SUM_CONTAINER)
//            println valMap
            def hisrec = new HistoryValues(
                    valMap.DB_BAT_POWER_IN, valMap.DB_BAT_POWER_OUT, valMap.DB_DC_POWER,
                    valMap.DB_GRID_POWER_IN, valMap.DB_GRID_POWER_OUT, valMap.DB_CONSUMPTION,
                    valMap.DB_CONSUMED_PRODUCTION
            )
            historyMap[start] = hisrec
            start = start.plusSeconds((int) interval)
            normalisedStart = normalisedStart.plusSeconds((int) interval)
        }
        historyMap
    }

    def storageStatus() {
        def responseStatus = interactions.sendRequest(E3dcRequests.emsValueRequest(RSCPTag.TAG_EMS_REQ_STATUS))
        def responseMode = interactions.sendRequest(E3dcRequests.emsValueRequest(RSCPTag.TAG_EMS_REQ_MODE))
        println "status: $responseStatus, mode: $responseMode"
    }

    /**
     * Implementation of {@link IStorageInteractionRunner#loadProperties(java.lang.String)} ()}
     * Load site specific values, passwords etc.
     * @param filename of property file
     * @return initialized properties
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

    static void main(String[] args) {
        def interactionRunner = getInteractionRunner()
        def live = interactionRunner.currentValues
        println "initial $live"

//        def dayOfMonth = DateTime.now().dayOfMonth
//        def start = new DateTime(2024, 6, dayOfMonth - 1, 0, 0)
//        def history = interactionRunner.getHistoryValues(start, HOUR, 2*24)
//        def ddmmyy =  DateTimeFormat.forPattern('dd.MM.yy HH:mm:ss')
//        def hmm =  DateTimeFormat.forPattern('H')
//        history.keySet().each { dateTime ->
//            if (dateTime.hourOfDay == 0) {
//                def day = ddmmyy.print(dateTime)
//                println("\n$day")
//            }
//            print "${hmm.print dateTime}: ${history[dateTime].homeConsumption().round()}, "
//        }

        def load
        for (i in 0..2) {
            load = interactionRunner.storageLoadMode(GRIDLOAD, 1000)
            live = interactionRunner.currentValues
            println "loop $i, live: $live, load: $load"
//            Thread.sleep(10000)
//            println "loop $i, live: $live"
            Thread.sleep(20000)
        }
//        live = interactionRunner.currentValues
//        println "after loop: $live"
//        load = interactionRunner.storageLoadMode(IDLE, 0)
//        println "Idle: <- $load"
//        Thread.sleep(10000)
//        live = interactionRunner.currentValues
//        println "set to idle: $live"
//        load = interactionRunner.storageLoadMode(AUTO, 3000)
//        println "Auto: <- $load"
//        Thread.sleep(10000)
//        live = interactionRunner.currentValues
//        println "reset to auto: $live"
//        Thread.sleep(10000)
//        live = interactionRunner.currentValues
//        println "reset to auto: $live"
//        Thread.sleep(10000)
//        live = interactionRunner.currentValues
//        println "reset to auto: $live"
//        Thread.sleep(10000)
//        live = interactionRunner.currentValues
//        println "reset to auto: $live"
        interactionRunner.interactions.closeConnection()
    }
}
