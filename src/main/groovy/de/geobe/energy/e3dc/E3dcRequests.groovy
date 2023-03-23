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

import groovy.transform.Immutable
import groovy.transform.ImmutableOptions
import groovy.transform.RecordType
import io.github.bvotteler.rscp.RSCPData
import io.github.bvotteler.rscp.RSCPFrame
import io.github.bvotteler.rscp.RSCPTag

import java.time.Instant

class E3dcRequests {

    static vUint16 = { RSCPData.Builder b, short v ->
        b.uint16Value(v)
    }

    static batteryDataRequest() {
        def index = RSCPData.builder()
                .tag(RSCPTag.TAG_BAT_INDEX)
        vUint16.delegate = index
        index = vUint16(index, (short) 0).build()
//                .uint16Value((short) 0)
//                .build()
        def rsoc = RSCPData.builder()
                .tag(RSCPTag.TAG_BAT_REQ_RSOC)
                .noneValue()
                .build()
        def req = RSCPData.builder()
                .tag(RSCPTag.TAG_BAT_REQ_DATA)
                .containerValues([index, rsoc])
                .build()
        def frame = RSCPFrame.builder()
                .timestamp(Instant.now())
                .addData(req)
                .build()
        frame.asByteArray
    }

    static liveDataRequest() {
        def powerBat = RSCPData.builder()
                .tag(RSCPTag.TAG_EMS_REQ_POWER_BAT)
                .noneValue()
                .build()
        def powerGrid = RSCPData.builder()
                .tag(RSCPTag.TAG_EMS_REQ_POWER_GRID)
                .noneValue()
                .build()
        def powerHome = RSCPData.builder()
                .tag(RSCPTag.TAG_EMS_REQ_POWER_HOME)
                .noneValue()
                .build()
        def powerPv = RSCPData.builder()
                .tag(RSCPTag.TAG_EMS_REQ_POWER_PV)
                .noneValue()
                .build()
        def socBat = RSCPData.builder()
                .tag(RSCPTag.TAG_EMS_REQ_BAT_SOC)
                .noneValue()
                .build()
        def frame = RSCPFrame.builder()
                .timestamp(Instant.now())
                .addData([powerBat, powerGrid, powerHome, powerPv, socBat])
                .build()
        frame.asByteArray
    }

    static simpleRequest(List<Request> requests) {
        def builders = []
        requests.each {request ->
            def builder = RSCPData.builder()
                    .tag(request.tag)
            switch(request) {
                case ValRequest:
                    builder = request.buildVal(builder, request.val)
                    break
                default:
                    builder = builder.noneValue()
            }
            def data = builder.build()
            builders << data
        }
        def frame = RSCPFrame.builder()
                .timestamp(Instant.now())
                .addData(builders)
                .build()
        frame.asByteArray
    }

    static liveDataRequests = [
            new Request(tag: RSCPTag.TAG_EMS_REQ_POWER_BAT),
            new Request(tag: RSCPTag.TAG_EMS_REQ_POWER_GRID),
            new Request(tag: RSCPTag.TAG_EMS_REQ_POWER_HOME),
            new Request(tag: RSCPTag.TAG_EMS_REQ_POWER_PV),
            new Request(tag: RSCPTag.TAG_EMS_REQ_BAT_SOC)
    ]
}


class Request {
    RSCPTag tag = RSCPTag.TAG_NONE
}

class ValRequest extends Request {
    Object val
    Closure buildVal
}

//def uint16Value = { RSCPData.Builder b, short v -> b.uint16Value(v) }
