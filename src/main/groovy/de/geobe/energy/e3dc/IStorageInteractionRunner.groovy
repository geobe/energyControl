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

import org.joda.time.DateTime

/**
 * Abstraction of interactions with energy storage system
 */
interface IStorageInteractionRunner {

    enum TimeResolution { MINUTE, HOUR, WEEK, MONTH }

    /**
     * Get current values for PV production, grid in, grid out,
     * house consumption, battery SoC and maybe more
     * @return map of values
     */
    def getCurrentValues()

    /**
     * Get aggregated values for a number of time intervals
     * @param start starting instant
     * @param interval time resolution
     * @param count of intervals
     * @return list of maps with timestamp and values
     */
    def getHistoryValues(DateTime start, TimeResolution interval, int count)

    /**
     * Set storage system to load from grid
     * @param watts limit load power to watts
     * @return
     */
    def setLoadFromGrid(int watts)

    /**
     * Load site specific values, passwords etc.
     * @param filename
     * @return
     */
    def loadProperties(String filename)

}