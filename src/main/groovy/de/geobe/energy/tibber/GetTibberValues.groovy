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

import org.apache.hc.client5.http.fluent.Request
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.Header
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.Instant

import java.time.LocalDateTime
import java.time.OffsetDateTime;

class GetTibberValues {
    Properties homeProperties = new Properties()
    Properties defaultProperties = new Properties()

    static void main(String[] args) {
        def gv = new GetTibberValues()
        def hp = gv.loadProperties(gv.homeProperties, '/home.properties')
        def dp = gv.loadProperties(gv.defaultProperties)
        gv.storeProperties(dp)
        def testquery = '''{"query": { viewer { login name homes {id type} } } }'''
        def testquery2 = '{"query":"{ viewer { login name homes { id timeZone currentSubscription { priceInfo { range( resolution: HOURLY first: 24 after: \\"MjAyMy0wMi0wMVQwMDowMDowMC4wMDArMDE6MDA=\\" ) { pageInfo { startCursor resolution } nodes { startsAt total currency } } } } } } }"}'
        def questquery = gv.makeViewerQuery('''{ viewer { login name homes {id type} } }''')
        def deepquery = gv.makeViewerQuery('{ viewer { login name homes { id timeZone currentSubscription { priceInfo { range( resolution: HOURLY first: 24 after: \"MjAyMy0wMi0wMVQwMDowMDowMC4wMDArMDE6MDA=\" ) { pageInfo { startCursor resolution } nodes { startsAt total currency } } } } } } }')
        println testquery2
        println deepquery
        gv.jsonFromTibber(testquery2, dp)
    }

    def makeViewerQuery(String path) {
        def prolog = '{"query":"'
        def epilog = '"}'
        path = path.replaceAll('\n', ' ').replaceAll('\\s\\s+', ' ').replaceAll('\"', '\\\\"')
        return prolog + path + epilog
    }

    def jsonFromTibber(String query, Properties props = defaultProperties) {

        Request request = Request.post props.tibberuri
        request.addHeader('Authorization', "Bearer ${props.accesstoken}")
        request.bodyString(query, ContentType.APPLICATION_JSON)
        def response = request.execute()
        def json = response.returnContent().asString()
        println "code: code, json: $json"
    }

    def loadProperties(Properties properties, String filename = '/default.properties') {
        this.getClass().getResource(filename).withInputStream {
            properties.load(it)
        }
        properties
    }

    def storeProperties(Properties properties, String filename = '/default.properties') {
        def f = this.getClass().getResource(filename).getFile()
        def w = new java.io.FileWriter(f)
        properties.store(w, 'set from program')
        w.flush()
        w.close()
    }
}

class TestTimeCalcs {
    static void main(String[] args) {
        def start = new DateTime(2023, 10, 1, 0, 0)
        def end = start.plusMonths(1) //new DateTime(2023, 3, 1, 0, 0)
        println start
        println end
        def istart = new Instant(start)
        def iend = new Instant(end)
        def duration = new Duration(istart, iend)
        println "istart: $istart, iend: $iend, duration: $duration.standardDays, $duration.standardHours"
    }
}

class TestStringNormalisation {
    static void main(String[] args) {
        def gv = new GetTibberValues()
        def hp = gv.loadProperties(gv.homeProperties, '/home.properties')
        def dp = gv.loadProperties(gv.defaultProperties, '/default.properties')
        def ts = new TestStringNormalisation()
        def id = hp.homeid
//        println ts.homeQuery(id)
        def normalizedQuery = gv.makeViewerQuery(ts.homeQuery(id))
        println normalizedQuery
        gv.jsonFromTibber(normalizedQuery, hp)
        def start = new DateTime(2023, 3, 1, 0, 0)
        normalizedQuery = gv.makeViewerQuery(ts.intervalQuery(dp.homeid, start,12))
        gv.jsonFromTibber(normalizedQuery, dp)
    }

    def encodeDateTimeBase64(DateTime dt) {
        dt.toString().bytes.encodeBase64().toString()
    }

    def homeQuery(id) {
"""
{
  viewer {
    login
    name
    home(id: "$id") {
      timeZone
      size
      address {
        address1
        address2
        address3
        city
        postalCode
        country
        latitude
        longitude
      }
      currentSubscription {
        priceInfo {
          today {
            startsAt
            total
            currency
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
        def sta64 = encodeDateTimeBase64(startingAt.minusSeconds(1))
"""
{
  viewer {
    name
    home(id: "$id") {
      currentSubscription {
        priceInfo {
          range(
            resolution: HOURLY
            first: $hours
            after: "$sta64"
          ) {
            nodes {
              startsAt
              total
              currency
            }
          }
        }
      }
    }
  }
}
"""
    }
}
