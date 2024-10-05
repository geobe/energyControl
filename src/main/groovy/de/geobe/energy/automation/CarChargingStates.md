## Car charging state definitions derived from wallbox values
### Recorded with an e-go wallbox and an MG5 car

#### Remarks

* Car battery fully charged cannot be found out from wallbox values without trying to charge.
* Neither charge level can be determined.
* Events can be derived from certain changes in wallbox values

#### Car State Table from wallbox values

| #  | Timestamp  | Trigger       | mayCharge | force   | car      | req  | energy     | Charging State   | Comment                 | 
|----|:-----------|:--------------|-----------|---------|----------|------|:-----------|:-----------------|:------------------------|
| *  | any        |               | false     | OFF     | COMPLETE | x    | 0          | NOT_CHARGING     | **idle**                |
|    | ---------- |               |           |         |          |      |            |                  |                         |
| 0  | 0          | startCharging | false     | NEUTRAL | COMPLETE | \>=6 | 0          | CHARGE_REQUEST_0 | **normal startup**      |
| 1  | < 1 s      |               | true      | NEUTRAL | COMPLETE | \>=6 | 0          | CHARGE_REQUEST_1 | takes time to start     |
| 2  | 10 .. 30 s |               | true      | NEUTRAL | CHARGING | \>=6 | \>0 \<4000 | STARTUP_CHARGING | depending on battery    |
| 3  | + ~5 s     |               | true      | NEUTRAL | CHARGING | \>=6 | ~4000      | CHARGING         | temperature etc         |
|    | ---------- |               |           |         |          |      |            |                  |                         |
| 4  | x          |               | true      | NEUTRAL | CHARGING | \>=6 | \>=4000    | CHARGING         | **charging completed**  |
| 5  |            |               | true      | NEUTRAL | CHARGING |      | \<4000     | FINISH_CHARGING  | decreasing to ~2180     |
| 6  | x + n      |               | true      | NEUTRAL | COMPLETE |      | 0          | FULLY_CHARGED    |                         |
|    | ---------- |               |           |         |          |      |            |                  |                         |
| 4a |            | stopCharging  | true      | OFF     | CHARGING | \>=6 | \>4000     | CHARGE_STOP_0    | **stop charging**       |
| 5a | + ~1.0 s   |               | false     | OFF     | CHARGING | x    | \>4000     | CHARGE_STOP_1    | takes time to stop      |
| 6a | + ~1.0 s   |               | false     | OFF     | CHARGING | x    | \<4000     | CHARGE_STOP_2    | charging                |
| 7a | + ~1.0 s   |               | false     | OFF     | COMPLETE | x    | 0          | NOT_CHARGING     | idle                    |
|    | ========== |               |           |         |          |      |            |                  |                         |
| 0b | 0          | startCharging | false     | NEUTRAL | COMPLETE | \>=6 | 0          | CHARGE_REQUEST_0 | **if fully charged**    |
| 1b | < 1 s      |               | true      | NEUTRAL | COMPLETE | \>=6 | 0          | CHARGE_REQUEST_1 | like normal startup     |
| 2b | 10 .. 15 s |               | true      | NEUTRAL | CHARGING | \>=6 | \>0 \<4000 | STARTUP_CHARGING | short charge impulse    |
| 3b | + ~0.005 s |               | true      | NEUTRAL | COMPLETE | x    | 0          | NO_CHARGE_FULL   | detect fully charged    |
|    | ---------- |               |           |         |          |      |            |                  |                         |
| 4c |            | stopCharging  | true      | OFF     | COMPLETE | x    | 0          | CHARGE_STOP_FULL | **switch off charging** |
| 5c | + ~1 s     |               | false     | OFF     | COMPLETE | x    | 0          | NOT_CHARGING     | charged                 |
|    | ---------- |               |           |         |          |      |            |                  |                         |

#### Events from states



| Event         | From State       | To State         | Comment                  |
|---------------|------------------|------------------|--------------------------|
| STARTING      | CHARGE_REQUEST_x | STARTUP_CHARGING | really needed?           |
| CHARGING      | STARTUP_CHARGING | CHARGING         |                          |
| FULLY_CHARGED | STARTUP_CHARGING | NO_CHARGE_FULL   |                          |
| FULLY_CHARGED | CHARGING         | NO_CHARGE_FULL   | or use current gradient? |
| STOPPED       | CHARGE_STOP_x    | NOT_CHARGING     |                          |
