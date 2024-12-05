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

import de.geobe.energy.recording.LogMessageRecorder
import de.geobe.energy.recording.PowerCommunicationRecorder
import groovy.io.GroovyPrintWriter
import org.apache.hc.client5.http.fluent.Request
import org.apache.hc.core5.http.ContentType
import org.codehaus.groovy.runtime.StringBufferWriter

/**
 * Responsibility: Format queries and send them to tibbew GraphQL API. Return resulting json data.<br>
 * The interface class to tibber GraphQL API for a specific tibber account.
 */
class TibberAccess {
    /** key to a specific tibber account */
    def accessToken = ''
    /** URI of tibber GraphQL api */
    def tibberUri = ''

    /**
     * create tibber access object for a specific tibber account
     * @param uri   address of tibber api
     * @param token unique access token for the account
     */
    TibberAccess(String uri, String token) {
        accessToken = token
        tibberUri = uri
    }

    /**
     * execute query against tibber GraphQL API datasource at tibberUri
     * @param query graphQL query as it would work on tibber explorer page
     * @see <a href="https://developer.tibber.com/explorer">
     * @return query result as a json string
     */
    def jsonFromTibber(String query) {
        try {
            def postableQuery = makeViewerQuery(query)
            Request request = Request.post tibberUri
            request.addHeader('Authorization', "Bearer ${accessToken}")
            request.bodyString(postableQuery, ContentType.APPLICATION_JSON)
            def response = request.execute()
            def json = response.returnContent().asString()
            json
        } catch(Exception exception) {
            PowerCommunicationRecorder.logMessage "TibberAccess exception $exception"
            LogMessageRecorder.recorder.logMessage "TibberAccess exception $exception"
//            def sbw = new StringBufferWriter(new StringBuffer())
//            exception.printStackTrace(new GroovyPrintWriter(sbw))
//            LogMessageRecorder.recorder.logMessage "TibberAccess ${sbw.toString()}"
            return ''
        }
    }

    /**
     * format query from tibber explorer to GraphQL json format
     * @param explorerQuery multiline formated query
     * @return formated postable json
     */
    def makeViewerQuery(String explorerQuery) {
        def prolog = '{"query":"'
        def epilog = '"}'
        explorerQuery = explorerQuery.replaceAll('\n', ' ')
                .replaceAll('\\s\\s+', ' ')
                .replaceAll('\"', '\\\\"')
        return prolog + explorerQuery + epilog
    }

}
