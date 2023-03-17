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
 * Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package de.geobe.energy.tibber

import org.joda.time.DateTime

class QueryRunner {

    TibberAccess tibberAccess
    TibberQueries tibberQueries
    def home = ''
    def access = ''
    def uri = ''

    static void main(String[] args) {
        QueryRunner homeRunner = new QueryRunner()
        QueryRunner testRunner = new QueryRunner('/default.properties')
        def result = testRunner.runIntervalQuery(new DateTime(2023, 3, 10, 0, 0), 10)
        println result
        result = homeRunner.runPriceQuery()
        println result.today
        println result.tomorrow
        println homeRunner.runCurrencyQuery()
        println testRunner.runCurrencyQuery()
    }

    QueryRunner(String propertyPath = '/home.properties') {
        def properties = loadProperties(propertyPath)
        access = properties.accesstoken
        uri = properties.tibberuri
        home = properties.homeid
        tibberAccess = new TibberAccess(uri, access)
        tibberQueries = new TibberQueries()
    }


    def runPriceQuery() {
        def query = tibberQueries.priceQuery(home)
        def jsonResult = tibberAccess.jsonFromTibber(query)
        tibberQueries.scanPrice(jsonResult)
    }

    def runIntervalQuery(DateTime startingAt, int hours) {
        def query = tibberQueries.intervalQuery(home, startingAt, hours)
        def jsonResult = tibberAccess.jsonFromTibber(query)
        tibberQueries.scanInterval(jsonResult)
    }

    def runCurrencyQuery() {
        def query = tibberQueries.currencyQuery(home)
        def jsonResult = tibberAccess.jsonFromTibber(query)
        tibberQueries.scanCurrency(jsonResult)
    }

    def loadProperties(String filename = '/default.properties') {
        Properties props = new Properties()
        def r = this.getClass().getResource(filename)
        r.withInputStream {
            props.load(it)
        }
        props
    }

}
