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

package de.geobe.energy.heatpump

class HeatpumpControllerProxy {
    private hpcProtocol
    private hpcHost
    private hpcPort
    private hpcPath
    private URL hpcUrl

    static void main(String[] args) {
        HeatpumpControllerProxy controller = new HeatpumpControllerProxy()
        println "${controller.state}"
//        while (true) {
//            print "Eingabe Normal, Suspend, Precedence, Enforced, Aktuell, eXit > "
//            def r = java.lang.System.in.newReader().readLine()
//            if (r.toUpperCase().startsWith('N')) {
//                controller.state = HeatPumpState.NORMALOPERATION
//            } else if (r.toUpperCase().startsWith('P')) {
//                controller.state = HeatPumpState.PRECEDENCE
//            } else if (r.toUpperCase().startsWith('S')) {
//                controller.state = HeatPumpState.SUSPENDED
//            } else if (r.toUpperCase().startsWith('A')) {
//                println "${controller.state}"
//            } else if (r.toUpperCase().startsWith('E')) {
//                try {
//                    controller.state = HeatPumpState.ENFORCED
//                } catch (Exception e) {
//                    println "$e -> ${controller.state}"
//                }
//            } else if (r.toUpperCase().startsWith('X')) {
//                break
//            } else {
//                println "read: $r"
//            }
    }

HeatpumpControllerProxy(String configfile = '/heatpump.properties') {
    Properties props = new Properties()
    def r = this.getClass().getResource(configfile)
    r.withInputStream {
        props.load(it)
    }
    hpcProtocol = props.protocol
    hpcHost = props.host
    hpcPort = Integer.decode props.port
    hpcPath = props.path
    hpcUrl = new URL(hpcProtocol, hpcHost, hpcPort, hpcPath)
}

def getState() {
    def hpcConn = hpcUrl.openConnection()
    hpcConn.setRequestProperty('Accept', 'text/json')
    def respCode = hpcConn.getResponseCode()
    def response
    if (respCode == 200) {
        response = hpcConn.inputStream.text
    } else {
        response = respCode
    }
    response
}

def setState(HeatPumpState state) {
    def hpcConn = hpcUrl.openConnection()
    hpcConn.setRequestProperty('Accept', 'text/json')
    hpcConn.with {
        doOutput = true
        requestMethod = 'POST'
        outputStream.with { writer ->
            writer << "sg-state=$state".toString()
        }
        def result = content.text
        result
    }
}

}
