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
