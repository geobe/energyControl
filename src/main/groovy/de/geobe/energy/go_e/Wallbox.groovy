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

import groovy.json.JsonSlurper
import groovy.transform.ImmutableOptions
import groovy.transform.ToString

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
                connectTimeout: 5000,
                readTimeout   : 6000]
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
     * sends request to wallbox api to start loading
     * @return human readable response
     */
    def startCharging() {
        // seems that NEUTRAL delegates loading state to the car (which makes sense)
        def uri = setRequest + "frc=${ForceState.NEUTRAL.ordinal()}"
        new URL(uri).text
    }

    /**
     * sends request to wallbox api to start loading, overriding car state
     * @return human readable response
     */
    def startChargingRemote() {
        // seems that ON overrides loading state of car (master is control program)
        def uri = setRequest + "frc=${ForceState.ON.ordinal()}"
        new URL(uri).text
    }

    /**
     * sends request to wallbox api to stop loading
     * @return human readable response
     */
    def stopCharging() {
        def uri = setRequest + "frc=${ForceState.OFF.ordinal()}"
        new URL(uri).text
    }

    /**
     * sends request to wallbox api to set loading current
     * @param current loading current in A, is limited between min and max values from properties
     * @return human readable response
     */
    def setChargingCurrent(short current) {
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
        println "start charging: ${wb.startChargingRemote()}"
        def start = System.currentTimeMillis()
        for (i in 0..<20) {
            println "@${(start - System.currentTimeMillis()).intdiv(1000)} s: ${wb.getValues()}"
            i++
            Thread.sleep(1000)
        }
        println "proceed @${(start - System.currentTimeMillis()).intdiv(1000)} s: ${wb.getValues()}"
        for (i in 0..<2) {
            i++
            Thread.sleep(60000)
            println "@${(start - System.currentTimeMillis()).intdiv(1000)} s: ${wb.getValues()}"
        }
        println "stop: ${wb.stopCharging()}"
        for (i in 0..<5) {
            println "@${(start - System.currentTimeMillis()).intdiv(1000)} s: ${wb.getValues()}"
            i++
            Thread.sleep(1000)
        }
        println "end @${(start - System.currentTimeMillis()).intdiv(1000)} s: ${wb.getValues()}"
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
        "Wallbox -> mayCharge: $allowedToCharge, req: $requestedCurrent, car: $carState, force: $forceState, energy: $energy, phases: $phaseSwitchMode"
    }
}

interface IWallboxValueSource {
    WallboxValues getValues()
}