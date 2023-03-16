package de.geobe.energy.tibber

import org.apache.hc.client5.http.fluent.Request
import org.apache.hc.core5.http.ContentType

/**
 * Responsibility: Format queries and send them to tibbew GraphQL API. Reurn resulting json data.<br>
 * The interface class to tibber GraphQL API for a specific tibber account.
 */
class TibberAccess {
    def accessToken = ''
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
        def postableQuery = makeViewerQuery(query)
        Request request = Request.post tibberUri
        request.addHeader('Authorization', "Bearer ${accessToken}")
        request.bodyString(postableQuery, ContentType.APPLICATION_JSON)
        def response = request.execute()
        def json = response.returnContent().asString()
        json
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
