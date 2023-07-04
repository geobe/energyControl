package de.geobe.energy.automation

record PvChargeStrategyParams(
        int batPower = 3000,
        int batCapacity = 17500,
        int stopThreshold = -4000,
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
        minChargeUseBat = o.minChargeUseBat
        fullChargeUseBat = o.fullChargeUseBat
        minBatLoadPower = o.minBatLoadPower
        minBatUnloadPower = o.minBatUnloadPower
        toleranceStackSize = o.toleranceStackSize
    }
}