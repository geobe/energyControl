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

import org.joda.time.DateTime

/**
 * Run interactions withan E§DC storage system
 */
class E3dcInteractionRunner implements IStorageInteractionRunner {
    String storageIp = ''
    int storagePort
    String storagePassword = ''
    String e3dcPortalUser = ''
    String e3dcPortalPassword = ''
    E3dcInteractions interactions

    static void main(String[] args) {
        def runner = new E3dcInteractionRunner()
        runner.interactions.sendAuthentication()
        runner.interactions.requestBatteryData()
        runner.interactions.closeConnection()
    }

    E3dcInteractionRunner(String filename = '/E3DC.properties') {
        def props = loadProperties(filename)
        storageIp = props.storageIp
        storagePort = Integer.decode(props.storagePort.toString())
        storagePassword = props.storagePassword
        e3dcPortalUser = props.e3dcPortalUser
        e3dcPortalPassword = props.e3dcPortalPassword
        interactions = new E3dcInteractions(storageIp, storagePort, storagePassword, e3dcPortalUser, e3dcPortalPassword)
    }

    @Override
    def getCurrentValues() {
        return null
    }

    @Override
    def getHistoryValues(DateTime start, TimeResolution interval, int count) {
        return null
    }

    @Override
    def setLoadFromGrid(int watts) {
        return null
    }

    @Override
    def loadProperties(String filename) {
        Properties props = new Properties()
        def r = this.getClass().getResource(filename)
        r.withInputStream {
            props.load(it)
        }
        props
    }
}
