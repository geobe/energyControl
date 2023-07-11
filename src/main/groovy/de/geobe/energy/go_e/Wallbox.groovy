package de.geobe.energy.go_e

import groovy.json.JsonSlurper
import groovy.transform.ImmutableOptions

class Wallbox {
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
        if(! wallbox) {
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
     * @return updated wallbox object
     */
    def getWallboxValues() {
        def response = jsonSlurper.parseText(new URL(readRequest + "$FILTER").text)
        allowedToCharge = response.alw
        requestedCurrent = response.amp
        carState = CarState.values()[response.car]
        forceState = ForceState.values()[response.frc]
        energy = response.nrg
        eTotal = energy[11]
        phaseSwitchMode = PhaseSwitchMode.values()[response.psm]
        new WallboxValues(allowedToCharge, requestedCurrent,
                carState,forceState, eTotal, phaseSwitchMode
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
        println wb.getWallboxValues()
        println wb.setChargingCurrent((short) 8)
        wb.startCharging()
        for (i in 0..<10 ) {
            i++
            Thread.sleep(1500)
            println wb.getWallboxValues()
        }
        wb.stopCharging()
        for (i in 0..<3 ) {
            i++
            Thread.sleep(1500)
            println wb.getWallboxValues()
        }
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
        return "Allowed to charge: $allowedToCharge, requested current: $requestedCurrent, car CarChargingState: $carState, " +
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
) {}
