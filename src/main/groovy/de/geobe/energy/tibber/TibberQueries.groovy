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

import groovy.transform.Immutable
import groovy.transform.ImmutableOptions
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
        id ?  "home(id: \"$id\")".toString() : 'homes'
    }

    /**
     *
     * @param result GraphQL result as parsed by JsonSlurper
     * @return price info subtree of result
     */
    def priceInfo(def result) {
        result.data.viewer.home ?
                result.data.viewer.home.currentSubscription.priceInfo :
                result.data.viewer.homes[0].currentSubscription.priceInfo
    }

    /**
     * Convert list of priceinfo result values to handy data types
     * @param list raw types from JsonSlurper (String, BigDecimal)
     * @return list of converted types (DateTime, Float)
     */
    def extractPriceAt(List list) {
        def priceList = []
        list.each {
            def t = new PriceAt(DateTime.parse(it.startsAt), (Float)it.total)
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
        def today = extractPriceAt(priceInfo(result).today)
        def tomorrow = extractPriceAt(priceInfo(result).tomorrow)
        [today: today, tomorrow: tomorrow]
    }

    /**
     * Decode tibber price info query result for a time interval
     * @param jsonResult query result as json string
     * @return list of hourly prices for a time interval
     */
    def scanInterval(String jsonResult) {
        def result = slurper.parseText(jsonResult)
        extractPriceAt(priceInfo(result).range.nodes)
    }

    /**
     * Extract currency information
     * @param jsonResult query result as json string
     * @return currency name
     */
    def scanCurrency(String jsonResult) {
        def result = slurper.parseText(jsonResult)
        priceInfo(result).current.currency
    }

    /**
     * Provide GraphQL query string for tibber prices today and tomorrow
     * @param id homeId, if known
     * @return json query string
     */
    def priceQuery(id = '') {
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
     * Provide GraphQL query string for tibber hourly prices for an interval of hours
     * @param id homeId, if known
     * @param startingAt DateTime interval starts
     * @param hours interval lengh in hours
     * @return json query string
     */
    def intervalQuery(String id, DateTime startingAt, int hours){
        def start64 = encodeDateTimeBase64(startingAt.minusSeconds(1))
        """
{
  viewer {
    ${homeline(id)} {
      currentSubscription {
        priceInfo {
          range(
            resolution: HOURLY
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

