package de.geobe.energy.automation

record PvChargeStrategyParams(
        int batPower = 3000,
        int batCapacity = 17500,
        int stopThreshold = -3000,
        int batStartHysteresis = 1, //ampere!
        int minChargeUseBat = 60,
        int fullChargeUseBat = 80,
        int minBatLoadPower = 200,
        int minBatUnloadPower = 200,
        int toleranceStackSize = 10
) {
    PvChargeStrategyParams(PvChargeStrategyParams o) {
        batPower = o.batPower
        batCapacity = o.batCapacity
        stopThreshold = o.stopThreshold
        batStartHysteresis = o.batStartHysteresis
        minChargeUseBat = o.minChargeUseBat
        fullChargeUseBat = o.fullChargeUseBat
        minBatLoadPower = o.minBatLoadPower
        minBatUnloadPower = o.minBatUnloadPower
        toleranceStackSize = o.toleranceStackSize
    }
}