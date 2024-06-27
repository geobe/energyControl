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

import de.geobe.energy.automation.CarChargingManager

class UiStringsDE {

    private static uiStrings

//    static synchronized getUiStrings() {
//        if(! uiStrings) {
//            uiStrings = new UiStringsDE()
//        }
//        uiStrings
//    }

    final mgmtStrings = [
            mgmtActive           : 'Lademanagement aktiv',
            mgmtInactive         : 'Lademanagement inaktiv',
            mgmtActivate         : 'Activate',
            mgmtDeactivate       : 'Deactivate',
            mgmtActivateConfirm  : 'Lademanagement aktivieren?',
            mgmtDeactivateConfirm: 'Lademanagement deaktivieren?',
    ]

    final chargeComandStrings = [
            cmdSurplus           : 'CHARGE_PV_SURPLUS',
            cmdTibber            : 'CHARGE_TIBBER',
            cmdAnyway            : 'CHARGE_ANYWAY',
            cmdStop              : 'CHARGE_STOP',
            wallboxStrategyHeader: 'Ladesteuerung',
            noStrategy           : 'Steuerung aus',
            pvStrategy           : 'Solar laden',
            tibberStrategy       : 'Tibber laden',
            anywayStrategy       : 'Sofort laden',
            stopStrategy         : 'Nicht laden',
    ]

    final stateStrings = [
            chargingStateLabel     : 'Auto Ladestatus',
            chargeManagerStateLabel: 'Lademanager',
            chargeStrategyLabel    : 'Auto Ladestrategie',
            tibberStrategyLabel    : 'Tibber Ladestrategie',
            tibberPriceLabel       : 'Tibber Preis'
    ]

    final headingStrings = [
            websiteTitle: 'PowerManagement',
            powerTitle  : 'Energiewerte',
            statesTitle : 'Statuswerte',
            graphTitle  : 'Energie Status'
    ]

    final powerStrings = [
            pvLabel     : 'PV',
            gridLabel   : 'Netz',
            batteryLabel: 'Batterie',
            homeLabel   : 'Haus',
            carLabel    : 'Auto',
            socLabel    : 'Speicher',
    ]

    /** Translations for various state values (enum values) */
    final stateTx = [
            // CarChargingManager.ChargeManagerState
            Inactive               : 'inaktiv',
            NoCarConnected         : 'kein Auto',
            ChargeTibber           : 'Tibber laden',
            ChargeAnyway           : 'Sofort laden',
            ChargingStopped        : 'Nicht laden',
            HasSurplus             : 'Solar Überschuss',
            NoSurplus              : 'Kein Solar Überschuss',
            WaitForExtCharge       : 'Auf Befehl warten',
            // CarChargingManager.ChargeManagerStrategy
            CHARGE_PV_SURPLUS      : 'Solar laden',
            CHARGE_TIBBER          : 'Tibber laden',
            CHARGE_ANYWAY          : 'Sofort laden',
            CHARGE_STOP            : 'Nicht laden',
            // WallboxMonitor.CarChargingState
            NO_CAR                 : 'kein Auto',
            WAIT_CAR               : 'Auf Auto warten',
            CHARGING               : 'lädt',
            CHARGING_ANYWAY        : 'sofort laden',
            FULLY_CHARGED          : 'aufgeladen',
            CHARGING_STOPPED_BY_APP: 'Stopp (App)',
            CHARGING_STOPPED_BY_CAR: 'Stopp (Auto)',
    ]

    final pvStrategySettingLabels = [
            batPower          : 'Wechselrichter [W]',
            batCapacity       : 'Kapazität [Wh]',
            stopThreshold     : 'Ladestopp < [W]',
            batStartHysteresis: 'Starthysterese [W]',
            minChargeUseBat   : 'max. laden [%]',
            fullChargeUseBat  : 'Batterie -> ab [%]',
            minBatLoadPower   : 'min. Batterie <- [W]',
            minBatUnloadPower : 'min. Batterie -> [W]',
            maxBatUnloadPower : 'max. Batterie -> [W]',
            toleranceStackSize: 'Mitteln über [n]'
    ]

    final pvSettingStrings = [
            settingsTitle : 'Einstellungen',
            settingsButton: 'Übernehmen'
    ]

    final graphLabels = [
            ePv       : 'Solar',
            eBattery  : 'Batterie',
            eGrid     : 'Netz',
            eHome     : 'Haus',
            eCar      : 'Auto',
            socBattery: '% Batterie',
            timeAxis  : 'Zeit',
            powerAxis : 'Leistung [W]',
            socAxis   : 'Ladezustand [%]'
    ]

    final graphControlStrings = [
            hours4       : '4 Stunden',
            hours2       : '2 Stunden',
            hour1        : '1 Stunde',
            minutes30    : '30 Minuten',
            minutes15    : '15 Minuten',
            minutes5     : '5 Minuten',
            graphSize    : 'Anzeigelänge',
            graphPosition: 'Anzeigeposition'
    ]
}
