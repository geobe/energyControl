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
    UiStringsDE ts

    /** marker variable for last snapshots save to file */
    private int lastSaveDayOfYear = -42

    DateTimeFormatter full = DateTimeFormat.forPattern('dd.MM.yy HH:mm:ss')
    DateTimeFormatter hour = DateTimeFormat.forPattern('HH:mm:ss')
    DateTimeFormatter minute = DateTimeFormat.forPattern('mm:ss')
    DateTimeFormatter second = DateTimeFormat.forPattern('ss')
    DateTimeFormatter stamp = DateTimeFormat.forPattern('yy-MM-dd')

    GraphController(UiStringsDE uiStrings) {
        ts = uiStrings
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
            snapshots << snapshot
        }

    }

    def saveSnapshot(PowerValues powerValues, WallboxValues wallboxValues) {
        short cHome = powerValues.consumptionHome - wallboxValues.energy
        def snap = new Snapshot(
                powerValues.timestamp.getEpochSecond() * 1000, // for JodaTime multiply with 1000
                (short) powerValues.powerSolar,
                (short) powerValues.powerBattery,
                (short) powerValues.powerGrid,
                cHome,
                wallboxValues.energy,
                (short) powerValues.socBattery
        )
//        check
        snapshots.push snap
    }

    def clearSnapshots() {
        snapshots.clear()
    }

    def createSnapshotCtx(int size, int offset = 0) {
        def labels = []
        def lines = []

        size = Math.min(size - offset, snapshots.size())
        int index = 0
        List<Snapshot> worklist = []
        snapshots.each { Snapshot snap ->
            if (index >= offset && index < offset + size) {
                worklist.push snap
            }
            index++
        }

        Map sample = new Snapshot().toMap()
//        Map sample = worklist[0].toMap()
        Set keySet = sample.keySet() - 'instant'

        keySet.each { key ->
            def line = [
                    label  : ts.graphLabels[key],
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
            if (ix == 0) {
                label = "'${full.print(time)}'"
            } else if (ix % 60 == 0) {
                label = "'${hour.print(time)}'"
            } else if (ix % 12 == 0) {
                label = "'${minute.print(time)}'"
            } else {
                label = "'${second.print(time)}'"
            }
            labels << label
            def snapMap = snap.toMap()
            lines.each { line ->
                def key = line.key
                line.dataset << snapMap[key]
            }
        }
        def ctx = [
                graphTitle: ts.headingStrings.graphTitle,
                labels    : labels.toString(),
                lines     : lines
        ]
        ctx.putAll ts.graphLabels
        ctx
    }

    def mockShots(int n) {
        def now = DateTime.now().getMillis().intdiv(1000) * 1000
        for (i in 0..<n) {
            def snap = new Snapshot(
                    now - i * 5000,
                    (short) (Math.random() * 8000 - 3000),
                    (short) (Math.random() * 6000 - 3000),
                    (short) (Math.random() * 8000 - 4000),
                    (short) (Math.random() * -4000),
                    (short) (Math.random() * 3000),
                    (short) (Math.random() * 100)
            )
            snapshots << snap
        }
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
        def gc = new GraphController(new UiStringsDE())
        gc.mockShots(17280)
        gc.writeSnapshots()
        if(gc.todaysSnapshotExists()) {
            print 'todays snapshot file found'
        }
        def oldSnapshots = gc.readSnapshots()
        def snit = gc.snapshots.iterator()
        def upit = oldSnapshots.iterator()
        int n = 0
        while (snit.hasNext() && upit.hasNext()) {
            if(snit.next() != upit.next()) {
                println "different records at $n"
            }
            n++
        }
        if(n != 17280) {
            println "different length, 17280 <=> $n"
        }
//        def engine = new PebbleEngine.Builder().build()
//        def template = engine.getTemplate('template/graphtest.peb')
//
//        staticFiles.location("public")
//
//        get('/') { req, resp ->
//            def accept = req.headers('Accept')
//            resp.status 200
//            def out = new StringWriter()
//            def ctx = gc.createSnapshotCtx(120)
//            template.evaluate(out, ctx)
////            println out
//            out.toString()
//        }

    }

    final lineColors = [
            ePv       : Colors.w3Color.yellow,
            eBattery  : Colors.w3Color.orange,
            eGrid     : Colors.w3Color.blue_grey,
            eHome     : Colors.w3Color.purple,
            eCar      : Colors.w3Color.teal,
            socBattery: Colors.w3Color.deep_orange
    ]
}
