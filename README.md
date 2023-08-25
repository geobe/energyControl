 ## energyControl Project
### Energy management with PV, energy storage system, wallbox, heatpump and more
Control and plan electric energy distribution to heatpump and wallbox 
based on 
- current and next-day hourly electricty prices (tibber), 
- current PV production and energy storage load state (e3dc),
- expected PV production based on
  - hourly weather forecast (dwd, German weather service) 
  - and calculated solar altitude,
- and expected heat pump power consumption based on weather forecast.
### Development Status and Roadmap
#### Version 0.1-beta
18.03.2023 
* Access to tibber data works.
* New Queries can easily be integrated.
#### Version 0.2-beta
09.04.2023
* Access to current live data and history data of e3dc storage works. 
* Operation modes of S10 storage device can be set.
#### Version 03-beta
25.08.2023
* Access to go-e wallbox
* Wallbox control based on PV production
* Web UI for wallbox load mode control ans visualization
* Run and test on ~~Raspberry Pi~~ x86 mini pc (linux mint)
#### Version 0.4-x
TODO
* Wallbox car charging at tibber low price times
* E3DC charging at tibber low price times
#### Version 0.5-x
TODO
* Accessing DWD weather forecast
* Integration of solar position and irradiation intensity

### Design Objectives
#### Meaning of Date and Time values
The different modules need a consistent definition for the semantic of temporal data.
Tibber values give the basis for interpretaion of time.
The price for electric power is given with an hourly resolution, based on prices
of european energy exchange (EEX). So reasonable time values are full hours. 
Time values have following meaning for the modules:
- tibber: as explained above, only full hours are meaningful
- e3dc: History data are summarized on hourly basis, 
current values (e.g. as used for wallbox control) can be retrieved at any time
- Solar intensity could be calculated for arbitrary time values. To be compatible
with other values, values are calculated for half hours, beeing representative
for their full hour
- DWD weather forecasts again are on an hourly basis
- Wallbox control has no own time dependency but makes use of photovoltaic
production and tibber prices
- Internally, Instant values are used for a consistent handling of time and
simultaneity and as database index

