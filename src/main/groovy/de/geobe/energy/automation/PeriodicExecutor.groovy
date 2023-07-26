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

package de.geobe.energy.automation

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PeriodicExecutor {

    private static ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(5)

    private long cycleTime
    private TimeUnit timeUnit
    final Runnable task
    private ScheduledFuture taskHandle
    private static int instanceCount = 0
    private int instanceId

    PeriodicExecutor(Runnable task, long cycleTime, TimeUnit timeUnit) {
        this.task = task
        this.cycleTime = cycleTime
        this.timeUnit = timeUnit
    }

    def start() {
        taskHandle = executor.scheduleAtFixedRate(task, 0, cycleTime, timeUnit)
        instanceId = instanceCount
    }

    def stop() {
        taskHandle?.cancel(false)
    }

    static shutdown() {
        executor.shutdown()
    }

}
