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
 * Responsibility: Provide tibber queries, know resulting record structure
 * and decode returned json into handy data types
 */
class TibberQueries {

    def slurper = new groovy.json.JsonSlurper()

    /**
     * Encode joda DateTime to Base64 String
     * @param dt DateTime value
     * @return Base64 String
     */
    def encodeDateTimeBase64(DateTime dt) {
        dt.toString().bytes.encodeBase64().toString()
    }

    /**
     * If HomeId is known, query directly for home, not for homes list
     * @param id HomeId, default null
     * @return a snippet for GraphQL query
     */
    def homeline(id = '') {
        id ? "home(id: \"$id\")".toString() : 'homes'
    }

    /**
     * Extract price info for today or tomorrow from priceQuery result
     *
     * @param result GraphQL result as parsed by JsonSlurper
     * @return price info subtree of result
     */
    def priceInfoNow(def result) {
        result.data?.viewer.home ?
                result.data.viewer.home.currentSubscription.priceInfo :
                result.data.viewer.homes[0].currentSubscription.priceInfo
    }

    /**
     * extract price info for other date from intervalQuery result
     * @param result GraphQL result as parsed by JsonSlurper
     * @return price info subtree of result
     */
    def priceInfoAt(def result) {
        result.data?.viewer.home ?
                result.data.viewer.home.currentSubscription.priceInfoRange :
                result.data.viewer.homes[0].currentSubscription.priceInfoRange
    }

    /**
     * Convert list of priceinfo result values to handy data types
     * @param list raw types from JsonSlurper (String, BigDecimal)
     * @return list of converted types (DateTime, Float)
     */
    def extractPriceAt(List list) {
        def priceList = []
        list?.each {
            def t = new PriceAt(DateTime.parse(it.startsAt), (Float) it.total)
            priceList.add(t)
        }
        priceList
    }

    /**
     * Decode tibber price info query result for today and tomorrow
     * @param jsonResult query result as json string
     * @return map of hourly prices for today and tomorrow
     */
    def scanPrice(String jsonResult) {
        def result = slurper.parseText(jsonResult)
        def today = extractPriceAt(priceInfoNow(result).today)
        def tomorrow = extractPriceAt(priceInfoNow(result).tomorrow)
        [today: today, tomorrow: tomorrow]
    }

    /**
     * Decode tibber price info query result for a time interval
     * @param jsonResult query result as json string
     * @return list of hourly prices for a time interval
     */
    def scanInterval(String jsonResult) {
        def result = slurper.parseText(jsonResult)
        extractPriceAt(priceInfoAt(result)?.nodes)
    }

    /**
     * Extract currency information
     * @param jsonResult query result as json string
     * @return currency name
     */
    def scanCurrency(String jsonResult) {
        def result = slurper.parseText(jsonResult)
        priceInfoAt(result).current.currency
    }

    /**
     * Provide GraphQL query string for tibber prices today and tomorrow
     * @param id homeId, if known
     * @return json query string
     */
    def priceQueryOld(id = '') {
        """
{
  viewer {
    ${homeline(id)} {
      currentSubscription {
        priceInfo {
          today {
            startsAt
            total
          }
          tomorrow {
            startsAt
            total
          }
        }
      }
    }
  }
}
"""
    }

    /**
     * Provide GraphQL query string for tibber prices today and tomorrow
     * @param id homeId, if known
     * @param resolution 'HOURLY' or 'QUARTER_HOURLY'
     * @return json query string
     */
    def priceQuery(id = '', resolution = 'HOURLY') {
        """
{
  viewer {
    ${homeline(id)} {
      currentSubscription {
        priceInfo(resolution: ${resolution}) {
          today {
            startsAt
            total
          }
          tomorrow {
            startsAt
            total
          }
        }
      }
    }
  }
}
"""
    }

    /**
     * Provide GraphQL query string for tibber hourly prices for an interval of hours
     * @param id homeId, if known
     * @param startingAt DateTime interval starts
     * @param hours interval lengh in hours
     * @return json query string
     */
    def intervalQuery(String id, DateTime startingAt, int hours, boolean quarters = false) {
        def start64 = encodeDateTimeBase64(startingAt.minusSeconds(1))
        if (quarters) {
            hours = hours * 4
        }
        """
{
  viewer {
    ${homeline(id)} {
      currentSubscription {
        priceInfoRange(
            resolution: ${quarters ? 'QUARTER_HOURLY' : 'HOURLY'}
            first: $hours
            after: "$start64"
        ) {
          nodes {
            startsAt
            total
          }
        }
      }
    }
  }
}
"""
    }

    /**
     * Provide GraphQL query string for currency of tibber prices
     * @param id homeId, if known
     * @return json query string
     */
    def currencyQuery(id = '') {
        """
{
  viewer {
    ${homeline(id)} {
      currentSubscription {
        priceInfo {
          current {
            currency
          }
        }
      }
    }
  }
}
"""
    }
}

