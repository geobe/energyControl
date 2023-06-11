/*
 * MIT License
 *
 * Copyright (c) 2023  Georg Beier
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package de.geobe.energy.sunpath

/**
 * Utility class to calculate Julian Date. If no hour, minute and second are given,
 * Julian Day Number JND is returned. Algorithm based on Wikipedia article,
 * see <a href="https://de.wikipedia.org/wiki/Julianisches_Datum">
 *     Julianisches_Datum (German)</a> or
 * see <a href="https://en.wikipedia.org/wiki/Julian_day">Julian Day</a>
 */
class JulianDay {
    /**
     * calculate Julian Date or Julian Day number for time 00:00:00
     * @param year
     * @param month from 1 to 12
     * @param day from 1 to length of month
     * @param hour 0 .. 23
     * @param minute 0 .. 59
     * @param second 0 .. 59
     * @param gregorian if true, use Gregorian calendar, else Julian
     * @return Julian Date as a double value
     */
    def jD(long year, long month, long day,
           long hour = 0, long minute = 0, long second = 0, boolean gregorian = true) {
        if (month <= 2) {
            month += 12
            year -= 1
        }
        def partialDay = (second + minute * 60 + hour * 3600) / 86400.0
        partialDay += day
        double b = 0
        if (gregorian) {
            b = 2.0 - Math.floorDiv(year, 100) + Math.floorDiv(year, 400)
        }
        def jd = Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + partialDay + b - 1524.5
        jd
    }
}
