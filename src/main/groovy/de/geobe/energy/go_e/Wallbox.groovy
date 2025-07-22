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

package de.geobe.energy.go_e

import de.geobe.energy.automation.WallboxMonitor
import groovy.json.JsonSlurper
import groovy.transform.AutoClone
import groovy.transform.ImmutableOptions
import groovy.transform.Sortable

class Wallbox implements IWallboxValueSource {
    enum ForceState {
        NEUTRAL,
        OFF,
        ON
    }

    enum CarState {
        UNKNOWN_ERROR,
        IDLE,
        CHARGING,
        WAIT_CAR,
        COMPLETE,
        ERROR
    }

    enum PhaseSwitchMode {
        AUTO,
        FORCE_1,
        FORCE_3
    }

    final static JsonSlurper jsonSlurper = new JsonSlurper()

    final String wallboxIp
    final short maxCurrent
    final short minCurrent
    final short maxStartCurrent
    boolean allowedToCharge         // alw - ro
    short requestedCurrent          // amp - rw
    CarState carState               // car - ro
//    Optional<CarChargingState> carState     // car - ro
    ForceState forceState           // frc - rw
    def energy                      // nrg - ro
    short eTotal                    // nrg[11] - ro
    PhaseSwitchMode phaseSwitchMode // psm - rw

    private static Wallbox wallbox

    static synchronized getWallbox() {
        if (!wallbox) {
            wallbox = new Wallbox()
        }
        wallbox
    }

    // make property readonly
    boolean getAllowedToCharge() { allowedToCharge }

    // make property readonly
    def getEnergy() { energy }

    // make property readonly
    Optional getCarState() { carState }

    static final String FILTER = 'alw,amp,car,frc,nrg,psm'
    final String readRequest
    final String setRequest

    /**
     * initializing constructor reading config values from properties file
     * @param filename
     */
    Wallbox(String filename = '/wallbox.properties') {
        def props = loadProperties(filename)
        wallboxIp = props.wallboxIp
        maxCurrent = Short.parseShort(props.maxCurrent)
        minCurrent = Short.parseShort(props.minCurrent)
        maxStartCurrent = Short.parseShort(props.maxStartCurrent)
        readRequest = "http://$wallboxIp/api/status?filter="
        setRequest = "http://$wallboxIp/api/set?"
    }

    /**
     * update wallbox property values from physical wallbox
     * @return updated wallboxValues object
     */
    WallboxValues getValues() {
        def url = new URL(readRequest + "$FILTER")
        def getParams = [
                connectTimeout: 30000,
                readTimeout   : 30000]
        def text = url.getText(getParams)
        def response = jsonSlurper.parseText(text)
        allowedToCharge = response.alw
        requestedCurrent = response.amp
        carState = CarState.values()[response.car]
        forceState = ForceState.values()[response.frc]
        energy = response.nrg
        eTotal = energy[11]
        phaseSwitchMode = PhaseSwitchMode.values()[response.psm]
        new WallboxValues(allowedToCharge, requestedCurrent,
                carState, forceState, eTotal, phaseSwitchMode
        )
    }

    /**
     * sends request to wallbox api to start charging by using URL(...).setText() method
     * @return human readable response
     */
    def startCharging() {
        // seems that NEUTRAL delegates charging state to the car (which makes sense)
        def uri = setRequest + "frc=${ForceState.NEUTRAL.ordinal()}"
        new URL(uri).text
    }

    /**
     * sends request to wallbox api to start charging, overriding car state
     * @return human readable response
     */
    def forceStartCharging() {
        // seems that ON overrides charging state of car (master is control program)
        def uri = setRequest + "frc=${ForceState.ON.ordinal()}"
        new URL(uri).text
    }

    /**
     * sends request to wallbox api to stop charging
     * @return human readable response
     */
    def stopCharging() {
        def uri = setRequest + "frc=${ForceState.OFF.ordinal()}"
        new URL(uri).text
    }

    /**
     * sends request to wallbox api to set charging current
     * @param current charging current in A, is limited between min and max values from properties
     * @return human readable response
     */
    def setChargingCurrent(short current) {
//        if (current == 0) {
//            // don't change
//        } else
        if (current < minCurrent) {
            current = minCurrent
        } else if (current > maxCurrent) {
            current = maxCurrent
        }
        def uri = setRequest + "amp=$current"
        new URL(uri).text
    }

    // is obviously determined by cabling, not settable by software
    def setPhaseSwitchMode(PhaseSwitchMode mode) {
        def uri = setRequest + "frc=${mode.ordinal()}"
        new URL(uri).text
    }

    static void main(String[] args) {
        Wallbox wb = Wallbox.wallbox
        println "before start ${wb.getValues()}"
        println "set current: ${ wb.setChargingCurrent((short) 6) }"
        println "start charging: ${wb.startCharging()}"
        long run
        def start = System.currentTimeMillis()
        def previousValues = wb.values
        println "@00.0 s: $previousValues"
        while ((run = System.currentTimeMillis() - start) < 40000) {
            def values = wb.values
            if(values.differs(previousValues)) {
                previousValues = values
                def s = run.intdiv(1000)
                def ms = run - s
                println "@$s.$ms s: $values"
            }
            Thread.sleep(100)
        }
        println "proceed @${(System.currentTimeMillis() - start).intdiv(1000)} s"
        while ((run = System.currentTimeMillis() - start) < 120000) {
            def values = wb.values
            if(values.differs(previousValues)) {
                previousValues = values
                def s = run.intdiv(1000)
                def ms = run - s
                println "@$s.$ms s: $values"
            }
            Thread.sleep(100)
        }
        previousValues = wb.values
        println "stop: ${wb.stopCharging()}"
        while ((run = System.currentTimeMillis() - start) < 130000) {
            def values = wb.values
            if(values.differs(previousValues)) {
                previousValues = values
                def s = run.intdiv(1000)
                def ms = run - s
                println "@$s.$ms s: $values"
            }
            Thread.sleep(100)
        }
        println "end @${(System.currentTimeMillis() - start).intdiv(1000)} s"
    }

    /**
     * load prperties object from file
     * @param filename path to file
     * @return initialized properties object
     */
    Properties loadProperties(String filename) {
        Properties props = new Properties()
        def r = this.getClass().getResource(filename)
        r.withInputStream {
            props.load(it)
        }
        props
    }

    @Override
    String toString() {
        return "Allowed to continueCharging: $allowedToCharge, requested current: $requestedCurrent, car CarChargingState: $carState, " +
                "force state: $forceState, phase switch mode: $phaseSwitchMode\n energy: $energy"
    }
}

@AutoClone
@Sortable(excludes = ['phaseSwitchMode'])
@ImmutableOptions(knownImmutables = "carState")
record WallboxValues(
        boolean allowedToCharge,
        short requestedCurrent,
        Wallbox.CarState carState,
        Wallbox.ForceState forceState,
        short energy,
        Wallbox.PhaseSwitchMode phaseSwitchMode
) {
    @Override
    String toString() {
        "Wallbox -> mayCharge: $allowedToCharge, req: $requestedCurrent, car: $carState, force: $forceState, energy: $energy"
    }

    boolean differs(WallboxValues o, short deltaE = 200, short limit = WallboxMonitor.E_LIMIT) {
        def realDeltaE = Math.abs(energy - o.energy) >= deltaE && o.energy < limit
        realDeltaE || allowedToCharge != o.allowedToCharge || requestedCurrent != o.requestedCurrent ||
                carState != o.carState || forceState != o.forceState
    }
}

interface IWallboxValueSource {
    WallboxValues getValues()
}