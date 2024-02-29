/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2024. Georg Beier. All rights reserved.
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

package de.geobe.energy.recording

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

class RecordingFile {
    static final DateTimeFormatter YEAR = DateTimeFormat.forPattern('yy')
    static final DateTimeFormatter MONTH = DateTimeFormat.forPattern('yy-MM')
    static final DateTimeFormatter DAY = DateTimeFormat.forPattern('yy-MM-dd')

    String reportTemplate
    File reportDir
    Span span

    enum Span {
        EVER,
        YEAR,
        MONTH,
        DAY,
    }

    RecordingFile(String dir, String baseName, Span span, String extension = 'txt', boolean atHome = true) {
        this.span = span
        def base = ''
        if(atHome) {
            base += System.getProperty('user.home')
        }
        def dirName ="$base/$dir"
        reportDir = new File(dirName)
        if(!reportDir.exists()) {
            reportDir.mkdir()
        }
        reportTemplate = "$baseName%.$extension"
    }

    File reportFile() {
        def now = DateTime.now()
        String replace
        switch (span) {
            case Span.EVER:
                replace = ''
                break
            case Span.YEAR:
                replace = YEAR.print(now)
                break
            case Span.MONTH:
                replace = MONTH.print(now)
                break
            case Span.DAY:
                replace = DAY.print(now)
        }
        def fileName = reportTemplate.replace('%', replace)
        new File(reportDir, fileName)
    }

    void appendReport(Object report) {
        def rept = report.toString()
        if(!rept.endsWith('\n')) {
            rept += '\n'
        }
        reportFile().append(rept)
    }
}
