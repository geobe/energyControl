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

package de.geobe.energy.tibber

import org.joda.time.DateTime

/**
 * Abstraction of query methods against different power supplier apis
 * to get current and historical price data(like tibber or aWATTar).
 */
interface IPowerQueryRunner {
    /**
     * get prices for today and tomorrow
     * @return map of lists of date / price pairs
     */
    def runPriceQuery()

    /**
     * List of hourly prices for a time interval, e.g. a month
     * @param startingAt start date
     * @param hours # of hours
     * @return list of date / price pairs
     */
    def runIntervalQuery(DateTime startingAt, int hours)

    /**
     * relevant currency
     * @return currency name string
     */
    def runCurrencyQuery()

    /**
     * get properties file from classpath
     * @param filename
     * @return initialized properties
     */
    def loadProperties(String filename)

}