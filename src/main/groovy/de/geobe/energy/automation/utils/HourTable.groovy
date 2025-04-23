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

package de.geobe.energy.automation.utils

import de.geobe.energy.automation.PowerStorageStatic
import de.geobe.energy.web.EnergySettings
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 *
 */
class HourTable<MODE extends Enum> {
    static final HOURS = 24
    private subdir = ''
    private Class<MODE> modeClass
    private List modes
    private Map modeMap = [:]
    private volatile List<MODE> hourtable = new ArrayList<>(HOURS * 2)

    HourTable(Class<MODE> m, String sub = 'saved/') {
        subdir = sub
        modeClass = m
        modes = modeClass.enumConstants
        modes.each { mode ->
            modeMap[mode.toString()] = mode
        }
        init()
    }

//    /**
//     * Test if a string can be converted to an enum
//     * @param mode string (e.g. from saved states) representing an enum (or not)
//     * @return true, if enum
//     */
//    boolean isMode(String mode) {
//        modeMap.keySet().contains(mode)
//    }

    List getHourtable() {
        hourtable.clone()
    }

    MODE getAt(int i) {
        hourtable[i]
    }

    def getAt(Range range) {
        hourtable[range]
    }

    void putAt(int i, MODE v) {
        hourtable[i] = v
    }

    /**
     * cycle 1 step through the enum values of one cell. This is used
     * to process clicks on the ui
     * cycling through the possible settings
     * @param hour selected cell
     * @return clone of hourtable
     */
    def incModeAt(int hour) {
        MODE newMode
        if (hour in 0..<2 * HOURS) {
            def current = hourtable[hour]
            def pos = current.ordinal()
            def inc = (pos + 1) % modes.size()
            newMode = modes[inc]
            hourtable[hour] = newMode
            hourtable.clone()
        }
        newMode
    }

    /**
     * set a cell to a value
     * @param hour selected cell
     * @param v new value
     * @return clone of hourtable
     */
    def setModeAt(int hour, MODE v) {
        if (modes.contains(v)) {
            hourtable[hour] = v
        }
        hourtable.clone()
    }

    /**
     * copy settings from next day (i >= 24) to current day
     */
    def shiftHourtable() {
        for (i in 0..<HOURS) {
            hourtable[i] = hourtable[HOURS + i]
        }
        hourtable.clone()
    }

    /**
     * reset a range of cells, typically next day, to enum with ordinal 0
     * @param hours cells to reset
     * @return clone of hourtable
     */
    def resetHourtable(Range hours) {
        for (i in hours) {
            hourtable[i] = modes[0]
        }
        hourtable.clone()
    }

    /**
     * set a range of cells to given enum
     * @param hours cells to set
     * @return clone of hourtable
     */
    def setHourtable(Range hours, MODE v) {
        for (i in hours) {
            hourtable[i] = v
        }
        hourtable.clone()
    }

    /**
     * hourtable and a list of related parameters are saved as a json file
     * @param filename of json file
     * @param params map of keys and fields to be saved
     */
    void saveHourtable(String filename, Map params) {
        def home = System.getProperty('user.home')
        def settingsDir = "$home/.${EnergySettings.SETTINGS_DIR}/$subdir"
        def dir = new File(settingsDir)
        if (!dir.exists()) {
            dir.mkdir()
        }
        if (dir.isDirectory()) {
            def out = [timetable: hourtable]
            out.putAll(params)
            def json = JsonOutput.toJson(out)
            json = JsonOutput.prettyPrint(json)
            def settingsFile = new File(settingsDir, filename).withWriter { w ->
                w << json
            }
        }
    }

    /**
     * hourtable and a list of related parameters are restored from a json file
     * @param filename of json file
     * @param setters a map of keys and method pointers to setter methods to restore parameters from file
     * @return clone of hourtable
     */
    def loadOrInitHourtable(String filename, Map setters, Map defaults) {
        def table
        def home = System.getProperty('user.home')
        def file = new File("$home/.${EnergySettings.SETTINGS_DIR}/$subdir", "${filename}")
        if (file.exists() && file.text) {
            def json = file.text
            def saved = new JsonSlurper().parseText(json)
            if (saved instanceof Map) {
                setters.keySet().each { key ->
                    if(saved[key]) {
                        setters[key](saved[key])
                    }
                }
                table = saved.timetable
                hourtable = stringList2ModeList(table)
            }
        } else {
            init()
            defaults.keySet().each { key ->
                setters[key](defaults[key])
            }
        }
        hourtable.clone()
    }

    /**
     * Transform list of strings to list of enums. If a string
     * doesn't match an enum constant, use first enum value as a default
     * @param table saved timetables as read by jsonSlurper
     * @return hourtable as list of MODE enum values
     */
    private List<MODE> stringList2ModeList(List table) {
        List<MODE> listOfModes = new ArrayList<>(HOURS * 2)
        table?.eachWithIndex { String entry, int i ->
            listOfModes[i] = modeMap[entry] ?: modes[0]
        }
        listOfModes
    }

    private void init() {
        for (i in 0..<2 * HOURS) {
            hourtable[i] = modes[0]
        }
    }


    static void main(String[] args) {
        def ht = new HourTable(PowerStorageStatic.StorageMode)
        def m = ht.incModeAt(7)
        println "${m[0..12]}"
        m = ht.setModeAt(5, PowerStorageStatic.StorageMode.NO_DISCHARGE)
        println "${m[0..12]}"
        m = ht.hourtable
        ht.setModeAt(0, PowerStorageStatic.StorageMode.NO_DISCHARGE)
        println "${m[0..12]}"
        m = ht.hourtable
        println "${m[0..12]}"
        ht.saveHourtable('test', [hurz: 42])
    }

}

//class Testclass {
//    enum Momo {
//        SLEEPY,
//        HUNGRY,
//        PLAYING,
//        RUNNING
//    }
//
//    enum MomoState {
//        DRY,
//        WET,
//        DIRTY
//    }
//
//    def hourtable = new HourTable(Momo, 'debug/')
//    private MomoState state = MomoState.DRY
//    private int weight = 23
//
//    def init() {
//        hourtable.setHourtable(4..6, Momo.HUNGRY)
//        hourtable.setHourtable(14..16, Momo.HUNGRY)
//        hourtable.setModeAt(7, Momo.PLAYING)
//    }
//
//    void setWeight(Integer w) {
//        if (w)
//            this.@weight = w
//    }
//
//    void setState(String s) {
//        if (s && hourtable.isMode(s)) {
//            def st = MomoState.valueOf(s)
//            this.@state = st ?: MomoState.DIRTY
//        } else {
//            this.@state = MomoState.DIRTY
//        }
//    }
//
//    def saveToFile() {
//        def params = [
//                weight: weight,
//                state : state
//        ]
//        hourtable.saveHourtable('momotest.json', params)
//    }
//
//    def restoreFromFile() {
//        def setters = [
//                weight: this.&setWeight,
//                state : this.&setState
//        ]
//        hourtable.loadOrInitHourtable('momotest.json', setters)
//    }
//
//    String toString() {
//        hourtable.toString()
//    }
//
//    static void main(String[] args) {
//        def t = new Testclass()
//        t.restoreFromFile()
//        t.init()
//        t.saveToFile()
//        t.restoreFromFile()
//    }
//}
