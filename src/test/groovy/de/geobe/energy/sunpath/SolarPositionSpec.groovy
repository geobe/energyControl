/*
 * MIT License
 *
 * Copyright (c) 2021  Georg Beier
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
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

package de.geobe.energy.sunpath

import org.joda.time.DateTime
import spock.lang.Specification

class SolarPositionSpec extends Specification {

    SolarPosition solarPosition = new SolarPosition()

    static final wikiPositionValues = [
            azimuth         : -94.062d,
            elevation       : 19.062d,
            jD0             : 2453953.5d,
            t0              : 0.06594113621d,
            thetaGh         : 2.9759d,
            theta           : 56.239d,
            delta           : 16.726d,
            alpha           : 136.119d,
            jD              : 2453953.75d,
            n               : 2408.75d,
            g               : 211.593d,
            eclipticalL     : 134.638d,
            epsilon         : 23.438d,
            lambda          : 133.653d,
            elevRefracted   : 19.110d,
    ]


    def 'local solar time looks reasonable compared to pveducation.org'() {
        given: 'a DateTime at noon Feb 20 in local timezone anda configuration'
        def localNoon = new DateTime(2023, 6, 15, 12, 0)
        def config = SolarPosition.readConfig('sunpathConfig.json')
        def lon = config.location.lon
        def timeOff = (int) (4 * (15.0 - lon))
        when: 'we ask for solar time'
        def solarTime = solarPosition.localSolarTime(localNoon, lon)
        then:
        println "timeOff $timeOff min -> $solarTime"
    }

    /**
     * test solar position for Munich at 06.08.2006 8:00 CEST (= 6:00 UT), lat = 48.1°, lon = 11.6 E
     * see <a href="https://de.wikipedia.org/wiki/Sonnenstand#Beispiel">Solar Position example Munich</a>
     * (in German)
     */
    def 'calculated solar position matches example from wikipedia'() {
        given: 'test data for munich'
        def m = [
                y  : 2006, m: 8, d: 6, h: 6, min: 0,
                lat: 48.1, lon: 11.6
        ]
        when: 'we calculate positon fur munich'
        def position = solarPosition
                .solarCoordinates(m.y, m.m, m.d, m.h, m.min, m.lat, m.lon)
        then: 'we compare values with Wikipedia for at least o.00002 relative deviation'
        wikiPositionValues.every {wikiVal ->
            def key = wikiVal.key
            def delta = Math.abs(position[key] - wikiVal.value)
//            println "$key relative deviation: ${delta ? (delta / wikiVal.value) : 0.0}"
            delta ? (delta / wikiVal.value) < 0.00002 : true
        }
    }
}
