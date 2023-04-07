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

import io.github.bvotteler.rscp.RSCPData
import io.github.bvotteler.rscp.RSCPFrame
import io.github.bvotteler.rscp.RSCPTag
import org.joda.time.DateTime

import java.time.Duration
import java.time.Instant

import static io.github.bvotteler.rscp.RSCPData.Builder as DB

/**
 * Utility class with methods for simpler construction of RSCP Tags
 */
class E3dcRequests {

    /**
     * A unifying method to build RSCPData objects combining several builder methods
     * @param tag Tag as defined by E3DC
     * @param setValue setter method as closure for given data type, default noneValue
     * @param value typed value as expected by setter method, default null
     * @return the ready built RSCPData object
     */
    static RSCPData buildTaggedData(RSCPTag tag, Closure<DB> setValue = DB.&noneValue, def value = null) {
        DB builder = RSCPData.builder().tag(tag)
        setValue = setValue.rehydrate(builder, builder, builder)
        builder = (value != null) ? setValue.call(value) : setValue.call()
        builder.build()
    }

    /**
     * A unifying method to build RSCPFrame byte arrays combining several builder methods.<br>
     * Starting with a list of RequestElement objects, these are transformed into a List of RSCPData
     * objects. Then a RSCPFrame is built from these. At the end, a byte array is returned to be sent to the
     * E3DC device.
     * @param requests List of RequestElement objects
     * @return byte array ready for encryption and transmission
     */
    static requestsToFrame(List<RequestElement> requests) {
        def dataList = requestDataList(requests)
        def frame = RSCPFrame.builder()
                .timestamp(Instant.now())
                .addData(dataList)
                .build()
        def bytes = frame.asByteArray
        bytes
    }

    /**
     * Transform list of RequestElements into list of RSCPData elements
     * @param requestElements
     * @return list of RSCPData elements
     */
    static requestDataList(List<RequestElement> requestElements) {
        def dataList = []
        requestElements.each { requestElement ->
            def val = requestElement.val
            if (val instanceof List) {
                val = requestDataList(val)
            }
            def data = buildTaggedData(requestElement.tag, requestElement.buildVal, val)
            dataList << data
        }
        dataList
    }

    /**
     * RequestElement data list to get current live data
     */
    static liveDataRequests = [
            new RequestElement(tag: RSCPTag.TAG_EMS_REQ_POWER_BAT),
            new RequestElement(tag: RSCPTag.TAG_EMS_REQ_POWER_GRID),
            new RequestElement(tag: RSCPTag.TAG_EMS_REQ_POWER_HOME),
            new RequestElement(tag: RSCPTag.TAG_EMS_REQ_POWER_PV),
            new RequestElement(tag: RSCPTag.TAG_EMS_REQ_BAT_SOC)
    ]

    /**
     * RequestElement data list to get calculated battery state of charge (higher percision).<br>
     * This is an example for a nested list: RequestElement for TAG_BAT_REQ_DATA contains a list of requests.
     */
    static batteryDataRequests = [
            new RequestElement(tag: RSCPTag.TAG_BAT_REQ_DATA, buildVal: DB.&containerValues, val: [
                    new RequestElement(tag: RSCPTag.TAG_BAT_INDEX, buildVal: DB.&uint16Value, val: (short) 0),
                    new RequestElement(tag: RSCPTag.TAG_BAT_REQ_RSOC)
            ])
    ]

    /**
     * Requestlement data list for retrieval of history data
     */
    static historyDataRequest = { DateTime start, long interval, int intervals ->
        def instant = Instant.ofEpochMilli(start.millis)
        def length = Duration.ofSeconds(interval)
        def span = Duration.ofSeconds(interval * intervals)
        [
                new RequestElement(tag: RSCPTag.TAG_DB_REQ_HISTORY_DATA_DAY, buildVal: DB.&containerValues, val: [
                        new RequestElement(tag: RSCPTag.TAG_DB_REQ_HISTORY_TIME_START, buildVal: DB.&timestampValue, val: instant),
                        new RequestElement(tag: RSCPTag.TAG_DB_REQ_HISTORY_TIME_INTERVAL, buildVal: DB.&timestampValue, val: length),
                        new RequestElement(tag: RSCPTag.TAG_DB_REQ_HISTORY_TIME_SPAN, buildVal: DB.&timestampValue, val: span)
                ])
        ]
    }

    /**
     * Requestlement data list for authentication
     */
    static authenticationRequest = { String user, String password ->
        [
                new RequestElement(tag: RSCPTag.TAG_RSCP_REQ_AUTHENTICATION, buildVal: DB.&containerValues, val: [
                        new RequestElement(tag: RSCPTag.TAG_RSCP_AUTHENTICATION_USER, buildVal: DB.&stringValue, val: user),
                        new RequestElement(tag: RSCPTag.TAG_RSCP_AUTHENTICATION_PASSWORD, buildVal: DB.&stringValue, val: password)
                ])
        ]
    }

    /**
     * set ems mode: Description taken from E3DC documentation (German).<br>
     * Mit diesem TAG kann in die Regelung des S10s eingegriffen werden.
     * Bei DC-Systemen ist die Ladeleistung auf die anliegende PV-Leistung beschränkt,
     * bei AC und Hybrid-Systemen kann die Ladeleistung auch größer der PV-Leistung sein.
     * Achtung: Wenn mit diesem Kommando eingegriffen wird, wird eine eventuell
     * gesetzte Einspeisereduzierung NICHT beachtet!
     * Achtung: Das Kommando muss mindestens alle 30 Sekunden gesetzt werden,
     * ansonsten geht das EMS in den Normalmodus.
     * @param powerMode Der Modus in den das S10 gehen soll: <ul>
     *     <li>AUTO/NORMAL MODUS  0 </li>
     *     <li>IDLE MODUS 1</li>
     *     <li>ENTLADEN MODUS 2</li>
     *     <li>LADEN MODUS 3<li>
     *     <li>NETZ_LADE MODUS 4</li>
     * </ul>
     * @param powerRequest loading power in W
     */
    static loadFromGridRequest = { byte powerMode, int powerRequest ->
        // TAG_EMS_REQ_SET_POWER [TAG_EMS_REQ_SET_POWER_MODE 4(UChar8), TAG_EMS_REQ_SET_POWER(Int32) 3000?]
        [
                new RequestElement(tag: RSCPTag.TAG_EMS_REQ_SET_POWER, buildVal: DB.&containerValues, val: [
                        new RequestElement(tag: RSCPTag.TAG_EMS_REQ_SET_POWER_MODE, buildVal: DB.&uchar8Value, val: powerMode),
                        new RequestElement(tag: RSCPTag.TAG_EMS_REQ_SET_POWER_VALUE, buildVal: DB.&int32Value, val: powerRequest)
                ])
        ]
    }

}

/**
 * A data structure that simplifies the definition of requests the Groovy way to be sent to the E3DC system
 */
class RequestElement {
    RSCPTag tag = RSCPTag.TAG_NONE
    Closure buildVal = DB.&noneValue
    Object val = null
}
