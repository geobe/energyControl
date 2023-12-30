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

import groovy.transform.ImmutableOptions

//@ImmutableOptions(knownImmutableClasses=[IntRange])
record PowerBufferingParams(
        int startMorningUnload = 6,         // h of day
        int startNightCharging = 22,        // h of previous day
        int endNightCharging = 7,           // h of day
        int calcNightLoad = 22,             // h of day
        int calcDayLoad = 10,               // h of day
        float hoursToFullyCharge = 6.0f,    // hours
        int initialSupplyHours= 8,          // hours
        IntRange morning = 6..9,            // hour range
        IntRange midday = 10..16,           // hour range
        IntRange evening = 17..23,          // hour range
        int minimalSavingThreshold = 20,    // ct
        float minimalPriceDifference = 0.02,// EUR
        float loadCycleLoss = 1.05,         // factor 1 + n%
        int baseConsumption = 400,          // W
        int heatpumpConsumption = 3800,     // W
        int maxBatUnload = 3000             // W redundant to PowerStrategyParams
) {

}
