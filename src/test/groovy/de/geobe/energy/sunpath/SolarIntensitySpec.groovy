/*
 * MIT License
 *
 * Copyright (c) 2021  Georg Beier
 * Permission is hereby granted, free of continueCharging, to any person obtaining a copy
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

import spock.lang.Specification

class SolarIntensitySpec extends Specification {

    def expectedOutcome = [
            [zeta: 0.0, am: 1.0, av: 1040.0, min: 840.0, max: 1130.0],
            [zeta: 23.0, am: 1.09, av: 1020.0, min: 800.0, max: 1110.0],
            [zeta: 30.0, am: 1.15, av: 1010.0, min: 780.0, max: 1100.0],
            [zeta: 45.0, am: 1.41, av: 950.0, min: 710.0, max: 1060.0],
            [zeta: 48.2, am: 1.5, av: 930.0, min: 680.0, max: 1050.0],
            [zeta: 60.0, am: 2.0, av: 840.0, min: 560.0, max: 970.0],
            [zeta: 70.0, am: 2.9, av: 710.0, min: 430.0, max: 880.0],
            [zeta: 75.0, am: 3.8, av: 620.0, min: 330.0, max: 800.0],
            [zeta: 80.0, am: 5.6, av: 470.0, min: 200.0, max: 660.0],
//            [zeta: 85.0, am: 10.0, av: 270.0, min: 85.0, max: 480.0],
    ]

    def 'atmospheric mass calculation fairly reproduces values from Wikipedia'() {
        given: 'an instance to test'
        def solarIntensity = new SolarIntensity()
        when: 'am values are calculated'
        def calc = expectedOutcome.collect { solarIntensity.airMass(it.zeta) }
//        calc.eachWithIndex { def v, i ->
//            double expct = expectedOutcome[i].am
//            printf('% 5.2f, % 5.2f%%;  ', v, 100.0 * (v - expct) / expct)
//        }
//        println ''
        then: 'we can compare values'
        calc.eachWithIndex { double v, int i ->
            def expct = expectedOutcome[i].am
            assert Math.abs(v - expct) / expct < 0.033
        }


    }

    def 'intensity calculation fairly reproduces values from Wikipedia'() {
        given: 'an instance to test'
        def solarIntensity = new SolarIntensity()
        when: 'am values are calculated'
        def calc = expectedOutcome.collect {
            [
                    solarIntensity.intensity(it.zeta, 0.0, SolarIntensity.PollutionLevel.Average),
                    solarIntensity.intensity(it.zeta, 0.0, SolarIntensity.PollutionLevel.Polluted),
                    solarIntensity.intensity(it.zeta, 0.0, SolarIntensity.PollutionLevel.CleanAir),
            ]
        }
        calc.eachWithIndex{ ArrayList<Double> v, int i ->
            def expct = expectedOutcome[i]
            printf('avg: % 4.0f <-> % 4.0f, min: % 4.0f <-> % 4.0f, max: % 4.0f <-> % 4.0f\n',
            v[0], expct.av, v[1], expct['min'], v[2], expct['max'])
        }
        then: 'compare with expected'
        calc.eachWithIndex{ ArrayList<Double> v, int i ->
            def expct = expectedOutcome[i]
            assert Math.abs(v[0] - expct.av) < 10
            assert Math.abs(v[1] - expct['min']) < 20
            assert Math.abs(v[2] - expct['max']) < 20
        }
    }
}

