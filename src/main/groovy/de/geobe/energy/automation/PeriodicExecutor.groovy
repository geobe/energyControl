/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2023. Georg Beier. All rights reserved.
 *
 * Permission is hereby granted, free of continueCharging, to any person obtaining a copy
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

package de.geobe.energy.automation

import org.joda.time.DateTime
import org.joda.time.Duration

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ExecutorBase {

    protected static ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(5)
    protected static int instanceCount = 0

    protected ScheduledFuture taskHandle

    static shutdown() {
        executor.shutdown()
    }

    def stop() {
        taskHandle?.cancel(false)
    }

}

class PeriodicExecutor extends ExecutorBase {

    private long cycleTime
    private long initialDelay
    private TimeUnit timeUnit
    private int instanceId
    final Runnable task

    PeriodicExecutor(Runnable task, long cycleTime, TimeUnit timeUnit, long initialDelay = 0) {
        this.task = task
        this.cycleTime = cycleTime
        this.timeUnit = timeUnit
        this.initialDelay = initialDelay
    }

    def start() {
        taskHandle = executor.scheduleAtFixedRate(task, initialDelay, cycleTime, timeUnit)
        instanceId = instanceCount
    }

}

class TimedExecutor extends ExecutorBase {

    TimedExecutor(TimerTask task, DateTime runAt) {
        long delay = new Duration(DateTime.now(), runAt).standardSeconds
        taskHandle = executor.schedule(task, delay, TimeUnit.SECONDS)
    }
}
