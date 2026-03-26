/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026. Georg Beier. All rights reserved.
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

package de.geobe.energy.automation.utils

import de.geobe.energy.automation.DeadlockGuard
import de.geobe.energy.recording.LogMessageRecorder
import de.geobe.energy.web.EnergyControlUI
import groovy.transform.ImmutableOptions
import org.joda.time.DateTime

class TraceMonitor {
    List<TraceRecord> traceStack = []
    Runnable guard = new Runnable() {
        @Override
        void run() {
            reportAndRestart()
        }
    }

    DeadlockGuard deadlockGuard

    private static TraceMonitor traceMonitor

    static synchronized getMonitor() {
        if(! traceMonitor) {
            traceMonitor = new TraceMonitor()
        }
        traceMonitor
    }

    private TraceMonitor(long latency = 10) {
        deadlockGuard = new DeadlockGuard(guard, latency)
    }

    def reset(long t = 0) {
        traceStack.clear()
        if(!deadlockGuard.start(t)) {
//            print '*'
//        } else {
            LogMessageRecorder.logMessage "no previous guard"
//            println 'no previous guard'
        }
    }

    def trace(String msg) {
        traceStack.add new TraceRecord( DateTime.now(), msg)
    }

    /**
     * Report a call trace to LogMessageRecorder and restart program via EnergyControlUI method.
     * This method should only be called when the main event loop is blocked fatally.
     */
    void reportAndRestart() {
        if(traceStack) {
            LogMessageRecorder.logTrace traceStack
        } else {
            LogMessageRecorder.logMessage "empty trace stack"
        }
        EnergyControlUI.failed()
    }
}

@ImmutableOptions(knownImmutableClasses = DateTime)
record TraceRecord(DateTime stamp, String msg) {
    @Override
    String toString() {
        return "--- ${LogMessageRecorder.fullStamp.print(stamp)}\t$msg"
    }
}