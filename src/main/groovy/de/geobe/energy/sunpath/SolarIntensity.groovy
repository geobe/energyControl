/*
 * MIT License
 *
 * Copyright (c) 2023  Georg Beier
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

package de.geobe.energy.sunpath

import static java.lang.Math.*

class SolarIntensity {
    /** Earth radius in km */
    static final rEarth = 6371.0
    /** effective height of atmosphere for absorption calculation in km */
    static final ehAtmosphere = 9.0
    /** solar intensity external to the Earth's atmosphere in W/m² */
    static final solarConstant = 1353.0
    /** characterize level of air polution */
    enum PollutionLevel {
        Average, CleanAir, Polluted
    }
    /** Interpolated from data in the Earthscan reference using suitable Least squares estimate variants
     *  of equation parameters, also from cited Wikipedia article */
    static final intensityParameters = [
            Average : [0.7, 0.678],
            CleanAir: [0.76, 0.618],
            Polluted: [0.56, 0.715]
    ]

    /**
     * air mass calculation approximated according to Kasten and Young<br>
     * @see <a href="https://en.wikipedia.org/wiki/Air_mass_(solar_energy)#Calculation">
     *     Wikipedia on Air mass (solar energy)</a>
     * @param zeta zenith angle of sun position relative to earth surface, i.e. 90° - elevation
     * @return effective air mass that a sun ray passes at th
     */
    def airMass(double zeta) {
        def am = 1 / (cos(toRadians(zeta)) + 0.50572 * pow(96.07995 - zeta, -1.6364))
        am
    }

    /**
     * calculate relative fair weather intensity approximation<br>
     * see <a href="https://en.wikipedia.org/wiki/Air_mass_(solar_energy)#Solar_intensity">
     *     Wikipedia on Air mass (solar energy)</a>
     * @param zeta zenith angle of sun position relative to earth surface, i.e. 90° - elevation
     * @param altitude of location above sea level in km
     * @param pollutionLevel selects between
     * @return
     */
    def relativeIntensity(double zeta, double altitude = 0.0, PollutionLevel pollutionLevel = PollutionLevel.Average) {
        def am = airMass(zeta)
        def params = intensityParameters[(pollutionLevel.toString())]
        def amToPow = pow(am, params[1])
        def factorToPow = pow(params[0], amToPow)
        def ity = 1.1 * ((1 - altitude / 7.1) * factorToPow + altitude / 7.1)
        ity
    }

    def intensity(double zeta, double altitude = 0.0, PollutionLevel pollutionLevel = PollutionLevel.Average) {
        solarConstant * relativeIntensity(zeta, altitude, pollutionLevel)
    }


    def powerGain(double powerFactor, double zeta, double altitude = 0.0, PollutionLevel pollutionLevel = PollutionLevel.Average) {
        powerFactor * intensity(zeta, altitude, pollutionLevel)
    }
}
