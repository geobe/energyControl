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
import io.github.bvotteler.rscp.RSCPDataType
import io.github.bvotteler.rscp.RSCPFrame
import io.github.bvotteler.rscp.helper.AES256Helper
import io.github.bvotteler.rscp.helper.BouncyAES256Helper
import io.github.bvotteler.rscp.helper.E3DCConnector
import io.github.bvotteler.rscp.sample.E3DCSampleRequests
import org.joda.time.DateTime

class E3dcInteractions {

    AES256Helper aesHelper
    final Socket socket
    byte[] authFrame

    E3dcInteractions(String ip, int port, String localPw, String e3User, String e3Pw) {
        aesHelper = new BouncyAES256Helper(localPw)
        socket = E3DCConnector.openConnection(ip, port)
        authFrame = E3DCSampleRequests.buildAuthenticationMessage(e3User, e3Pw)
    }

    def sendAuthentication() {
        def response = E3DCConnector.sendFrameToServer(socket, aesHelper.&encrypt, authFrame)
                .peek { println "bytes sent: $it" }
                .flatMap { E3DCConnector.receiveFrameFromServer(socket, aesHelper.&decrypt) }
                .peek { println "auth received ${it.length} bytes" }
                .getOrElse(new byte[0])
        if (response) {
            def frame = RSCPFrame.builder().buildFromRawBytes(response)
            println displayResponse(frame)
        }
    }

    def requestBatteryData() {
       def requestFrame = E3dcRequests.batteryDataRequest()
        def response = E3DCConnector
                .sendFrameToServer(socket, aesHelper.&encrypt, requestFrame)
                .flatMap {E3DCConnector.receiveFrameFromServer(socket, aesHelper.&decrypt)}
                .getOrElse(new byte[0])
        if (response) {
            def frame = RSCPFrame.builder().buildFromRawBytes(response)
            def values = RscpUtils.values(frame)
            println "Ladezustand ${values.BAT_DATA.BAT_RSOC.get()}"
            println displayResponse(frame)
        }
    }

    def closeConnection() {
        E3DCConnector.silentlyCloseConnection(socket)
    }

    def displayResponse(RSCPFrame frame) {
        def content = new StringBuffer()
        def at = new DateTime(frame.getTimestamp().toEpochMilli())
        content.append "response at $at\n"
        frame.data.each { data ->
            content.append displayData(data, 0)
        }
        content.toString()
    }

    def displayData(RSCPData data, int level) {
        def value
        def isContainer = data.dataType == RSCPDataType.CONTAINER
        value = isContainer ? '{ '  : RscpUtils.value(data)
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

}
