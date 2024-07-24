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
import spark.Request
import spark.Response

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * An object of this class owns the data for graphic display of energy values.
 * It also stores and manages the state of display control widgets for the energy graph.
 */
class GraphController {

    static final String snapshotPostfix = '-snapshots.bin'
    /** all static template strings for spark */
    Map<String, Map<String, String>> txstrings

    /** marker variable for last snapshots save to file */
    private int lastSaveDayOfYear = -42

    static DateTimeFormatter date = DateTimeFormat.forPattern(' [EEE, dd.MM.yy]')
    static DateTimeFormatter localDate = date
    static DateTimeFormatter full = DateTimeFormat.forPattern('dd.MM.yy HH:mm:ss')
    static DateTimeFormatter hmmss = DateTimeFormat.forPattern('H:mm:ss')
    static DateTimeFormatter hour = DateTimeFormat.forPattern('HH')
    static DateTimeFormatter minute = DateTimeFormat.forPattern('mm:ss')
    static DateTimeFormatter second = DateTimeFormat.forPattern('ss')
    static DateTimeFormatter stamp = DateTimeFormat.forPattern('yy-MM-dd')
    static DateTimeFormatter pickerstamp = DateTimeFormat.forPattern('yyyy-MM-dd')
    static DateTimeFormatter day = DateTimeFormat.forPattern('dd.MM.')

    // global display values
    volatile int graphDataSize = 360
    volatile int graphOffset = 100
    volatile DateTime graphHistory = DateTime.now()
    volatile int updateFrequency = 6
    volatile boolean updatePause = false
    volatile int updateCounter = 0

    // remember size when going to history and back again
    volatile int sizeBackup

    /** storage for energy values */
    private ConcurrentLinkedDeque<Snapshot> snapshots = new ConcurrentLinkedDeque<>()

    /** cache for previous energy values */
    private static final int HISTORY_STACK_SIZE = 10
    private LinkedHashMap<DateTime, Deque<Snapshot>> previousValues = new LinkedHashMap<>(HISTORY_STACK_SIZE)
    /** states for graph display and update logic */
    enum GraphUiState {
        LIVE,
        PAUSED,
        HISTORY,
    }

    volatile GraphUiState graphUiState = GraphUiState.LIVE


    GraphController(Map<String, Map<String, String>> uiStrings) {
        txstrings = uiStrings
    }

    def updateDateFormat(String iso) {
        localDate = date.withLocale(new Locale(iso))
    }

    Map evalGraphPost(Request req, Response resp) {
        def accept = req.headers('Accept')
        def qparams = req.queryParams()
        def graphUpdate
        def p = req.queryParams('graphpause')
        boolean graphPaused = !(p == null || p.toString().empty || p.toString().contains('false'))
        int size
        int offset
        DateTime history
        if (qparams.contains('graphsize') && req.queryParams('graphsize').isInteger()) {
            size = req.queryParams('graphsize').toInteger()
        }
        if (qparams.contains('graphoffset') && req.queryParams('graphoffset').isInteger()) {
            offset = req.queryParams('graphoffset').toInteger()
        }
        if (qparams.contains('graphupdate') && req.queryParams('graphupdate').isInteger()) {
            graphUpdate = req.queryParams('graphupdate').toInteger()
        }
        if (qparams.contains('graphhistory') && req.queryParams('graphhistory').matches(/\d{4}-\d{2}-\d{2}/)) {
            def hist = req.queryParams('graphhistory')
            history = DateTime.parse(hist, pickerstamp)
        }
        if (graphUiState == GraphUiState.LIVE) {
            // check for transitions
            if (history && (history.dayOfYear != DateTime.now().dayOfYear ||
                    history.year != DateTime.now().year)) {
                // transition to history
                initHistoryState(history)
            } else if (offset < 100 || graphPaused) {
                // transition to paused
                initPauseState(offset, size)
            } else {
                graphDataSize = size
                updateFrequency = graphUpdate
            }
        } else if (graphUiState == GraphUiState.PAUSED) {
            if (offset == 100 || !graphPaused) {
                // transition to live
                initLiveState()
            } else if (history.dayOfYear != DateTime.now().dayOfYear) {
                // transition to history
                initHistoryState(history)
            } else {
                graphDataSize = size
                graphOffset = offset
            }
        } else if (graphUiState == GraphUiState.HISTORY) {
            if (history.dayOfYear == DateTime.now().dayOfYear || !graphPaused) {
                // transition to live
                graphDataSize = sizeBackup
                initLiveState()
            } else {
                graphHistory = history
                graphOffset = offset
                graphDataSize = size
            }
        }
        graphControlValues()
    }

    def initLiveState() {
        graphUiState = GraphUiState.LIVE
        graphOffset = 100
        updatePause = false
        graphHistory = DateTime.now()
    }

    def initPauseState(int offset, int size) {
        graphUiState = GraphUiState.PAUSED
        graphOffset = offset
        graphDataSize = size
        updatePause = true
        graphHistory = DateTime.now()
    }

    def initHistoryState(DateTime history) {
        graphUiState = GraphUiState.HISTORY
        sizeBackup = graphDataSize
        graphDataSize = 17280
        graphHistory = history
        updatePause = true
    }

    def graphControlValues() {
        def params = [:]
        params.put('graphPaused', updatePause)
        params.put('graphUpdate', updateFrequency)
        params.put('size', graphDataSize)
        params.put('graphOffset', graphOffset)
        params.put('graphHistory', pickerstamp.print(graphHistory))
        params.put('graphHistoryMax', pickerstamp.print(DateTime.now()))
        params.putAll(createSizeValues(graphDataSize))
        params
    }

    def createGraphControlCtx(Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        ctx.putAll ti18n.graphControlStrings
        ctx
    }

    def createSizeValues(int size = 360) {
        def sizes = [17280, 8640, 5760, 2880, 1440, 720, 360, 180, 60]
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

    private Deque<Snapshot> getHistorySnapshot() {
        def dateStamp = stamp.print(graphHistory)
        if (previousValues.keySet().contains(dateStamp)) {
            previousValues[dateStamp]
        } else {
            def snapFile = snapFile(graphHistory)
            if (snapFile.exists()) {
                def prevshots
                snapFile.withObjectInputStream { inputStream ->
                    prevshots = inputStream.readObject()
                }
                if (previousValues.size() >= HISTORY_STACK_SIZE) {
                    // remove eldest
                    def removekey = previousValues.keySet().toArray()[0]
                    previousValues.remove(removekey)
                }
                previousValues[dateStamp] = prevshots
                prevshots
            } else {
                graphHistory = DateTime.now()
                return snapshots
            }
        }
        previousValues[dateStamp]
    }

    /**
     * Prepare arguments for generating display context of energy graph for pebble
     * @param ti18n map of translation strings
     * @return map of values for pebble template
     */
    Map getSnapshotCtx(Map<String, Map<String, String>> ti18n) {
        Map result
        switch (graphUiState) {
            case GraphUiState.LIVE:
            case GraphUiState.PAUSED:
                result = createSnapshotCtx(graphDataSize, ti18n, 100 - graphOffset)
                break
            case GraphUiState.HISTORY:
                Deque<Snapshot> snaps = getHistorySnapshot()
                result = createSnapshotCtx(graphDataSize, ti18n, 100 - graphOffset, graphHistory, snaps)
        }
        result
    }

    /**
     * Generate display context of energy graph for pebble
     * @param size # of data points "window" to be displayed
     * @param ti18n map of translation strings
     * @param off relative position of "window" in percent
     * @param datestamp date label for graph, default now
     * @param snaps list ofsnapshot values, default todays snapshots
     * @return map of values for pebble template
     */
    def createSnapshotCtx(int size, Map<String, Map<String, String>> ti18n, int off,
                          DateTime datestamp = DateTime.now(),
                          Deque<Snapshot> snaps = snapshots) {
        def labels = []
        def lines = []
        def datasize = snaps.size()
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
        snaps.each { Snapshot snap ->
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
                label = "'${hmmss.print(time)}'"
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
        def today = localDate.print datestamp
        def ctx = [
                graphTitle: ti18n.headingStrings.graphTitle + today,
                labels    : labels.toString(),
                lines     : lines
        ]
        ctx.putAll ti18n.graphLabels
        ctx.putAll ti18n.graphControlStrings
        // make sure calendar input is syncrnous with graph
        ctx.put('graphHistory', pickerstamp.print(datestamp))
        ctx
    }

    /**
     * Initialize snapshots from file or add first value to snapshots
     * @param uiStrings
     */
    def init(Snapshot snapshot) {
        snapshots = readSnapshots()
        if (todaysSnapshotExists()) {
            println 'snapshots initialized from file'
        } else {
            println 'new snapshots started'
        }
        snapshots.push snapshot
    }

    /**
     * Store latest values in a list for the whole day. Save into file at midnight.
     * @param powerValues latest power values
     * @param wallboxValues latest wallbox values
     * @return the snapshots dequeue
     */
    def saveSnapshot(PowerValues powerValues, WallboxValues wallboxValues) {
        short energy = (wallboxValues?.energy)?:0
        short cHome = powerValues.consumptionHome - energy
        def snap = new Snapshot(
                powerValues.timestamp.toEpochMilli(),
                (short) powerValues.powerSolar,
                (short) powerValues.powerBattery,
                (short) powerValues.powerGrid,
                cHome,
                energy,
                (short) powerValues.socBattery
        )
        def timestamp = powerValues.timestamp.toEpochMilli()
        def lastTimestamp = snapshots.peek().instant
        def dayOfYear = new DateTime(timestamp).dayOfYear
        def lastDayOfYear = new DateTime(lastTimestamp).dayOfYear
        if (dayOfYear != lastDayOfYear) {
            writeSnapshots()
            lastSaveDayOfYear = dayOfYear
            clearSnapshots()
        }
        snapshots.push snap
    }

    /** clear snapshot list */
    def clearSnapshots() {
        snapshots.clear()
    }

    /**
     * write all snapshots of the day into a file with a defined file name based on todays date
     */
    def writeSnapshots() {
        def dateStamp = DateTime.now()
        if (dateStamp.getMinuteOfDay() <= 1) {
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
            file.withObjectOutputStream { outputStream ->
                outputStream.writeObject snapshots
            }
        }
    }

    /**
     * read a snapshot file of a given date into a list
     * @param dateStamp file date
     * @return list of that days values as a ConcurrentLinkedDeque
     */
    ConcurrentLinkedDeque<Snapshot> readSnapshots(DateTime dateStamp = DateTime.now()) {
        ConcurrentLinkedDeque<Snapshot> oldSnapshots
        def home = System.getProperty('user.home')
        def filename = "${stamp.print(dateStamp)}$snapshotPostfix"
        def snapFile =
                new File("$home/.$EnergySettings.SETTINGS_DIR/$filename".toString())
        if (snapFile.exists()) {
            snapFile.withObjectInputStream { inputStream ->
                oldSnapshots = inputStream.readObject()
            }
        } else {
            oldSnapshots = new ConcurrentLinkedDeque<>()
        }
        oldSnapshots
    }

    File snapFile(DateTime dateStamp) {
        def home = System.getProperty('user.home')
        def filename = "${stamp.print(dateStamp)}$snapshotPostfix"
        new File("$home/.$EnergySettings.SETTINGS_DIR/$filename".toString())
    }

    /**
     * Check of a snapshot file of current day exists. These files are written if the server was shut down
     * @return true if exists
     */
    def todaysSnapshotExists() {
        def filename = stamp.print(DateTime.now()) + snapshotPostfix
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
