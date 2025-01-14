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

import de.geobe.energy.e3dc.E3dcChargingModeController
import de.geobe.energy.e3dc.E3dcInteractionRunner
import de.geobe.energy.recording.PowerCommunicationRecorder
import de.geobe.energy.tibber.PriceAt
import de.geobe.energy.web.EnergySettings
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

/**
 * Data structures and functionality for manual power buffering control
 */
class PowerStorageStatic implements PowerValueSubscriber, PowerPriceSubscriber {

    static final DEF_SOC_DAY = 75
    static final DEF_SOC_NIGHT = 75
    static final DEF_SOC_RESERVE = 0
    static final CHARGE_POWER = 3000
    static final NIGHT = [20, 21, 22, 23, 0, 1, 2, 3, 4, 5, 6, 7]
    static final DAY = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]
    static final socTargets = [0, 25, 40, 50, 60, 75, 90, 100]
    static final reserveTargets = [0, 10, 25, 50]
    static final powerFactorTargets = [100, 90, 80, 75, 60, 50, 40, 25]
    static final TIMETABLE_FILE = 'timetable.json'
    static final PRICETABLE_FILE = 'Pricetable'
    static final DateTimeFormatter F_DAY = DateTimeFormat.forPattern('yy-MM-dd-HH-mm')

    static synchronized PowerStorageStatic getPowerStorage() {
        if (!powerStorage) {
            powerStorage = new PowerStorageStatic()
        }
        powerStorage
    }

    private static PowerStorageStatic powerStorage

    enum StorageMode {
        AUTO,
        GRID_CHARGE,
        NO_DISCHARGE,
    }

    enum ChargeControlMode {
        INACTIVE,
        MANUAL,
        AUTO
    }

    static final HOURS = 24

    private volatile List<StorageMode> hourtable = new ArrayList<>(HOURS * 2)
    private volatile boolean active = false
    private volatile boolean savedActive = active
    private volatile ChargeControlMode chargeControlMode = ChargeControlMode.INACTIVE
    private volatile float saving = 0.0
    private StorageMode currentMode = StorageMode.AUTO
    private int socDay = DEF_SOC_DAY
    private int socNight = DEF_SOC_NIGHT
    private int socReserve = DEF_SOC_RESERVE
    private int lastHour = -1
    private DateTime controlPlanningStamp
    private CurrentPowerPrices planningPrices

    private E3dcChargingModeController chargingModeController

    private PowerStorageStatic() {
        int hour = DateTime.now().hourOfDay
        currentMode = hourtable[hour]
        chargingModeController = new E3dcChargingModeController(CHARGE_POWER, DEF_SOC_DAY)
        loadOrInitTimetable()
        PowerMonitor.monitor.subscribe(this)
        PowerPriceMonitor.monitor.subscribe(this)
    }

    /**
     * Realtime behaviour is triggered by the PowerMonitor instance reading new storage values.
     * Check every readout period (usually every 5 sec) if storage mode has changed since last period.
     * Changes may result from user input or proceeding to next hour in the hourtable. Changes are
     * passed along to the E3DdcChargingMode instance.
     * @param pmValues current power values
     */
    @Override
    void takePMValues(PMValues pmValues) {
        try {
            def now = DateTime.now().hourOfDay
            if (lastHour == -1) {
                lastHour = now
            } else if (lastHour > now) {
                shiftTimetable()
                PowerCommunicationRecorder.logMessage "Timetable shifted, now = $now, lastHour = $lastHour"
                lastHour = now
            } else {
                lastHour = now
            }
            if (!active) {
                return
            }
            def targetMode = hourtable[now]
            if (targetMode != currentMode) {
                currentMode = targetMode
//            def soc = pmValues.powerValues.socBattery()
//            println "storage mode set to $currentMode with soc $soc"
                byte e3dcMode
                int socNow = now in DAY ? socDay : socNight
                switch (targetMode) {
                    case StorageMode.AUTO:
                        e3dcMode = E3dcInteractionRunner.AUTO
                        socNow = socReserve
                        break
                    case StorageMode.GRID_CHARGE:
                        e3dcMode = E3dcInteractionRunner.GRIDLOAD
                        break
                    case StorageMode.NO_DISCHARGE:
                        e3dcMode = E3dcInteractionRunner.IDLE
                        break
                    default:
                        e3dcMode = E3dcInteractionRunner.AUTO
                        socNow = socReserve
                }
                chargingModeController.setChargingMode(e3dcMode, CHARGE_POWER, socNow, endDate)
                PowerCommunicationRecorder.recorder.powerStorageModeChanged(currentMode)
            }
        } catch (exception) {
            PowerCommunicationRecorder.logMessage "PowerStorageStatic exception $exception"
        }
    }

    @Override
    void takeMonitorException(Exception exception) {
        PowerCommunicationRecorder.logMessage exception
        setControlActive(false)
    }

    @Override
    void resumeAfterMonitorException() {
        PowerCommunicationRecorder.logMessage 'Resumed after exception'
        setControlActive(savedActive)
    }

    @Override
    void takePriceUpdate(CurrentPowerPrices prices) {
        if (chargeControlMode == ChargeControlMode.AUTO && prices.tomorrow) {
            if (!controlPlanningStamp || controlPlanningStamp.isBefore(DateTime.now())) {
                // set timestamp to tomorrow 01:00
                controlPlanningStamp = DateTime.now().withTimeAtStartOfDay().plusHours(25)
//                planTomorrow(prices)
            }
        }
    }

    def incModeAt(int hour) {
        if (hour in 0..47) {
            def current = hourtable[hour]
            def pos = current.ordinal()
            def inc = (pos + 1) % StorageMode.values().size()
            def newMode = StorageMode.values()[inc]
            hourtable[hour] = newMode
            saveTimetable()
            PowerCommunicationRecorder.recorder.powerStoragePresetChanged(newMode, hour)
        }
    }

    /**
     * change activity planning and activity mode of control task and propagate to active state control
     * @param mode one of the three charge control modes
     */
    void setChargeControlMode(ChargeControlMode mode) {
        this.@chargeControlMode = mode
        PowerCommunicationRecorder.recorder.powerStorageControlModeChanged(mode)
        if (mode in [ChargeControlMode.AUTO, ChargeControlMode.MANUAL]) {
            setControlActive(true)
        } else {
            setControlActive(false)
        }

    }

    /**
     * change activity state of control task. make sure that E3DC runs in AUTO mode.
     * @param activityState true if PowerStorageStatic task is set to active
     * @param saveChange default is save to disk
     */
    void setControlActive(boolean activityState, boolean saveChange = true) {
        if (activityState && !active) {
            this.@savedActive = active = true
            chargingModeController.setChargingMode(E3dcInteractionRunner.AUTO, CHARGE_POWER, DEF_SOC_RESERVE, endDate)
            if (saveChange) saveTimetable()
        } else if (active) {
            this.@savedActive = active = false
            chargingModeController.setChargingMode(E3dcInteractionRunner.AUTO, CHARGE_POWER, DEF_SOC_RESERVE, endDate)
            chargingModeController.stopChargeControl()
            if (saveChange) saveTimetable()
        }
    }

    def resetTomorrow() {
        for (i in 0..<HOURS) {
            hourtable[HOURS + i] = StorageMode.AUTO
        }
        saveTimetable()
    }

    def optimizeTomorrow() {
        def pricesAt = PowerPriceMonitor.monitor.latestPrices.tomorrow
        if(pricesAt) {
            def prices = []
            pricesAt.each {prices << it.price }
            def optimum = PowerStorageAutomation.optimizeTryBest(prices)
            saving = optimum.saving
            List<StorageControlRecord> optimized = optimum.records
            if(saving) {
                for (i in 0..<HOURS) {
                    assert optimized[i].hour == i
                    hourtable[HOURS + i] = optimized[i].mode
                }
            }
        } else {
            saving = 0.0
        }
        saveTimetable()
    }

    def getEndDate() {
        def now = DateTime.now()
        def hourNow = now.hourOfDay
        if (hourNow in DAY) {
            new DateTime(now.year, now.monthOfYear, now.dayOfMonth, DAY.last(), 0)
        } else if (hourNow in NIGHT) {
            def end = new DateTime(now.year, now.monthOfYear, now.dayOfMonth, NIGHT.last(), 0)
            hourNow > NIGHT.last() ? end.plusDays(1) : end
        } else {
            now.hourOfDay().roundCeilingCopy()
        }
    }

    /**
     * copy settings from next day (i >= 24) to current day
     */
    def shiftTimetable() {
        for (i in 0..<HOURS) {
            hourtable[i] = hourtable[HOURS + i]
        }
    }

    /**
     * storage control timetable and all related parameters are saved as a json file
     */
    def saveTimetable() {
        def home = System.getProperty('user.home')
        def settingsDir = "$home/.${EnergySettings.SETTINGS_DIR}/"
        def dir = new File(settingsDir)
        if (!dir.exists()) {
            dir.mkdir()
        }
        if (dir.isDirectory()) {
            def out = [
                    active           : active,
                    chargeControlMode: chargeControlMode,
                    socDay           : socDay,
                    socNight         : socNight,
                    socReserve       : socReserve,
                    timetable        : hourtable
            ]
            def json = JsonOutput.toJson(out)
            json = JsonOutput.prettyPrint(json)
//            println "new JSON settings: $json"
            def settingsFile = new File(settingsDir, TIMETABLE_FILE).withWriter { w ->
                w << json
            }
        }

    }

    def loadOrInitTimetable() {
        def table
        def home = System.getProperty('user.home')
        def file = new File("$home/.${EnergySettings.SETTINGS_DIR}/", "${TIMETABLE_FILE}")
        if (file.exists() && file.text) {
            def json = file.text
            def saved = new JsonSlurper().parseText(json)
            if (saved instanceof Map) {
                this.@active = saved.active
                this.@chargeControlMode = ChargeControlMode.valueOf(saved?.chargeControlMode ?: 'INACTIVE')
                this.@socDay = saved.socDay
                this.@socNight = saved.socNight
                this.@socReserve = saved.socReserve
                table = saved.timetable
                hourtable = string2StorageMode(table)
                setControlActive(active, false)
            }
        } else {
            for (i in 0..<HOURS * 2) {
                hourtable << StorageMode.AUTO
            }
            setControlActive(false)
        }
    }

    /**
     * transform list of strings to list of enums
     * @param key selects the hourtable
     * @param table saved timetables as read by jsonSlurper
     * @return hourtable as list of StorageMode enum values
     */
    List<StorageMode> string2StorageMode(List table) {
        List<StorageMode> listOfModes = new ArrayList<>(HOURS * 2)
        table?.eachWithIndex { String entry, int i ->
            try {
                listOfModes[i] = StorageMode.valueOf(entry)
            } catch (Exception ex) {
                listOfModes[i] = StorageMode.AUTO
            }
        }
        listOfModes
    }

    def getTimetable() {
        hourtable.asImmutable()
    }

    def getActive() {
        active
    }

    def getChargeControlMode() {
        chargeControlMode
    }

    def getStorageMode() {
        currentMode
    }

    def getSocDay() { socDay }

    def getSocNight() { socNight }

    def getSocReserve() { socReserve }

    def getSaving() { saving }

    def setSavedActive(boolean act) {
        this.@savedActive = act
        saveTimetable()
    }

    def setSocDay(int soc) {
        if (soc in socTargets) {
            this.@socDay = soc
            saveTimetable()
        }
    }

    def setSocNight(int soc) {
        if (soc in socTargets) {
            this.@socNight = soc
            saveTimetable()
        }
    }

    def setSocReserve(int soc) {
        if (soc in reserveTargets) {
            this.@socReserve = soc
            chargingModeController.socBlackoutReserve = socReserve
            saveTimetable()
        }
    }

    def savePrices(CurrentPowerPrices prices) {
        def home = System.getProperty('user.home')
        def settingsDir = "$home/.${EnergySettings.SETTINGS_DIR}/"
        def dir = new File(settingsDir)
        if (!dir.exists()) {
            dir.mkdir()
        }
        if (dir.isDirectory()) {
            def out = []
            prices.today.each { PriceAt entry ->
                out << entry.price
            }

            def json = JsonOutput.toJson(out)
            json = JsonOutput.prettyPrint(json)
            def date = F_DAY.print(DateTime.now())
            def filename = "$PRICETABLE_FILE-$date"
            new File(settingsDir, filename).withWriter { w ->
                w << json
            }
            return json
        }
    }

    def MIN_PRICE_DIFF = 0.050
    float LOSS = 1.1

    def planTomorrow(CurrentPowerPrices prices) {
        if (prices.tomorrow) {
            List<StorageControlRecord> scratch = []
            prices.yesterday.eachWithIndex { PriceAt entry, int i ->
                scratch <<  new StorageControlRecord(price: entry.price, hour : i)
            }
            def sorted = scratch.sort(false)
            outer:
            for (i in 23..12) {
                def hi = sorted[i]
                inner:
                for (j in 0..11) {
                    def lo = sorted[j]
                    def loplus = (lo * LOSS)
                    def diff = hi - loplus
                    if (hi.before(lo) || lo.mode) {
                        continue inner
                    } else if (diff < MIN_PRICE_DIFF) {
                        break outer
                    } else {
                        def lox = lo.hour
                        def hix = hi.hour
                        scratch[lox].mode = StorageMode.GRID_CHARGE
                        scratch[hix].mode = StorageMode.AUTO
                        continue outer
                    }

                }
            }
            println sorted
            println scratch
        }
    }

    static void main(String[] args) {
        PowerStorageStatic powerStorage = new PowerStorageStatic()
        def prices = PowerPriceMonitor.monitor.latestPrices
//        powerStorage.loadOrInitTimetable()
        def json = powerStorage.planTomorrow(prices)
//        println json
        System.exit(0)
    }
}
