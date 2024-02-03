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

import io.github.bvotteler.rscp.RSCPData
import io.github.bvotteler.rscp.RSCPDataType
import io.github.bvotteler.rscp.RSCPFrame
import io.github.bvotteler.rscp.helper.AES256Helper
import io.github.bvotteler.rscp.helper.BouncyAES256Helper
import io.github.bvotteler.rscp.helper.E3DCConnector
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

/**
 * Implements interactions with an E3DC storage system using rscp-e3dc library
 */
class E3dcInteractions {

    AES256Helper aesHelper
    final Socket socket

    E3dcInteractions(String ip, int port, String localPw) {
        aesHelper = new BouncyAES256Helper(localPw)
        socket = E3DCConnector.openConnection(ip, port)
    }

    /**
     * Authenticates connection with e3dc storage system
     * @param user login for e3dc portal, usually email address
     * @param password e3dc portal password
     * @return user level if successful, else -1
     */
    def sendAuthentication(user, password) {
        def values = sendRequest(E3dcRequests.authenticationRequest(user, password))
        values.RSCP_AUTHENTICATION ?: -1
    }

    /**
     * Send a request frame built from one or more rscp tags to the storage system, get the response frame
     * and decode it to a map
     * @param requestElements list of rscp tags
     * @return decoded response frame as map
     * @throws Exception, if connection to storage system fails
     */
    def sendRequest(List<RequestElement> requestElements) throws Exception {
        def requestFrame = E3dcRequests.requestsToFrame(requestElements)
//            def response = E3DCConnector
//                    .sendFrameToServer(socket, aesHelper.&encrypt, requestFrame)
//                    .flatMap { E3DCConnector.receiveFrameFromServer(socket, aesHelper.&decrypt) }
//                    .getOrElse(new byte[0])
        def sent = E3DCConnector.
                sendFrameToServer(socket, aesHelper.&encrypt, requestFrame)
        if (sent.left) {
            throw new RuntimeException(E3dcError.SEND, sent.getLeft())
        }
        def read  = sent.flatMap { E3DCConnector.receiveFrameFromServer(socket, aesHelper.&decrypt) }
        if (read.left) {
            throw new RuntimeException(E3dcError.READ, read.getLeft())
        }
        def response = read.get()
        if (response) {
            def frame = RSCPFrame.builder().buildFromRawBytes(response)
            def values = decodeFrame(frame)
            values
        }
    }

    /**
     * close connection to e3dc storage system
     */
    void closeConnection() {
        E3DCConnector.silentlyCloseConnection(socket)
    }

    static displayFrame(RSCPFrame frame) {
        def content = new StringBuffer()
        def at = new DateTime(frame.getTimestamp().toEpochMilli())
        content.append "response at $at\n"
        frame.data.each { data ->
            content.append displayData(data, 0)
        }
        content.toString()
    }


    static displayData(RSCPData data, int level) {
        def value
        def isContainer = data.dataType == RSCPDataType.CONTAINER
        value = isContainer ? '{ ' : RscpUtils.value(data)
        StringBuffer displayBuffer = new StringBuffer()
        def display = "${'  ' * level}${data.dataTag.name()} - ${data.dataType.name()}  $value\n"
        displayBuffer.append display.toString()
        if (data.dataType == RSCPDataType.CONTAINER) {
            data.containerData.each {
                displayBuffer.append displayData(it, level + 1)
            }
        }
        if (isContainer) {
            displayBuffer.append "${'  ' * level}}\n"
        }
        displayBuffer.toString()
    }

    /**
     * decode a rscp frame byte sequence into a map of contained data tags and add timestamp
     * @param frame usualy sent from e3dc storage system as reply to a request frame
     * @return map of [TAG_NAME, TAG_VALUE] elements
     */
    static decodeFrame(RSCPFrame frame) {
        def content = [:]
//        content.Timestamp = new DateTime(frame.getTimestamp().toEpochMilli(), DateTimeZone.UTC)
        content.Timestamp = frame.getTimestamp()
        frame.data.each { data ->
            content << decodeData(data)
        }
        content
    }

    /**
     * Decode a rscp data byte sequence into a map of contained tag/value pairs.
     * Recurse for Data of type CONTAINER
     * @param data
     * @return map of [TAG_NAME, TAG_VALUE] elements
     */
    static decodeData(RSCPData data) {
        def isContainer = data.dataType == RSCPDataType.CONTAINER
        if (isContainer) {
            def tag = data.dataTag.toString().replace('TAG_', '')
            def content = []
            data.containerData.each {
                content << decodeData(it)
            }
            [(tag): content]
        } else {
            RscpUtils.value(data)
        }
    }

    /**
     * Flatten result from historyDataRequest: All MapEntries within a list of single valued maps are
     * put into one map.
     * @param raw
     * @return map of [RSCPTag, value] elements
     */
    def extractMapFromList(List raw) {
        def result = [:]
        raw.each {
            if (it instanceof Map) {
                result << it
            }
        }
        result
    }

}
