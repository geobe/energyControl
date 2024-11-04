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

import de.geobe.energy.automation.PvChargeStrategy
import de.geobe.energy.automation.PowerStrategyParams
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.EqualsAndHashCode

@EqualsAndHashCode
class EnergySettings {

    static final SETTINGS_DIR = 'energyctl'
    static final SETTINGS_FILE = 'settings.json'

    EnergySettings(ValueController valueController, Map<String, Map<String, String>> uiStrings) {
        vc = valueController
//        ts = uiStrings
        processUpdate(restoreOrInitSettings())
    }

    /** simple access to PvChargeStrategy singleton */
    def pvcs = PvChargeStrategy.chargeStrategy

    private Map pvcsParameterMap = new PowerStrategyParams().toMap()

    /** all static template strings for spark */
//    Map<String, Map<String, String>> ts
    /** other controller classes */
    ValueController vc

    def getPowerStrategySettings() {
        pvcsParameterMap
    }

//    EnergySettings(PebbleEngine engine) {
//        this.engine = engine
//    }

    def settingsFormContext(Map<String, Map<String, String>> ti18n) {
        def ctx = [:]
        ctx.putAll ti18n.powerSettingStrings
        def inputListCtx = []
        powerStrategySettingKeys.each { key ->
            def inputCtx = [
                    label : ti18n.powerStrategySettingLabels[key],
                    id    : key,
                    name  : key,
                    limits: powerStrategySettingLimits[key],
                    value : pvcsParameterMap[key]
            ]
            inputListCtx.add inputCtx
        }
        ctx.put('ctxList', inputListCtx)
        ctx
    }

    boolean processUpdate(Map parameterMap) {
        def filteredParameterMap = [:]
        def changed = false
        pvcsParameterMap.each { param ->
            def key = param.key
            if (parameterMap.containsKey(key)) {
                if (parameterMap[key] != pvcsParameterMap[key]) {
                    changed = true
                }
                filteredParameterMap.put(key, parameterMap[key])
            }
        }
        if (changed) {
            def pvParams = new PowerStrategyParams(filteredParameterMap)
            pvcs.params = pvParams
            pvcsParameterMap = pvParams.toMap()
            saveSettings(pvcsParameterMap)
        }
        changed
    }

    /**
     * Save settings in a nicely formatted json file in user home directory
     */
    void saveSettings(Map settings) {
        def home = System.getProperty('user.home')
        def settingsDir = "$home/.$SETTINGS_DIR/"
        def dir = new File(settingsDir)
        if (!dir.exists()) {
            dir.mkdir()
        }
        if (dir.isDirectory()) {
            def json = JsonOutput.toJson(settings)
            json = JsonOutput.prettyPrint(json)
//            println "new JSON settings: $json"
            def settingsFile = new File(settingsDir, SETTINGS_FILE).withWriter { w ->
                w << json
            }
        }
    }

    /**
     * Restore from file, if exists, or set as predifined defaults
     */
    def restoreOrInitSettings() {
        def params
        def home = System.getProperty('user.home')
        def file = new File("$home/.${SETTINGS_DIR}/", "${SETTINGS_FILE}")
        if (file.exists() && file.text) {
            def json = file.text
            params = new JsonSlurper().parseText(json)
        } else {
            params = new PowerStrategyParams().toMap()
        }
        params
    }

    static void main(String[] args) {
        def settings = new EnergySettings()
        settings.saveSettings()
        def params = settings.restoreOrInitSettings()
        println params
        println "as record: ${new PowerStrategyParams(params)}"
    }

    final powerStrategySettingKeys = [
            'batPower',
            'batCapacity',
            'stopThreshold',
            'batStartHysteresis',
            'minChargeUseBat',
            'fullChargeUseBat',
            'minBatLoadPower',
            'minBatUnloadPower',
            'maxBatUnloadPower',
            'stopChargingBelow',
            'toleranceStackSize',
            'tibMaxBatLoad',
            'tibLowHighDiff',
            'tibLowPrice',
            'tibHighPrice',
            'tibChargeBelow',
    ]

    final powerStrategySettingLimits = [
            batPower          : 'min=1000  max=3000',
            batCapacity       : 'min=4375  max=17500',
            stopThreshold     : 'min=-4000  max=-2000',
            batStartHysteresis: 'min=0  max=2000',
            minChargeUseBat   : 'min=50  max=75',
            fullChargeUseBat  : 'min=70  max=90',
            minBatLoadPower   : 'min=0  max=1000',
            minBatUnloadPower : 'min=0  max=2000',
            maxBatUnloadPower : 'min=0  max=3000',
            stopChargingBelow : 'min=0 max=3800',
            toleranceStackSize: 'min=4  max=60',
            tibMaxBatLoad     : 'min=50  max=90',
            tibLowHighDiff    : 'min=3  max=50',
            tibLowPrice       : 'min=10  max=30',
            tibHighPrice      : 'min=30  max=100',
            tibChargeBelow    : 'min=10  max=50',
    ]

}
