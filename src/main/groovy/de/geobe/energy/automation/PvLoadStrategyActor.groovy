package de.geobe.energy.automation


import de.geobe.energy.e3dc.PowerValues
import groovyx.gpars.activeobject.ActiveMethod
import groovyx.gpars.activeobject.ActiveObject

@ActiveObject
class PvLoadStrategyActor implements PowerValueSubscriber  {
    private PvLoadStrategyParams params = new PvLoadStrategyParams()
    private int stacksize = 10
    private List<PowerValues> valueTrace = []
    private enum State {
        WAIT_FOR_SURPLUS,
        CHANGED_SURPLUS,
        HAS_SURPLUS
    }
    private State currentState = State.CHANGED_SURPLUS

    private evalPower() {
        if (valueTrace.first().consumptionHome - valueTrace.first().powerSolar > params.stopThreshold) {
            waitForSurplus()
        } else if (valueTrace.size() == stacksize) {

        }
    }

    @ActiveMethod
    void takePowerValues(PowerValues powerValues) {
        if (valueTrace.size() > stacksize) {
            valueTrace.removeLast()
        }
        valueTrace.push powerValues
        println powerValues
    }

    @ActiveMethod(blocking = true)
    void setParams(PvLoadStrategyParams p) {
        params = new PvLoadStrategyParams(p)

    }

    static void main(String[] args) {
        PvLoadStrategyActor actor = new PvLoadStrategyActor()
        PowerMonitor.monitor.subscribe actor
        Thread.sleep(30000)
        PowerMonitor.monitor.unsubscribe actor
        Thread.sleep(10000)
        PowerMonitor.monitor.subscribe actor
        Thread.sleep(30000)
        PowerMonitor.monitor.shutdown()
    }
}
