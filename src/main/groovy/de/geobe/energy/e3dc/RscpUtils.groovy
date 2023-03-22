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

class RscpUtils {

    static values(List<RSCPData> dataList) {
        def values = [:]
        dataList.each {data ->
            values << value(data)
        }
    }

    static values(RSCPFrame frame) {
        def values = []
        frame.data.each {data ->
            values << value(data)
        }
        values.size() == 1 ? values[0] : values
    }

    static value(RSCPData data) {
        def tag = data.dataTag.toString().replace('TAG_', '')
        def datamap = [:]
        def val
        def vals = [:]
        switch (data.dataType) {
            case RSCPDataType.BOOL:
            case RSCPDataType.BITFIELD:
            case RSCPDataType.BYTEARRAY:
            case RSCPDataType.ERROR:
                val = data.valueAsByteArray
                break
            case RSCPDataType.CHAR8:
            case RSCPDataType.UCHAR8:
            case RSCPDataType.INT16:
            case RSCPDataType.UINT16:
                val = data.valueAsShort
                break
            case RSCPDataType.INT32:
            case RSCPDataType.UINT32:
                val = data.valueAsInt
                break
            case RSCPDataType.INT64:
            case RSCPDataType.UINT64:
                val = data.valueAsLong
                break
            case RSCPDataType.FLOAT32:
                val = data.valueAsFloat
                break
            case RSCPDataType.DOUBLE64:
                val = data.valueAsDouble
                break
            case RSCPDataType.STRING:
                val = data.valueAsString
                break
            case RSCPDataType.CONTAINER:
                data.containerData.each {contained ->
                    vals << value(contained)
                }
                val = vals
                break
            case RSCPDataType.TIMESTAMP:
                val = [data.valueAsInstant, data.valueAsDuration]
                break
            case RSCPDataType.NONE:
            default:
                val = Optional.empty()
        }
        datamap[tag] = val
        datamap
    }
}
