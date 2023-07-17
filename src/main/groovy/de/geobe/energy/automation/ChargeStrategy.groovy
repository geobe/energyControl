package de.geobe.energy.automation

interface ChargeStrategy {
    void startStrategy(CarChargingManager manager)
    void stopStrategy()
}