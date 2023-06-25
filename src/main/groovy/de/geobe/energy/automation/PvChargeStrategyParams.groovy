package de.geobe.energy.automation

record PvChargeStrategyParams(
        int batPower = 3000,
        int batCapacity = 17500,
        int stopThreshold = -4000,
        int minUseBat = 50,
        int maxUseBat = 80,
        int toleranceTime = 30
) {
    PvChargeStrategyParams(PvChargeStrategyParams o) {
        batPower = o.batPower
        batCapacity = o.batCapacity
        stopThreshold = o.stopThreshold
        minUseBat = o.minUseBat
        maxUseBat = o.maxUseBat
        toleranceTime = o.toleranceTime
    }
}