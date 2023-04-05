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

import javax.xml.bind.DatatypeConverter
import java.nio.ByteBuffer

/**
 * Class with utility methods that reformat RSCPData into more groovy (useful) structures
 */
class RscpUtils {

    static values(List<RSCPData> dataList) {
        def values = [:]
        dataList.each { data ->
            values << value(data)
        }
    }

    static values(RSCPFrame frame) {
        def values = []
        frame.data.each { data ->
            values << value(data)
        }
        values.size() == 1 ? values[0] : values
    }

    static value(RSCPData data) {
        def tag = data.dataTag.toString().replace('TAG_', '')
        def datamap = [:]
        def type = data.dataType
        Optional val
        def vals = [:]
        switch (type) {
            case RSCPDataType.BOOL:
            case RSCPDataType.BITFIELD:
            case RSCPDataType.BYTEARRAY:
            case RSCPDataType.ERROR:
                val = Optional.of(data.valueAsByteArray)
                break
            case RSCPDataType.CHAR8:
            case RSCPDataType.UCHAR8:
                val = Optional.of(data.valueAsByteArray[0])
                break
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
                data.containerData.each { contained ->
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
        datamap[tag] = val.orElse(null)
        datamap
    }

    /**
     * Debug helper method, deconstructs frame binary in fields as defined in e3dc documentation,
     * coded in hex
     * @param frame
     * @return map with all elements of frame, including commands nested in container
     */
    static splitFrame(byte[] rawFrame) {
        int sizeData = rawFrame.size() - RSCPFrame.offsetData
        def magic = Arrays.copyOfRange(rawFrame, RSCPFrame.offsetMagic, RSCPFrame.offsetMagic + RSCPFrame.sizeMagic)
        def ctrl = rawFrame[(RSCPFrame.offsetCtrl + RSCPFrame.sizeCtrl - 1)..RSCPFrame.offsetCtrl].toArray(new Byte[0])
        def sec = rawFrame[(RSCPFrame.offsetTsSeconds + RSCPFrame.sizeTsSeconds - 1)..RSCPFrame.offsetTsSeconds].toArray(new Byte[0])
        def nano = rawFrame[(RSCPFrame.offsetTsNanoSeconds + RSCPFrame.sizeTsNanoSeconds - 1)..RSCPFrame.offsetTsNanoSeconds].toArray(new Byte[0])
        def len = rawFrame[(RSCPFrame.offsetLength + RSCPFrame.sizeLength - 1)..(RSCPFrame.offsetLength)].toArray(new Byte[0])
        def data = Arrays.copyOfRange(rawFrame, RSCPFrame.offsetData, RSCPFrame.offsetData + sizeData - RSCPFrame.sizeCRC)
        [
                magic: DatatypeConverter.printHexBinary(magic),
                ctrl : DatatypeConverter.printHexBinary(ctrl),
                sec  : DatatypeConverter.printHexBinary(sec),
                nano : DatatypeConverter.printHexBinary(nano),
                len  : DatatypeConverter.printHexBinary(len),
                data : splitData(data)
        ]
    }

    /**
     * Debug helper method, deconstructing a frame data section into contained RSCP tags
     * @param data
     * @return map of one or more RSCP tags contained in data
     */
    static splitData(byte[] data) {
        def ltag = 4
        def ltype = 1 + ltag
        def lsize = 2 + ltype
        def tag = data[ltag - 1..0].toArray(new Byte[0])
        def type = data[ltype - 1..ltag].toArray(new Byte[0])
        def size = data[lsize - 1..ltype].toArray(new Byte[0])
        def valueSize = (type == [0x0E]) ? (short) 0 : ByteBuffer.wrap(size).getShort()
        def last = lsize + valueSize
        def value = valueSize ? data[last - 1 .. lsize].toArray(new Byte[0]) : [].toArray(Byte[])
        def nested = Arrays.copyOfRange(data, lsize + valueSize, data.size())
        def hex = [
                tag  : DatatypeConverter.printHexBinary(tag),
                type : DatatypeConverter.printHexBinary(type),
                size : DatatypeConverter.printHexBinary(size),
                value: valueSize ? DatatypeConverter.printHexBinary(value) : ''
        ]
        if(type == [0x0E]) {
            hex << [nested: splitData(nested)]
        } else if(nested) {
            if(hex.next) {
                (hex.next << splitData(nested))
            } else {
                hex.next = [splitData(nested)]
            }
        }
        hex
    }
}
