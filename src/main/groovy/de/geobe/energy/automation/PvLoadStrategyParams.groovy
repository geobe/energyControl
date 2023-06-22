package de.geobe.energy.automation

record PvLoadStrategyParams(
        int batCurrent = 3000,
        int batCapacity = 17500,
        int stopThreshold = 4000,
        float minBatLoad = .5,
        float maxUseBat = .8,
        int toleranceTime = 30
) {
    PvLoadStrategyParams(PvLoadStrategyParams o) {
        batCurrent = o.batCurrent
        batCapacity = o.batCapacity
        stopThreshold = o.stopThreshold
        minBatLoad = o.minBatLoad
        maxUseBat = o.maxUseBat
        toleranceTime = o.toleranceTime
    }
}