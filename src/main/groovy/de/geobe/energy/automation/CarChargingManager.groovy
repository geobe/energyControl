package de.geobe.energy.automation

import de.geobe.energy.go_e.Wallbox
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

@ActiveObject
class CarChargingManager implements WallboxStateSubscriber {

    static enum ChargingState {
        Inactive,
        Active,
        NoCarConnected,
        CarConnected,
        ChargePvSurplus,
        ChargeTibber,
        ChargeAnyway,
        ChargingStopped,
        HasSurplus,
        NoSurplus
    }

    static enum ChargeStrategy {
        CHARGE_PV_SURPLUS,
        CHARGE_TIBBER,
        CHARGE_ANYWAY,
        CHARGE_STOP
    }

    static enum ChargingEvent {
        Activate,
        Deactivate,
        CarConnected,
        CarDisconnected,
        ChargeStrategyChanged,
        Surplus,
        NoSurplus,
        TibberGo,
        TibberStop
    }

    ChargingState chargingState = ChargingState.Inactive
    ChargeStrategy chargeStrategy = ChargeStrategy.CHARGE_STOP

    @ActiveMethod
    void setActive(boolean active) {

    }

    @ActiveMethod
    @Override
    void takeWallboxState(WallboxMonitor.CarLoadingState carState) {

    }

    @ActiveMethod
    void takeStrategy(ChargeStrategy strategy) {

    }

    @ActiveMethod
    void takeSurplus(int amps) {

    }

    @ActiveMethod(blocking = true)
    private void takeEvent(ChargingEvent event, def param = null) {
        switch (event) {
            case ChargingEvent.Activate:
                switch (chargingState) {
                    case ChargingState.Inactive:
                        chargingState = enterActive()
                }
                break
            case ChargingEvent.Deactivate:
                switch (chargingState) {
                    case ChargingState.Inactive:
                        return
                    case ChargingState.NoCarConnected:
                    case ChargingState.ChargingStopped:
                    case ChargingState.ChargeAnyway:
                        break
                    case ChargingState.ChargeTibber:
                        stopTibberMonitor()
                        break
                    case ChargingState.HasSurplus:
                    case ChargingState.NoSurplus:
                        PvChargeStrategy.loadStrategy.stopStrategy(this)
                        break
                }
                WallboxMonitor.monitor.unsubscribeState this
        }
    }

    private enterActive() {
        WallboxMonitor.monitor.subscribeState this
        if (WallboxMonitor.monitor.loadingState == WallboxMonitor.CarLoadingState.NO_CAR) {
            ChargingState.NoCarConnected
        } else {
            enterCarConnected()
        }
    }

    private exitActive() {
        WallboxMonitor.monitor.unsubscribeState this

    }

    private enterCarConnected() {
        switch (chargeStrategy) {
            case ChargeStrategy.CHARGE_STOP:
                stopCharging()
                return ChargingState.ChargingStopped
            case ChargeStrategy.CHARGE_ANYWAY:
                startCharging()
                setCurrent(Wallbox.wallbox.maxCurrent)
                return  ChargingState.ChargeAnyway
            case ChargeStrategy.CHARGE_TIBBER:
                startTibberMonitor()
                return ChargingState.ChargeTibber
            case ChargeStrategy.CHARGE_PV_SURPLUS:
                PvChargeStrategy.loadStrategy.startStrategy this
                stopCharging()
                return ChargingState.NoSurplus
        }
    }

    private stopCharging() {

    }

    private startCharging() {

    }

    private setCurrent(int amp) {

    }

    private startTibberMonitor() {

    }

    private stopTibberMonitor() {

    }
}
