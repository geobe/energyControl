## Car charging state definitions derived from wallbox values
### Recorded with an e-go wallbox and an MG5 car

#### Remarks

* Car battery fully charged cannot be found out from wallbox values without trying to charge.
* Neither charge level can be determined.
* State is derived from wallbox values mayCharge, forceState, carState and from previous state

#### Car State Table from wallbox values

| #    | Timestamp  | Trigger       | mayCharge | forceState    | carState | req  | energy    | Charging State   | Comment                     | 
|------|:-----------|:--------------|-----------|---------------|----------|------|:----------|:-----------------|:----------------------------|
| *    |            |               |           |               | IDLE     |      |           | NO_CAR           | **no car connected**        |
| *    |            |               |           |               | WAIT_CAR |      |           | WAIT_CAR         | **establishing connection** |
| *    |            |               | false     | OFF           | COMPLETE | x    | 0         | NOT_CHARGING     | **connected, not charging** |
|      | ---------- |               |           |               |          |      |           |                  |                             |
| 0    | 0          | startCharging | false     | NEUTRAL or ON | COMPLETE | \>=6 | 0         | CHARGE_REQUEST_0 | **normal startup**          |
| 1    | < 1 s      |               | true      | NEUTRAL or ON | COMPLETE | \>=6 | 0         | CHARGE_REQUEST_1 | takes time to start         |
| 2    | 10 .. 30 s |               | true      | NEUTRAL or ON | CHARGING | \>=6 | \>0 <4000 | STARTUP_CHARGING | depending on battery        |
| 3    | + ~5 s     |               | true      | NEUTRAL or ON | CHARGING | \>=6 | ~4000     | CHARGING         | temperature etc             |
|      | ---------- |               |           |               |          |      |           |                  |                             |
| 4    | x          |               | true      | NEUTRAL or ON | CHARGING | \>=6 | \>=4000   | CHARGING         | **charging completed**      |
| 5    |            |               | true      | NEUTRAL or ON | CHARGING |      | <4000     | FINISH_CHARGING  | decreasing to ~2180 W       |
| 6    | x +~15 min |               | true      | NEUTRAL or ON | COMPLETE |      | 0         | FULLY_CHARGED    |                             |
|      | ---------- |               |           |               |          |      |           |                  |                             |
| 4a   |            | stopCharging  | true      | OFF           | CHARGING | \>=6 | >4000     | CHARGE_STOP_0    | **stop charging**           |
| 5a   | + ~1.0 s   |               | false     | OFF           | CHARGING | x    | \>4000    | CHARGE_STOP_1    | takes time to stop          |
| 6a   | + ~1.0 s   |               | false     | OFF           | CHARGING | x    | <4000     | CHARGE_STOP_2    | charging                    |
| 7a   | + ~1.0 s   |               | false     | OFF           | COMPLETE | x    | 0         | NOT_CHARGING     | idle                        |
|      | ---------- |               |           |               |          |      |           |                  |                             |
| 0b   | 0          | startCharging | false     | NEUTRAL or ON | COMPLETE | \>=6 | 0         | CHARGE_REQUEST_0 | **if fully charged**        |
| 1b   | < 1 s      |               | true      | NEUTRAL or ON | COMPLETE | \>=6 | 0         | CHARGE_REQUEST_1 | like normal startup         |
| 2b   | 10 .. 15 s |               | true      | NEUTRAL or ON | CHARGING | \>=6 | \>0 <4000 | STARTUP_CHARGING | short charge impulse        |
| 3b   | + ~0.005 s |               | true      | NEUTRAL or ON | COMPLETE | x    | 0         | FULLY_CHARGED    | detect fully charged        |
|      | ---------- |               |           |               |          |      |           |                  |                             |
| 7/4b |            | stopCharging  | true      | OFF           | COMPLETE | x    | 0         | CHARGE_STOP_FULL | **switch off when full**    |
| 8/5b | + ~1 s     |               | false     | OFF           | COMPLETE | x    | 0         | NOT_CHARGING     | fully charged               |
|      | ---------- |               |           |               |          |      |           |                  |                             |
