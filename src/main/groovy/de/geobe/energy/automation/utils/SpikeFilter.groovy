/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2025. Georg Beier. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
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

package de.geobe.energy.automation.utils

import de.geobe.energy.automation.PMValues
import de.geobe.energy.automation.PowerStrategyParams

/**
 * Experimental!<br>
 * class responsibility: filter spikes from incoming realtime values:
 * powerValues and wallboxValues are read from different hardware sources in this sequence.
 * consumptionHome can show artificial spikes if wallboxValues.energy changes significantly
 * between two readings. In this case, consumptionHome is no usable but probably a wrong or
 * outdated value from e3dc power storage. So use previous value, if available
 */
class SpikeFilter {

    private PowerStrategyParams params = new PowerStrategyParams()

    /** charging power in W per 1 A */
    static powerFactor = 3 * 230

    /** a list of recent realtime values */
    private List<PMValues> valueTrace = []

    def getValueTrace() {
        valueTrace
    }

    PMValues filterSpikes(PMValues pmValues) {
        if(! valueTrace) {
            valueTrace.push(pmValues)
        } else {
            short consumptionHome = pmValues.powerValues.consumptionHome
            short energy = pmValues.wallboxValues.energy
            def energySpike = Math.abs(energy - valueTrace.first().wallboxValues.energy) > (int)(1.5 * powerFactor)
//            println "house: $consumptionHome, car energy now: $energy, prev: ${valueTrace.first().wallboxValues.energy} -> spike? $energySpike"
//            if (consumptionHome < 0 || consumptionHome - energy < 0 || energySpike) {
//                def lastHome = valueTrace.first().powerValues.consumptionHome
//                def lastEnergy = valueTrace.first().wallboxValues.energy
//                consumptionHome = lastHome - lastEnergy
//                pmValues.powerValues = pmValues.powerValues.setConsumptionHome(consumptionHome)
//            }
            valueTrace.push(pmValues)
            if (valueTrace.size() > params.toleranceStackSize) {
                valueTrace.removeLast()
            }
        }
        pmValues
    }
}
