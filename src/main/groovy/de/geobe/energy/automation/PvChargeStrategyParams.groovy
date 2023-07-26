/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2023. Georg Beier. All rights reserved.
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

package de.geobe.energy.automation

record PvChargeStrategyParams(
        int batPower = 3000,
        int batCapacity = 17500,
        int stopThreshold = -3000,
        int batStartHysteresis = 1, //ampere!
        int minChargeUseBat = 60,
        int fullChargeUseBat = 80,
        int minBatLoadPower = 200,
        int minBatUnloadPower = 200,
        int toleranceStackSize = 10
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