/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2024. Georg Beier. All rights reserved.
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
import de.geobe.energy.automation.PowerStorageStatic
import org.joda.time.DateTime

/**
 * Supply values and static strings for storage control part of ui
 */
class StorageController implements PowerPriceSubscriber {

    private PowerStorageStatic powerStorageStatic = PowerStorageStatic.powerStorage
    private ValueController valueController
    private CurrentPowerPrices powerPrices

    StorageController(ValueController vController) {
        PowerPriceMonitor.monitor.subscribe this
        valueController = vController
    }

    synchronized storageControlContext(Map<String, Map<String, String>> ti18n) {
        def bufCtlStates = []
        def bufCtlPrices = []
        def hourNow = DateTime.now().hourOfDay
        def hours = powerStorageStatic.timetable
        hours.eachWithIndex { storageMode, hour ->
            def entry = [state: storageMode.toString()]
            float price
            boolean inTheFuture
            if (hour >= PowerStorageStatic.HOURS) {
                price = powerPrices.tomorrow?.size() ?
                        powerPrices.tomorrow[hour % PowerStorageStatic.HOURS].price : 0.0
                inTheFuture = true
            } else {
                price = powerPrices.today[hour].price
                inTheFuture = hour >= hourNow ? true : false

            }
            def fPrice = String.format('%.1f', price * 100)
            def bPrice = inTheFuture ? '<b>' + fPrice + '</b>' : fPrice
            entry.put('price', bPrice)
            entry.put('today', inTheFuture)//? 'true' : 'false')
            bufCtlStates << entry
        }
        def ctx = [
                bufCtlTitle   : ti18n.headingStrings.bufCtlTitle,
                bufCtlAuto    : ti18n.bufCtl.bufCtlAuto,
                bufCtlManual  : ti18n.bufCtl.bufCtlManual,
                bufCtlInactive: ti18n.bufCtl.bufCtlInactive,
                bufCtlStates  : bufCtlStates,
                bufCtlPrices  : bufCtlPrices
        ]
        switch (powerStorageStatic.chargeControlMode) {
            case PowerStorageStatic.ChargeControlMode.AUTO:
                ctx.put('checkedBufCtlAuto', 'checked')
                break
            case PowerStorageStatic.ChargeControlMode.MANUAL:
                ctx.put('checkedBufCtlManual', 'checked')
                break
            case PowerStorageStatic.ChargeControlMode.INACTIVE:
                ctx.put('checkedBufCtlInactive', 'checked')
                break
            default:
                ctx.put('checkedBufCtlInactive', 'checked')
        }
        def selectMap = [day    : powerStorageStatic.socDay,
                         night  : powerStorageStatic.socNight,
                         reserve: powerStorageStatic.socReserve]
        def socList = []
        for (soc in socLabels.keySet()) {
            def socMap = [label  : ti18n.bufCtlLabels[socLabels[soc]],
                          name   : socLabels[soc],
                          target : socLabels[soc],
                          options: (soc == 'reserve' ?
                                  PowerStorageStatic.reserveTargets :
                                  PowerStorageStatic.socTargets),
                          select : selectMap[soc]
            ]
            socList << socMap
        }
        ctx.put('bufCtlSocSelect', socList)
        ctx
    }

    static final socLabels = [day    : 'bufCtlSocDay',
                              night  : 'bufCtlSocNight',
                              reserve: 'bufCtlSocReserve']

    @Override
    void takePriceUpdate(CurrentPowerPrices prices) {
        powerPrices = prices
        def ctx = storageControlContext(valueController.tGlobal)
        def out = valueController.streamOut(
                valueController.getStorageControlTemplate(), ctx)
        valueController.updateWsValues(out)
    }
}
