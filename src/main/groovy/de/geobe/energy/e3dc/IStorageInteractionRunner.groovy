/*
 *  MIT License
 *
 *  Copyright (c) 2023. Georg Beier
 *
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

package de.geobe.energy.e3dc

import groovy.transform.ImmutableOptions
import org.joda.time.DateTime

import java.time.Instant

/**
 * Abstraction of interactions with energy storage system
 */
interface IStorageInteractionRunner {

    /**
     * Get current values for PV production, grid in, grid out,
     * house consumption, battery SoC and maybe more
     * @return PowerValues record
     */
    PowerValues getCurrentValues()

    /**
     * Get aggregated values for a number of time intervals
     * @param start starting time in local timezone
     * @param interval time resolution in seconds, must be smaller than 68 years
     * @param count number of intervals
     * @return map of historyValue records with locale DateTime objects as keys
     */
    def getHistoryValues(DateTime start, long interval, int count)

    /**
     * Set storage system operation mode, e.g. load from grid
     * @param mode operation mode (auto - 0, idle - 1, unload - 2, load - 3, load from grid - 4)
     * @param watts set load power to watts
     * @return power that was actually set
     */
    def storageLoadMode(byte mode, int watts)

    /**
     * Load site specific values, passwords etc.
     * @param filename of property file
     * @return initialized properties
     */
    def loadProperties(String filename)

}

/**
 * data structure to hold actual power data
 */
@ImmutableOptions(knownImmutableClasses = [Instant])
record PowerValues(Instant timestamp, int powerBattery, int powerGrid, int powerSolar, int consumptionHome, int socBattery) {}

/**
 * data structure to hold historic summarized power data
 */
record HistoryValues(
        float batteryPowerIn, float batteryPowerOut, float dcPowerProduction,
        float gridPowerIn, float gridPowerOut, float homeConsumption,
        float consumedProduction
) {}