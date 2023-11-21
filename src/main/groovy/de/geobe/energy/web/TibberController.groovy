/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2023. Georg Beier. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
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

package de.geobe.energy.web

import de.geobe.energy.automation.CurrentPowerPrices
import de.geobe.energy.automation.PowerPriceMonitor
import de.geobe.energy.automation.PowerPriceSubscriber
import de.geobe.energy.tibber.IPowerQueryRunner
import de.geobe.energy.tibber.PriceAt
import de.geobe.energy.tibber.TibberQueryRunner
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import static java.lang.Math.*

class TibberController {

    /** ValueController reference to access websockets and i18n */
    ValueController valueController
    /** tibber price values */
    CurrentPowerPrices tibberPrices
    /** subscriber for tibber prices */
    private tibberPriceSubscriber = new PowerPriceSubscriber() {
        @Override
        void takePriceUpdate(CurrentPowerPrices prices) {
            tibberPrices = prices
            valueController.updateWsValues(tibberUpdateString())
        }
    }

    TibberController(ValueController vc) {
        valueController = vc
        PowerPriceMonitor.monitor.subscribe tibberPriceSubscriber
    }

    def createTibberDataCtx(Map<String, Map<String, String>> ti18n) {
        def labels = []
        def pricesToday = tibberPrices.today
        def pricesTomorrow = tibberPrices.tomorrow
        def color = []
        def now = DateTime.now().hourOfDay
        def line = [
                dataset: []
        ]
        pricesToday.each { PriceAt priceAt ->
            labels << "'${GraphController.hour.print(priceAt.start)}'".toString()
            line.dataset << priceAt.price * 100
            int slot = priceAt.start.hourOfDay
            color << (slot < now ? rgbaOf(priceAt.price, 0.3) :
                    (slot == now ? rgbaOf(priceAt.price, 1.0) : rgbaOf(priceAt.price, 0.9)))
        }
        def titleAdd = "${ti18n.tibberStrings.tibToday}".toString()
        if (pricesTomorrow?.size() > 0) {
            titleAdd += " ${ti18n.tibberStrings.tibAnd} ${ti18n.tibberStrings.tibTomorrow}".toString()
            pricesTomorrow.each { PriceAt priceAt ->
                labels << "'${GraphController.hour.print(priceAt.start)}'".toString()
                line.dataset << priceAt.price * 100
                color << rgbaOf(priceAt.price, 0.7)
            }
        }
        line.color = color
        def timestamp = GraphController.hmmss.print DateTime.now()
        line.label = "${ti18n.tibberStrings.tibUpd}@$timestamp"
        def ctx = [
                tibberTitle : "${ti18n.tibberStrings.tibTitle} [${titleAdd}]",
                tibberLabels: labels.toString(),
                tibberLine  : line
        ]
        ctx
    }

    def tibberUpdateString() {
        def ctx =  createTibberDataCtx(valueController.tGlobal)
        ctx.put('newChart', true)
        valueController.streamOut(valueController.tibberGraph, ctx)
    }

    String rgbaOf(Float price, Float saturation) {
        float lowPrice = valueController.es.powerStrategySettings.tibLowPrice / 100
        float highPrice = valueController.es.powerStrategySettings.tibHighPrice / 100
        float factor = max(0.0d, min(1.0d, (price - lowPrice) / (highPrice - lowPrice)))
        int red = min(255, (int) (factor * 510))
        int green = min(255, (int) ((1.0 - factor) * 510))
        def blue = 0
        "'rgba($red, $green, $blue, $saturation)'"
    }
}
