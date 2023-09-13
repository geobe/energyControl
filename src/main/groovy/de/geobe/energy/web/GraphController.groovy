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

package de.geobe.energy.web

import de.geobe.energy.e3dc.PowerValues
import de.geobe.energy.go_e.WallboxValues
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

import java.util.concurrent.ConcurrentLinkedDeque

class GraphController {

    static final String snapshotPostfix = '-snapshots.bin'
    /** storage for energy values */
    private ConcurrentLinkedDeque<Snapshot> snapshots = new ConcurrentLinkedDeque<>()

    /** all static template strings for spark */
    Map<String, Map<String, String>> ts

    /** marker variable for last snapshots save to file */
    private int lastSaveDayOfYear = -42

    DateTimeFormatter date = DateTimeFormat.forPattern(' [EEE, dd.MM.yy]')
    DateTimeFormatter localDate = date
    DateTimeFormatter full = DateTimeFormat.forPattern('dd.MM.yy HH:mm:ss')
    DateTimeFormatter hour = DateTimeFormat.forPattern('H:mm:ss')
    DateTimeFormatter minute = DateTimeFormat.forPattern('mm:ss')
    DateTimeFormatter second = DateTimeFormat.forPattern('ss')
    DateTimeFormatter stamp = DateTimeFormat.forPattern('yy-MM-dd')

    GraphController(Map<String, Map<String, String>> uiStrings) {
        ts = uiStrings
    }

    def updateDateFormat(String iso) {
        localDate = date.withLocale(new Locale(iso))
    }

    /**
     * Initialize snapshots from file or add first value to snapshots
     * @param uiStrings
     */
    def init(Snapshot snapshot) {
        snapshots = readSnapshots()
        if(todaysSnapshotExists()) {
            println 'snapshots initialized from file'
        } else {
            'new snapshots started'
        }
        snapshots.push snapshot
    }

    def saveSnapshot(PowerValues powerValues, WallboxValues wallboxValues) {
        short cHome = powerValues.consumptionHome - wallboxValues.energy
        def snap = new Snapshot(
                powerValues.timestamp.toEpochMilli(),
                (short) powerValues.powerSolar,
                (short) powerValues.powerBattery,
                (short) powerValues.powerGrid,
                cHome,
                wallboxValues.energy,
                (short) powerValues.socBattery
        )
        def timestamp = powerValues.timestamp.toEpochMilli()
        def lastTimestamp = snapshots.peek().instant
        def dayOfYear = new DateTime(timestamp).dayOfYear
        def lastDayOfYear = new DateTime(lastTimestamp).dayOfYear
        if(dayOfYear != lastDayOfYear) {
            writeSnapshots()
            lastSaveDayOfYear = dayOfYear
            clearSnapshots()
        }
        snapshots.push snap
    }

    def clearSnapshots() {
        snapshots.clear()
    }

    def createGraphControlCtx(Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        ctx.putAll ti18n.graphControlStrings
        ctx
    }

    def createSizeValues(int size = 360) {
        def sizes = [5760, 2880, 1440, 720, 360, 180, 60]
        def ix = sizes.indexOf(size)
        if (ix == -1) {
            ix = 4
        }
        def values = [:]
        sizes.eachWithIndex { int sz, int i ->
            values['val' + sz] = "value=$sz ${i == ix ? ' selected' : ''}"
        }
        values
    }

    /**
     *
     * @param size # of data points "window" to be displayed
     * @param ti18n map of translation strings
     * @param offset relative position of "window" in percent
     * @return map of values for pebble template
     */
    def createSnapshotCtx(int size, Map<String, Map<String, String>> ti18n, int off = 0) {
        def labels = []
        def lines = []
        def datasize = snapshots.size()
        int offset
        int displaySize
        if (datasize <= size) {
            displaySize = datasize
            offset = 0
        } else {
            displaySize = size
            offset = (off * (datasize - displaySize)).intdiv(100)
        }

//        displaySize = Math.min(displaySize - offset, datasize)
        int index = 0
        List<Snapshot> worklist = []
        snapshots.each { Snapshot snap ->
            if (index >= offset && index < offset + displaySize) {
                worklist.push snap
            }
            index++
        }

        Map sample = new Snapshot().toMap()
        Set keySet = sample.keySet() - 'instant'

        keySet.each { key ->
            def line = [
                    label  : ti18n.graphLabels[key],
                    color  : lineColors[key],
                    key    : key,
                    yAxisID: (key == 'socBattery' ? 'y-right' : 'y-left'),
                    dataset: []
            ]
            lines << line
        }

        worklist.eachWithIndex { snap, ix ->
            def time = new DateTime(snap.instant)
            def label
            def minOfHour = time.minuteOfHour().get()
            if (ix == 0 || minOfHour == 0 || displaySize > 180) {
                label = "'${hour.print(time)}'"
//            } else if (isHour) {
//                label = "'${hour.print(time)}'"
//            } else if (ix % 12 == 0) {
//                label = "'${minute.print(time)}'"
            } else {
                label = "'${minute.print(time)}'"
            }
            labels << label
            def snapMap = snap.toMap()
            lines.each { line ->
                def key = line.key
                line.dataset << snapMap[key]
            }
        }
        def today = localDate.print DateTime.now()
        def ctx = [
                graphTitle: ti18n.headingStrings.graphTitle + today,
                labels    : labels.toString(),
                lines     : lines
        ]
        ctx.putAll ti18n.graphLabels
        ctx.putAll ti18n.graphControlStrings
        ctx
    }

    def writeSnapshots() {
        def dateStamp = DateTime.now()
        if (dateStamp.getMinuteOfDay() <=1) {
            dateStamp = dateStamp.minusDays(1)
        }
        def filename = "${stamp.print(dateStamp)}$snapshotPostfix"
        def home = System.getProperty('user.home')
        def settingsDir = "$home/.$EnergySettings.SETTINGS_DIR/"
        def dir = new File(settingsDir)
        if (!dir.exists()) {
            dir.mkdir()
        }
        if (dir.isDirectory()) {
            def file = new File(settingsDir, filename)
            file.withObjectOutputStream {outputStream ->
                outputStream.writeObject snapshots
            }
        }
    }

    ConcurrentLinkedDeque<Snapshot> readSnapshots(DateTime dateStamp = DateTime.now()) {
        ConcurrentLinkedDeque<Snapshot> oldSnapshots
        def home = System.getProperty('user.home')
        def filename = "${stamp.print(dateStamp)}$snapshotPostfix"
        def snapFile =
                new File("$home/.$EnergySettings.SETTINGS_DIR/$filename".toString())
        if (snapFile.exists()) {
            snapFile.withObjectInputStream {inputStream ->
                oldSnapshots = inputStream.readObject()
            }
        } else {
            oldSnapshots = new ConcurrentLinkedDeque<>()
        }
        oldSnapshots
    }

    def todaysSnapshotExists() {
        def filename = stamp.print(DateTime.now())+snapshotPostfix
        def home = System.getProperty('user.home')
        def settingsDir = "$home/.$EnergySettings.SETTINGS_DIR"
        def snapFile = new File("$settingsDir/$filename")
        snapFile.exists()
    }

    static void main(String[] args) {
        def gc = new GraphController([:])
        println gc.createSizeValues(1440)
    }

    final lineColors = [
            ePv       : Colors.w3Color.amber,
            eBattery  : Colors.w3Color.orange,
            eGrid     : Colors.w3Color.blue_grey,
            eHome     : Colors.w3Color.purple,
            eCar      : Colors.w3Color.teal,
            socBattery: Colors.w3Color.deep_orange
    ]
}
