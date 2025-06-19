## Car charging state definitions derived from wallbox values

### Recorded with an e-go wallbox and an MG5 car

#### Remarks

* Car battery fully charged cannot be found out from wallbox values without trying to charge.
* Neither charge level can be determined.
* State is derived from wallbox values mayCharge, forceState, carState and from previous state

#### Car State Table from wallbox values

| #    | Timestamp  | Trigger       | allowedToCharge | forceState    | carState | req  | energy    | Charging State       | Comment                   | transient |
|------|:-----------|:--------------|-----------------|---------------|----------|------|:----------|:---------------------|:--------------------------|:----------|
| A    |            |               |                 |               | IDLE     |      |           | **NO_CAR**           | **no car connected**      |           |
| B    |            |               |                 |               | WAIT_CAR |      |           | **WAIT_CAR**         | establishing connection   | x         |
| C    |            |               | false           | OFF           | COMPLETE | x    | 0         | **NOT_CHARGING**     | *connected, not charging* |           |
|      | ---------- |               |                 |               |          |      |           |                      | **normal startup**        | --------- |
|      |            | startCharging |                 | **triggers**  |          |      |           |                      |                           |           |
| 0    | 0          |               | false           | NEUTRAL or ON | COMPLETE | \>=6 | 0         | CHARGE_REQUEST_0     | startup                   | x         |
| 1    | < 1 s      |               | true            | NEUTRAL or ON | COMPLETE | \>=6 | 0         | CHARGE_REQUEST_1     | takes time to start       | x         |
| 2    | 10 .. 30 s |               | true            | NEUTRAL or ON | CHARGING | \>=6 | \>0 <4000 | **STARTUP_CHARGING** | depends on batt. temp etc | xsummary  |
| 3    | + ~5 s     |               | true            | NEUTRAL or ON | CHARGING | \>=6 | ~4000     | **CHARGING**         |                           |           |
|      | ---------- |               |                 |               |          |      |           |                      | **completing**            | --------- |
| 4    |            |               | true            | NEUTRAL or ON | CHARGING |      | <4000     | **FINISH_CHARGING**  | decreasing to ~2180 W     |           |
| 5    | x +~15 min |               | true            | NEUTRAL or ON | COMPLETE |      | 0         | **FULLY_CHARGED**    |                           |           |
|      | ---------- |               |                 |               |          |      |           |                      | **force stop**            | --------- |
|      |            | stopCharging  |                 | **triggers**  |          |      |           |                      |                           |           |
| 4a   |            |               | true            | OFF           | CHARGING | \>=6 | >4000     | CHARGE_STOP_0        | stop charging             | x         |
| 5a   | + ~1.0 s   |               | false           | OFF           | CHARGING | x    | \>4000    | CHARGE_STOP_1        | takes time to stop        | x         |
| 6a   | + ~1.0 s   |               | false           | OFF           | CHARGING | x    | <4000     | **CHARGE_STOPPING**  | charging                  | xsummary  |
| 7a   | + ~1.0 s   |               | false           | OFF           | COMPLETE | x    | 0         | **NOT_CHARGING**     | idle                      |           |
|      | ---------- |               |                 |               |          |      |           |                      | **if fully charged**      | --------- |
|      |            | startCharging |                 | **triggers**  |          |      |           |                      |                           |           |
| 0b   | 0          |               | false           | NEUTRAL or ON | COMPLETE | \>=6 | 0         | CHARGE_REQUEST_0     | startup                   | x         |
| 1b   | < 1 s      |               | true            | NEUTRAL or ON | COMPLETE | \>=6 | 0         | CHARGE_REQUEST_1     | like normal startup       | x         |
| 2b   | 10 .. 15 s |               | true            | NEUTRAL or ON | CHARGING | \>=6 | \>0 <4000 | **STARTUP_CHARGING** | short charge impulse      | xsummary  |
| 3b   | + ~0.005 s |               | true            | NEUTRAL or ON | COMPLETE | x    | 0         | **FULLY_CHARGED**    | detect fully charged      |           |
|      | ---------- |               |                 |               |          |      |           |                      | **switch off when full**  | --------- |
|      |            | stopCharging  |                 | **triggers**  |          |      |           |                      |                           |           |
| 6/4b |            |               | true            | OFF           | COMPLETE | x    | 0         | **CHARGE_STOP_FULL** |                           | x         |
| 7/5b | + ~1 s     |               | false           | OFF           | COMPLETE | x    | 0         | **NOT_CHARGING**     | fully charged             |           |
|      | ---------- |               |                 |               |          |      |           |                      |                           | --------- |

