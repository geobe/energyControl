/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2025. Georg Beier. All rights reserved.
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

package de.geobe.energy.obsolete

import de.geobe.energy.automation.PowerPriceMonitor
import de.geobe.energy.e3dc.E3dcInteractionRunner

/**
 * Planning task for next day to run at midnight (or maybe at 23 hours).
 * <ul>
 *     <li>calculate maximal saving and break, if saving too low</li>
 *     <li>calculate charging hours</li>
 *     <li>calculate supply and idle hours based on last days consumption profile</li>
 *     <li>build switching time [chrge|idle|auto] table</li>
 *     <li>start UpdatePlanTask</li>
 *     <li>start switching time table processing task</li>
 * </ul>
 */
class PlanTask extends TimerTask {
    PowerBufferingStrategy strategy

    PlanTask(PowerBufferingStrategy pbs) {
        strategy = pbs
    }

    @Override
    void run() {
        // tomorrows prices
        def prices = strategy.calculationPrices(PowerPriceMonitor.monitor.latestPrices)
        // pairwise juxtapose lowest prices ascending to highest prices descending
        def steps = strategy.calculateStepwiseCharging(prices)
        if (steps.maxSaving > strategy.bufferingParams.minimalSavingThreshold) {
            def soc = E3dcInteractionRunner.interactionRunner.currentValues.socBattery
            strategy.nightChargingIntervals = strategy.calculateNightCharging(prices, 0)
            float lowPrice = strategy.nightChargingIntervals.price * strategy.bufferingParams.loadCycleLoss
            strategy.supplyPlan = strategy.estimateOptimalSupply(prices, lowPrice, strategy.daySupplying, soc)
            def chargeAt = strategy.nightChargingIntervals.onOff
            strategy.switchingTimes = strategy.generateChargingModeTimeTable()
        }

    }
}

/**
 *
 * Update planning task for current day to run at the end of every hour with operation mode idle or auto.
 * <ul>
 *     <li>compare soc with estimate</li>
 *     <li>revise planning if big difference</li>
 *     <li>cancel self at end of day or soc == 0</li>
 * </ul>
 */
class UpdatePlanTask extends TimerTask {

    @Override
    void run() {

    }
}
