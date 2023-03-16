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

