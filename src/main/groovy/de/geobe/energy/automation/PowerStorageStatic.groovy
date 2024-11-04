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
    static final socTargets = [0, 10, 25, 50, 75, 90, 100]
    static final reserveTargets = [0, 10, 25, 50]

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

    private volatile List<StorageMode> timetable = new ArrayList<>(HOURS)
    private volatile boolean active = false
    private StorageMode currentMode = StorageMode.AUTO
    private int socDay = DEF_SOC_DAY
    private int socNight = DEF_SOC_NIGHT
    private int socReserve = DEF_SOC_RESERVE

    private E3dcChargingModeController chargingModeController

    private PowerStorageStatic() {
        for (i in 0..<HOURS) {
            timetable << StorageMode.AUTO
        }
        currentMode = StorageMode.AUTO
        chargingModeController = new E3dcChargingModeController(CHARGE_POWER, DEF_SOC_DAY)
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

    def incModeAt(int hour) {
        def current = timetable[hour]
        def pos = current.ordinal()
        def inc = (pos + 1) % StorageMode.values().size()
        timetable[hour] = StorageMode.values()[inc]
    }

    def setActive(boolean activityState) {
        if (activityState && !active) {
            PowerMonitor.monitor.subscribe(this)
            active = true
            chargingModeController.setChargingMode(E3dcInteractionRunner.AUTO, CHARGE_POWER, DEF_SOC_RESERVE, endDate)
        } else if (active) {
            active = false
            PowerMonitor.monitor.unsubscribe(this)
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
     * Realtime behaviour is triggered by the PowerMonitor instance reading new storage values.
     * Check every readout period (usually every 5 sec) if storage mode has changed since last period.
     * Changes may result from user input or proceeding to next hour in the timetable. Changes are
     * passed along to the E3DdcChargingMode instance.
     * @param pmValues current power values
     */
    @Override
    void takePMValues(PMValues pmValues) {
        def now = DateTime.now().hourOfDay
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
    }

    @Override
    void resumeAfterMonitorException() {

    }
}
