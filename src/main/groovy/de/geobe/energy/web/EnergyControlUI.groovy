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

package de.geobe.energy.web

import de.geobe.energy.automation.CarChargingManager
import de.geobe.energy.automation.PeriodicExecutor
import de.geobe.energy.automation.PowerMonitor
import de.geobe.energy.automation.WallboxMonitor
import io.pebbletemplates.pebble.PebbleEngine

import static spark.Spark.*

class EnergyControlUI {

    static ValueController valueController

    static void main(String[] args) {

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                println "shutting down gently ..."
                shutdown()
                Thread.sleep 1000
                println 'done'
            }
        })

        PebbleEngine engine = new PebbleEngine.Builder().build()

//        ValueController
        valueController = new ValueController(engine)
        valueController.init()

        staticFiles.location("public")
        staticFiles.expireTime(1)

        webSocket('/dash', valueController)

        get('/', valueController.indexRoute)

        get('/wallboxStrategy', valueController.wallboxStrategyRoute)

        post('/wallboxStrategy/:action', valueController.wallboxStrategyPost)

        get('/dashboard', valueController.dashboardRoute)

        post('/settings', valueController.energySettingsPost)

//        get('/graph', valueController.graphRoute)

        post('/graph', valueController.graphPostRoute)

        post('/stop') { req, res ->
            stop();
            System.exit(0)
        }
    }

    private static shutdown() {
        CarChargingManager.carChargingManager.shutDown()
        WallboxMonitor.monitor.shutdown()
        PowerMonitor.monitor.shutdown()
        valueController?.shutdown()
        PeriodicExecutor.shutdown()
    }
}
