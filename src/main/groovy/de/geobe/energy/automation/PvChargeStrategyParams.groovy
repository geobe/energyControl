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

package de.geobe.energy.automation

record PvChargeStrategyParams(
        int batPower = 3000,            // Watt
        int batCapacity = 17500,        // Wh
        int stopThreshold = -3000,      // Watt
        int batStartHysteresis = 300,   // Watt
        int minChargeUseBat = 60,       // Percent
        int fullChargeUseBat = 80,      // Percent
        int minBatLoadPower = 200,      // Watt
        int minBatUnloadPower = 200,    // Watt
        int toleranceStackSize = 10     // # of values on valueStack for averages
) {
    PvChargeStrategyParams(PvChargeStrategyParams o) {
        batPower = o.batPower
        batCapacity = o.batCapacity
        stopThreshold = o.stopThreshold
        batStartHysteresis = o.batStartHysteresis
        minChargeUseBat = o.minChargeUseBat
        fullChargeUseBat = o.fullChargeUseBat
        minBatLoadPower = o.minBatLoadPower
        minBatUnloadPower = o.minBatUnloadPower
        toleranceStackSize = o.toleranceStackSize
    }
}