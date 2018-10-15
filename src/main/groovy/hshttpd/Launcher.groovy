/**
 * hshttpd - High speed http daemon
 *
 * @author Sylvio Ximenez de Azevedo Neto <sylvioazevedo@gmail.com>
 * @date 09/10/2018
 */
package hshttpd

import hshttpd.controller.HttpServer
import hshttpd.domain.Constants
import org.slf4j.LoggerFactory


// set log4j configuration file
System.setProperty("log4j.configurationFile", Constants.LOG_CONFIG_PATH)

// get logger instance
def logger = LoggerFactory.getLogger(Launcher.class.name)

// load application configuration settings
def settings = null

try {
    settings = new XmlSlurper().parse new File(Constants.APP_SETTINGS_PATH)
}
catch (IOException ioe) {
    logger.error "Unable to load application configuration file: ${ioe.message}"
}


println "${settings.appName.text()} - ${settings.version.text()}"

// start http server
HttpServer.startServer settings

// create command reading loop
while(true) {

    def cmd = System.in.newReader().readLine()

    switch(cmd.trim().toLowerCase()) {
        case "exit":
            HttpServer.stopServer()
            System.exit(0)
            break

        case "restart":
            HttpServer.stopServer()
            HttpServer.startServer settings
            break

        case "stop":
            HttpServer.stopServer()
            break

        case "start":
            HttpServer.startServer settings
            break

        case "reload":
            HttpServer.reload()
            break

        default:
            println "Command not recongnized: ${cmd}"
    }
}