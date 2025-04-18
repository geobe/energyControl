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
import de.geobe.energy.recording.LogMessageRecorder
import de.geobe.energy.recording.PowerCommunicationRecorder
import de.geobe.energy.automation.PowerMonitor
import de.geobe.energy.automation.PowerPriceMonitor
import de.geobe.energy.automation.WallboxMonitor
import io.pebbletemplates.pebble.PebbleEngine

import static spark.Spark.*

class EnergyControlUI {

    static ValueController valueController

    static void main(String[] args) {


        PebbleEngine engine = new PebbleEngine.Builder().build()

//        ValueController
        try {
            valueController = new ValueController(engine)
            valueController.init()

            PowerCommunicationRecorder.recorder
        } catch (Exception exception) {
            LogMessageRecorder.logStackTrace('startup', exception)
            def term = new sun.misc.Signal('TERM').number
            failed(128 + term)
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            void run() {
                println "shutting down gently ..."
                shutdown()
                Thread.sleep 1000
                println 'done'
            }
        })

        PowerCommunicationRecorder.logMessage('energy control service started')

        staticFiles.location("public")
        staticFiles.expireTime(10)

        webSocket('/dash', valueController)

        get('/', valueController.indexRoute)

        get('/wallboxStrategy', valueController.wallboxStrategyRoute)

        post('/wallboxStrategy/:action', valueController.wallboxStrategyPost)

        get('/dashboard', valueController.dashboardRoute)

        post('/settings', valueController.energySettingsPost)

//        get('/graph', valueController.graphRoute)

        post('bufCtlCmd/:action/v/:value', valueController.storagePost)

        post('bufCtlHour/:action', valueController.storagePost)

        post('/graph', valueController.graphPost)

        post('/graphData', valueController.graphDataPost)

        post('/graphUpdate', valueController.graphUpdatePost)

        post('/language', valueController.languagePost)

        post('/stop') { req, res ->
            valueController.showStopServer()
            exit()
        }
    }

    /**
     * Method will be automatically called when program is stopped by
     * <ul>
     *     <li> call to System.exit()</li>
     *     <li>sig_kill (^C)</li>
     *     <li>stopping or restarting service if program runs as a service</li>
     * </ul>
     */
    static void shutdown() {
        CarChargingManager.carChargingManager.shutDown()
//        PowerCommunicationRecorder.stopRecorder()
        WallboxMonitor.monitor.shutdown()
        PowerMonitor.monitor?.shutdown()
        PowerPriceMonitor.monitor?.shutdown()
        valueController?.shutdown()
        PeriodicExecutor?.shutdown()
    }

    /**
     * exit without restart by systemctl demon.
     */
    static void exit() {
        PowerCommunicationRecorder.logMessage("shut down with System.exit(0)")
        stop()
        System.exit(0)
    }

    /**
     * Restart is configured in systemd.service control file in [Service] section.
     *  To prevent restart in case of fatal errors, exit status with SIG_TERM (exit(143))
     *  is configured as success:<ul>
     *     <li>SuccessExitStatus=143 # don't try to resatrt after fatal errors</li>
     *     <li>Restart=on-failure   # exit-code != 0, ...</li>
     *     <li>RestartSec=120       # wait 120 seconds</li>
     *</ul>
     */
    static void failed(int status = 1) {
        PowerCommunicationRecorder.logMessage("request restart with System.exit($status), if not fatal (143)")
        stop()
        System.exit(status)
    }
}
