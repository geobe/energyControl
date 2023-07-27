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

package de.geobe.energy.tibber

import org.joda.time.DateTime

/**
 * Responsibility: Run the tibber queries for a given tibber profile
 */
class TibberQueryRunner implements IPowerQueryRunner {

    TibberAccess tibberAccess
    TibberQueries tibberQueries
    def home = ''
    def access = ''
    def uri = ''

    /**
     * sample main program
     */
    static void main(String[] args) {
        TibberQueryRunner homeRunner = new TibberQueryRunner()
        TibberQueryRunner testRunner = new TibberQueryRunner('/tibberSample.properties')
        def result = testRunner.runIntervalQuery(new DateTime(2023, 3, 10, 0, 0), 10)
        println result
        result = homeRunner.runPriceQuery()
        println result.today
        println result.tomorrow
        println homeRunner.runCurrencyQuery()
        println testRunner.runCurrencyQuery()
    }

    /**
     * Initialize TibberQueryRunner for a tibber account
     * @param propertyPath path to property file
     */
    TibberQueryRunner(String propertyPath = '/tibberHome.properties') {
        def properties = loadProperties(propertyPath)
        access = properties.accesstoken
        uri = properties.tibberuri
        home = properties.homeid
        tibberAccess = new TibberAccess(uri, access)
        tibberQueries = new TibberQueries()
    }

    /**
     * get tibber prices for today and tomorrow for this account
     * @return map of lists of date / price pairs
     */
    def runPriceQuery() {
        def query = tibberQueries.priceQuery(home)
        def jsonResult = tibberAccess.jsonFromTibber(query)
        tibberQueries.scanPrice(jsonResult)
    }

    /**
     * List of hourly prices for a time interval, e.g. a month for this account
     * @param startingAt start date
     * @param hours # of hours
     * @return list of date / price pairs
     */
    def runIntervalQuery(DateTime startingAt, int hours) {
        def query = tibberQueries.intervalQuery(home, startingAt, hours)
        def jsonResult = tibberAccess.jsonFromTibber(query)
        tibberQueries.scanInterval(jsonResult)
    }

    /**
     * relevant currency for this tibber account
     * @return
     */
    def runCurrencyQuery() {
        def query = tibberQueries.currencyQuery(home)
        def jsonResult = tibberAccess.jsonFromTibber(query)
        tibberQueries.scanCurrency(jsonResult)
    }

    /**
     * get properties file from classpath
     * @param filename
     * @return initialized properties
     */
    def loadProperties(String filename = '/tibberSample.properties') {
        Properties props = new Properties()
        def r = this.getClass().getResource(filename)
        r.withInputStream {
            props.load(it)
        }
        props
    }

}
