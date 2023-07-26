/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2023. Georg Beier. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.geobe.energy.sunpath

import groovy.json.JsonSlurper
import groovy.transform.Sortable
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoField

import static java.lang.Math.*

/**
 * Calculate approximate solar position (azimuth, elevation) for a given time at a given location. <br>
 * Calculus is based on Wikipedia article (in German),
 * @see <a href="https://de.wikipedia.org/wiki/Sonnenstand#Genauere_Ermittlung_des_Sonnenstandes_f%C3%BCr_einen_Zeitpunkt">
 *     Genauere Ermittlung des Sonnenstandes für einen Zeitpunkt (German)</a>
 */
class SolarPosition {

    JulianDay julianDay = new JulianDay()

    /**
     * read basic configuration parameters from a JSON file
     * @param filename
     * @return a map representing the json file content
     */
    static readConfig(String filename) {
        JsonSlurper slurper = new JsonSlurper()
        URL cfgUrl = SolarPosition.classLoader.getResource(filename)
        File cfgFile = new File(cfgUrl.getPath())
        def cfg = slurper.parse(cfgFile)
        cfg
    }

    /**
     * Calculate solar equatorial coordinates (azimuth, elevation) in degrees and radians.
     * Return all intermediate values for verification.
     *
     * @param year all time values must be given in UTC
     * @param month
     * @param day
     * @param hour
     * @param minute
     * @param latitude in degrees [-90° .. 90°], northern hemisphere is positive
     * @param longitude in degrees [-180° .. 180°], westward is negative
     * @return a map of all calculated values, if applicable, in degrees and radians
     */
    def solarCoordinates(long year, long month, long day, long hour, long minute, double latitude, double longitude) {
        def jD0 = julianDay.jD(year, month, day)
        def t0 = (jD0 - 2451545.0) / 36525 // UT0 at given date in julian centuries since 2000
        def tOfDayh = (double) hour + ((double) minute) / 60.0
        // mean Greenwich sidereal time for the given UT (in hours)
        def thetaGh = 6.697376 + 2400.05134 * t0 + 1.002738 * tOfDayh
        thetaGh %= 24
        // Greenwich hour angle of spring point
        def thetaG = thetaGh * 15
        // local hour angle of spring point
        def theta = thetaG + longitude
        def thetaRad = toRadians(theta)
        def ecliptic = solarEclipticalCoordinates(year, month, day, hour, minute)
        // get right ascension and declination
        double alphaRad = ecliptic.alphaRad
        double deltaRad = ecliptic.deltaRad
        // hour angle of actual location
        def tauRad = thetaRad - alphaRad
        // lat and lon in radian
        def phiRad = toRadians(latitude)
        def lambdaRad = toRadians(longitude)
        // calculate azimuth
        def divisor = (cos(tauRad) * sin(phiRad) - tan(deltaRad) * cos(phiRad))
        def azimuthRad = atan(sin(tauRad) / divisor)
        if (divisor < 0.0) {
            azimuthRad += PI
        }
        // azimuth angles > 180° transformed to negative angles
        if (azimuthRad > PI) {
            azimuthRad -= 2 * PI
        }
        // calculate elevation angle
        def elevRad = asin(cos(deltaRad) * cos(tauRad) * cos(phiRad) + sin(deltaRad) * sin(phiRad))
        def result = [azimuthRad  : azimuthRad, azimuth: toDegrees(azimuthRad),
                      elevationRad: elevRad, elevation: toDegrees(elevRad),
                      jD0         : jD0, t0: t0,
                      thetaGh     : thetaGh, theta: theta
        ]
        result.putAll(ecliptic)
        result.putAll(refractionCorrectedElevation(elevRad))
        result
    }

    /**
     * Helper method to calculate ecliptical coordinates of sun at a given UTC time
     * @param year all time values must be given in UTC
     * @param month
     * @param day
     * @param hour
     * @param minute
     * @return a map of all calculated values, if applicable, in degrees and radians
     */
    def solarEclipticalCoordinates(long year, long month, long day, long hour, long minute) {
        // Julian number of days = days and fraction of day since 2000 12:00 UT
        def jD = julianDay.jD(year, month, day, hour, minute)
        def n = jD - 2451545.0
        // ecliptical length of sun [°]
        def eclipticalL = (280.460 + 0.9856474 * n) % 360.0
        // mean anormality g [°]
        def g = (357.528 + 0.9856003 * n) % 360.0
        def gRad = toRadians(g)
        // anomaly corrected ecliptical length [°]
        def lambda = eclipticalL + 1.915 * sin(gRad) + 0.01997 * sin(2 * gRad)
        def lambdaRad = toRadians(lambda)
        // inclined ecliptic [°]
        def epsilon = 23.439 - 0.0000004 * n
        def epsilonRad = toRadians(epsilon)
        def alphaRad = atan(cos(epsilonRad) * tan(lambdaRad))
        if (cos(lambdaRad) < 0) {
            alphaRad += PI
        }
        // declination
        def deltaRad = asin(sin(epsilonRad) * sin(lambdaRad))
        def result =
                [deltaRad   : deltaRad, delta: toDegrees(deltaRad),
                 alphaRad   : alphaRad, alpha: toDegrees(alphaRad),
                 jD         : jD, n: n, g: g,
                 eclipticalL: eclipticalL, epsilon: epsilon,
                 lambda     : lambda,
                ]
        result
    }

    /**
     * Helper method to calculate apparent elevation caused by atmospheric refraction
     *
     * @param elevRad elevation in radians
     * @return a map of refracted elevation in degrees and radians
     */
    def refractionCorrectedElevation(double elevRad) {
        def elevation = toDegrees(elevRad)
        def ec = elevation + 10.3 / (elevation + 5.11)
        def rInMinutes = 1.02 / tan(toRadians(ec))
        def elevRefracted = elevation + rInMinutes / 60.0
        [elevRefracted: elevRefracted, elevRefractedRad: toRadians(elevRefracted)]
    }

    /**
     * @see <a href="https://www.pveducation.org/pvcdrom/properties-of-sunlight/solar-time">
     * @param forTime
     * @param lon
     * @return
     */
    DateTime localSolarTime(DateTime forTime, double lon) {
//        def utc = forTime.withZone(DateTimeZone.UTC)
        def day = forTime.dayOfYear
        def bRad = toRadians((360 / 365) * (day - 81))
        def eqOfTime = 9.87 * sin(2.0 * bRad)  - 7.53 * cos(bRad) - 1.5 * sin(bRad)
        def tCorrection = 4 * (lon-15.0) + eqOfTime
        def utcSolarTime = forTime.plusMinutes((int) tCorrection)
        utcSolarTime//.toDateTime(forTime.zone)
    }

    /**
     * Get information about local time at a given longitude. The algorithm is really simplified
     * as it assumes all time zones to be centered at multiples of 15° of longitude value.
     * @param year
     * @param month
     * @param day
     * @param lon geographic longitude [°]
     * @param zoneId id of local timezone, default is operation system default
     * @return a map with values <br>
     * offset: timezone offset to UTC [sec] <br>
     * localOffsetUTC: approximate offset of longitude position to UTC [sec]
     * localOffset: offset within timezone
     */
    def localTimeInfo(int year, int month, int day, double lon, ZoneId zoneId = ZoneId.systemDefault()) {
        def closest = Math.round(lon / 15)
        // offset of solar local time to local timezone in seconds
        def localOffset = Math.round((lon - closest * 15) * 240)
        LocalDate localDate = LocalDate.of(year, month, day)
        ZonedDateTime zonedDateTime = localDate.atStartOfDay(zoneId)
        // offset of local time zone relative to UTC in seconds
        def offset = zonedDateTime.getOffset().get(ChronoField.OFFSET_SECONDS)
        return [offset        : offset,
                localOffsetUTC: offset + localOffset,
                localOffset   : localOffset]
    }

    /**
     * create datasets representing solar position, absolute and in relation to twisted and tilted solar panel,
     * intended as input into drawing methods. Latitude and longitude in degrees, e.g. as given by Google maps.
     * @param latitude
     * @param longitude
     * @param year of interest
     * @param useSolarNoon if true (=default), calculate graph for true solar local time, else use time of local timezone
     * @param tilt Inclination of panel relative to earth surface in its direction.
     * @param direction horizontal compass direction of the surface normal.
     * @param calendarDays a list of [month, day] pairs for which solar positions are calculated
     * @param zoneId calculate time stamps for this timezone, as default use system default
     * @return a map of maps of XY value lists representing solar positions:<br>
     * solarPaths: map with solar path azimuth and elevation angles for every hour on given calendar days<br>
     * timedPositions: map with graphs linking azimuth and elevation angles for every calendar day at same hour
     * panelExpositions: map with graphs showing relative elevation in relation to solar panels
     * panelYields: map with graphs showing relative yield (i.e sin(elevation)) for solar panels per hour
     * totalPanelYield: graph showing day-integrated yield per date
     */
    def solarPositionGraph(double latitude, double longitude, int year,
                           boolean useSolarNoon = true,
                           double tilt = 0, double direction = 0,
                           MonthDay[] calendarDays = MonthDay.sample, ZoneId zoneId = ZoneId.systemDefault()) {
        def timeoffsetH = 0
        def timeoffsetM = 0
        def timeoffsetS = 0
        def timeZoneOffset = 0
        if (useSolarNoon) {
            longitude = 0.0
        }
        def sunPaths = [:]
        def timedPositions = [:]
        def panelExpositions = [:]
        def panelYields = [:]
        def totalPanelYield = [:]
        def tiltProjection = new TiltProjection()
        def solarIntensity = new SolarIntensity()
        calendarDays.each { day ->
            def sunPath = []
            def panelExposition = []
            def panelYield = []
            double totalYield = 0.0
            if (!useSolarNoon) {
                def toffset = localTimeInfo(year, day.month, day.day, longitude, zoneId)
                timeoffsetS = toffset.localOffsetUTC
                timeoffsetH = timeoffsetS.intdiv(3600)
                timeoffsetM = Math.round(timeoffsetS / 60.0)
                timeZoneOffset = toffset.offset.intdiv(3600)
            }
            (0..<24).each { hour ->
                def val = solarCoordinates(year, day.month, day.day, hour - timeoffsetH, -timeoffsetM, latitude, longitude)
                // rely on cutoff of negative values by graphics software
                def elevation = val.elevRefracted
//                totalYield = 0.0
                if (elevation >= -10.0) {
                    def azimuth = val.azimuth
                    def xy = new XY(x: azimuth, y: elevation)
                    sunPath << xy

                    if (!timedPositions[(hour - timeZoneOffset)]) {
                        timedPositions[(hour - timeZoneOffset)] = [xy]
                    } else {
                        timedPositions[(hour - timeZoneOffset)] << xy
                    }
                    def epsilon = tiltProjection.relativeElevation(tilt, direction, azimuth, elevation)
                    xy = new XY(x: azimuth, y: toDegrees(epsilon))
                    panelExposition << xy
                    def solarIntensityNow = solarIntensity.relativeIntensity(90 - elevation)
                    xy = new XY(x: hour - timeZoneOffset, y: sin(epsilon) * solarIntensityNow)
                    panelYield << xy
                    totalYield += sin(epsilon) * solarIntensityNow
                }
            }
            sunPaths[(day)] = sunPath
            panelExpositions[(day)] = panelExposition
            panelYields[(day)] = panelYield
            totalPanelYield[(day)] = totalYield
        }
        [
                sunpaths        : sunPaths,
                timedPositions  : timedPositions,
                panelExpositions: panelExpositions,
                panelYields     : panelYields,
                totalPanelYield : totalPanelYield
        ]
    }

    /**
     * Prepare output of relevant calculated values to make them comparable with a table in Wikipedia
     * @param year
     * @param month
     * @param day
     * @param hour
     * @param minute
     * @param latitude
     * @param longitude
     * @return printable string
     */
    def showResult(long year, long month, long day, long hour, long minute, double latitude, double longitude) {
        def r = solarCoordinates(year, month, day, hour, minute, latitude, longitude)
        println """
JD = ${r.jD} \t\t\tn = ${r.n} \t\t\t\t\tL = ${r.eclipticalL}
g = ${r.g} \t\tlambda = ${r.lambda} \tepsilon = ${r.epsilon}
alpha = ${r.alpha} \tdelta = ${r.delta} \t\tJD0 = ${r.jD0}
T0 = ${r.t0} \tthetaGh = ${r.thetaGh} \ttheta = ${r.theta} 
a = ${r.azimuth} \t\th = ${r.elevation} \t\t\thR = ${r.elevRefracted}"""
    }


/**
 * print solar position for Munich at 06.08.2006 8:00 CEST (= 6:00 UT), lat = 48.1°, lon = 11.6 E
 * see <a href="https://de.wikipedia.org/wiki/Sonnenstand#Beispiel">Solar Position example Munich</a> (in German)
 * @param args
 */
    static void main(String[] args) {
        def solarPosition = new SolarPosition()
        def cfg = solarPosition.readConfig('sunpathConfig.json')
        def lon = cfg.location.lon
        def lat = cfg.location.lat
        def graphs = solarPosition.solarPositionGraph(lat, lon, 2021, true, 12.5, 198)
        println 'sunpaths'
        graphs.sunpaths.each { println it }
//        println 'timed Positions'
//        graphs.timedPositions.sort().each { println it }
        println 'panel Expositions'
        graphs.panelExpositions.each { println it }
        println 'panel Yields'
        graphs.panelYields.sort().each { println it }
        print "totalPanelYield: "
        graphs.totalPanelYield.each { k, v ->
            printf('%s: % 5.2f, ', k.toString(), v)
        }
        println "\ntotalAnnualYield: ${graphs.totalPanelYield.collect { k, v -> v }.sum()}"
//        def map = solarPosition.localTimeInfo(2021, 4, 20, lon)
//        println "$map"
    }
}

/**
 * put month and day into one structure
 */
@Sortable
class MonthDay {
    static sample = [
            new MonthDay(month: 12, day: 21),
            new MonthDay(month: 1, day: 20),
            new MonthDay(month: 2, day: 18),
            new MonthDay(month: 3, day: 20),
            new MonthDay(month: 4, day: 20),
            new MonthDay(month: 5, day: 21),
            new MonthDay(month: 6, day: 21),
    ]
    int month
    int day

    @Override
    String toString() {
        return "$day. ${Month.values()[month - 1].toString().substring(0, 3)}".toString()
    }
}

/**
 * put coordinate pairs into one structure
 */
class XY {
    double x
    double y

    @Override
    String toString() {
        String.format "(% 7.2f, % 7.2f)", x, y
    }
}
