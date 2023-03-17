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

import groovy.transform.Immutable
import org.joda.time.DateTime

/**
 * Responsibility: Provide and execute tibber queries, define resulting records
 * and decode returned json into record
 */
class TibberQueries {

    def slurper = new groovy.json.JsonSlurper()

    def encodeDateTimeBase64(DateTime dt) {
        dt.toString().bytes.encodeBase64().toString()
    }

    def homeline(id = '') {
        id ?  "home(id: \"$id\")".toString() : 'homes'
    }

    def priceInfo(def result) {
        result.data.viewer.home ?
                result.data.viewer.home.currentSubscription.priceInfo :
                result.data.viewer.homes[0].currentSubscription.priceInfo
    }

    def extractPriceAt(List list) {
        def priceList = []
        list.each {
            def t = new PriceAt(DateTime.parse(it.startsAt), (Float)it.total)
            priceList.add(t)
        }
        priceList
    }

    def scanPrice(String jsonResult) {
        def result = slurper.parseText(jsonResult)
        def today = extractPriceAt(priceInfo(result).today)
        def tomorrow = extractPriceAt(priceInfo(result).tomorrow)
        [today: today, tomorrow: tomorrow]
    }

    def scanInterval(String jsonResult) {
        def result = slurper.parseText(jsonResult)
        extractPriceAt(priceInfo(result).range.nodes)
    }

    def scanCurrency(String jsonResult) {
        def result = slurper.parseText(jsonResult)
        priceInfo(result).current.currency
    }

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

//@Immutable(knownImmutableClasses = [DateTime])
record PriceAt(DateTime start, Float price) {}

