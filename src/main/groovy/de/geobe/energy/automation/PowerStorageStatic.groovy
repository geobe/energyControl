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
import de.geobe.energy.web.EnergySettings
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.joda.time.DateTime

/**
 * Data structures and functionality for manual power buffering control
 */
class PowerStorageStatic implements PowerValueSubscriber {

    static final DEF_SOC_DAY = 75
    static final DEF_SOC_NIGHT = 75
    static final DEF_SOC_RESERVE = 0
    static final CHARGE_POWER = 3000
    static final NIGHT = [20, 21, 22, 23, 0, 1, 2, 3, 4, 5, 6, 7]
    static final DAY = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]
    static final socTargets = [0, 25, 40, 50, 60, 75, 90, 100]
    static final reserveTargets = [0, 10, 25, 50]
    static final TIMETABLE_FILE = 'timetable.json'

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

    static final HOURS = 24

    private volatile List<StorageMode> timetable = new ArrayList<>(HOURS * 2)
    private volatile boolean active = false
    private volatile boolean savedActive = active
    private StorageMode currentMode = StorageMode.AUTO
    private int socDay = DEF_SOC_DAY
    private int socNight = DEF_SOC_NIGHT
    private int socReserve = DEF_SOC_RESERVE
    private int lastHour = -1

    private E3dcChargingModeController chargingModeController

    private PowerStorageStatic() {
        loadOrInitTimetable()
        int hour = DateTime.now().hourOfDay
        currentMode = timetable[hour]
        chargingModeController = new E3dcChargingModeController(CHARGE_POWER, DEF_SOC_DAY)
        PowerMonitor.monitor.subscribe(this)
    }

    /**
     * Realtime behaviour is triggered by the PowerMonitor instance reading new storage values.
     * Check every readout period (usually every 5 sec) if storage mode has changed since last period.
     * Changes may result from user input or proceeding to next hour in the timetable. Changes are
     * passed along to the E3DdcChargingMode instance.
     * @param pmValues current power values
     */
    @Override
    void takePMValues(PMValues pmValues) {
        def now = DateTime.now().hourOfDay
        if (lastHour == -1) {
            lastHour = now
        } else if (lastHour > now) {
            shiftTimetable()
        }
        if (!active) {
            return
        }
        def targetMode = timetable[now]
        if (targetMode != currentMode) {
            currentMode = targetMode
            def soc = pmValues.powerValues.socBattery()
            println "storage mode set to $currentMode with soc $soc"
            byte e3dcMode
            switch (targetMode) {
                case StorageMode.AUTO:
                    e3dcMode = E3dcInteractionRunner.AUTO
                    break
                case StorageMode.GRID_CHARGE:
                    e3dcMode = E3dcInteractionRunner.GRIDLOAD
                    break
                case StorageMode.NO_DISCHARGE:
                    e3dcMode = E3dcInteractionRunner.IDLE
                    break
                default:
                    e3dcMode = E3dcInteractionRunner.AUTO
            }
            chargingModeController.setChargingMode(e3dcMode, CHARGE_POWER, DEF_SOC_RESERVE, endDate)
        }
    }

    @Override
    void takeMonitorException(Exception exception) {
        println exception
        setActive(false)
    }

    @Override
    void resumeAfterMonitorException() {
        setActive(savedActive)
    }

    def incModeAt(int hour) {
        if (hour in 0..47) {
            def current = timetable[hour]
            def pos = current.ordinal()
            def inc = (pos + 1) % StorageMode.values().size()
            timetable[hour] = StorageMode.values()[inc]
            saveTimetable()
        }
    }

    def setActive(boolean activityState) {
        if (activityState && !active) {
            savedActive = active = true
            chargingModeController.setChargingMode(E3dcInteractionRunner.AUTO, CHARGE_POWER, DEF_SOC_RESERVE, endDate)
        } else if (active) {
            savedActive = active = false
            chargingModeController.stopChargeControl()
        }
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
            timetable[i] = timetable[HOURS + i]
        }
    }

    def saveTimetable() {
        def home = System.getProperty('user.home')
        def settingsDir = "$home/.${EnergySettings.SETTINGS_DIR}/"
        def dir = new File(settingsDir)
        if (!dir.exists()) {
            dir.mkdir()
        }
        if (dir.isDirectory()) {
            def out = [
                    active    : active,
                    socDay    : socDay,
                    socNight  : socNight,
                    socReserve: socReserve,
                    timetable : timetable
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
                active = saved.active
                socDay = saved.socDay
                socNight = saved.socNight
                socReserve = saved.socReserve
                table = saved.timetable
                timetable = string2StorageMode(table)
                return
            }
        }
        for (i in 0..<HOURS * 2) {
            timetable << StorageMode.AUTO
        }
    }

    /**
     * transform list of strings to list of enums
     * @param key selects the timetable
     * @param table saved timetables as read by jsonSlurper
     * @return timetable as list of StorageMode enum values
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
        timetable.asImmutable()
    }

    def getStorageMode() {
        currentMode
    }

    def getSocDay() { socDay }

    def getSocNight() { socNight }

    def getSocReserve() { socReserve }

    def setSocDay(int soc) {
        if (soc in socTargets) {
            socDay = soc
        }
    }

    def setSocNight(int soc) {
        if (soc in socTargets) {
            socNight = soc
        }
    }

    def setSocReserve(int soc) {
        if (soc in reserveTargets) {
            socReserve = soc
        }
    }

    static void main(String[] args) {
        PowerStorageStatic powerStorage = new PowerStorageStatic()
//        powerStorage.loadOrInitTimetable()
        powerStorage.shiftTimetable()
        println powerStorage.timetable
    }
}
