package hshttpd.controller

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.util.SelfSignedCertificate
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

class HttpServer {

    // default logger
    static logger = LoggerFactory.getLogger HttpServer.class

    // comm channel
    private Channel ch

    // server memory - where the web pages will be stored
    static memory = new ConcurrentHashMap<String, byte[]>()
    static contentType = new ConcurrentHashMap<String, String>()
    static lastModified = new ConcurrentHashMap<String, Long>()

    // application settings
    static settings

    // server thread objects
    static HttpServer server
    static Thread serverThread

    // constants
    final boolean SSL = System.getProperty("ssl") != null
    final int PORT = Integer.parseInt System.getProperty("port", SSL? "8443" : "8080")

    HttpServer(settings) {
        HttpServer.settings = settings

        loadFiles new File(settings.httpd.root.text() as String)
    }

    static reload() {

        if(!HttpServer.server) {
            logger.warn "Server nor started. Ignoring command."
            return
        }

        // clear current memory
        HttpServer.memory.clear()
        HttpServer.contentType.clear()
        HttpServer.lastModified.clear()

        // reload files into memory
        HttpServer.server.loadFiles new File(settings.httpd.root.text() as String)
        logger.info "Files reloaded into memory."
    }

    def loadFiles(File file) {

        if(file.isDirectory()) {
            file.listFiles().each { f-> loadFiles(f)}
            return
        }

        if(!file.isFile() || file.size() == 0 || !file.exists()) {
            return
        }

        def key = (file.path as String) - (settings.httpd.root.text() as String)

        def mime = URLConnection.guessContentTypeFromName file.name

        HttpServer.memory[key] = file.bytes
        HttpServer.contentType[key] =  mime? mime: "text/html"
        HttpServer.lastModified[key] = file.lastModified()
    }

    def start() {

        // Configure SSL.
        final SslContext sslCtx

        if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate()
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).sslProvider(SslProvider.JDK).build()
        }
        else {
            sslCtx = null
        }

        EventLoopGroup bossGroup = new NioEventLoopGroup(1)
        EventLoopGroup workerGroup = new NioEventLoopGroup()

        try {
            ServerBootstrap b = new ServerBootstrap()

            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new MemoryServerInitializer(sslCtx))

            ch = b.bind(PORT).sync().channel()

            System.err.println("Open your web browser and navigate to " + (SSL? "https" : "http") + "://localhost:" + PORT + '/')

            ch.closeFuture().sync()

            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        }
        finally {
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        }
    }

    def stop() {

        ch.flush()
        def c = ch.closeFuture()
        c.await(1000)

        if(ch.isOpen()||ch.isActive()) {
            ch.close()
        }
        ch.finalize()
    }

    static startServer (settings) {

        server = new HttpServer(settings)

        serverThread = Thread.start {
            server.start()
            logger.info "Server shutdown nicely."
        }

        logger.info "Server started and listening on port: ${HttpServer.server.PORT}"
    }

    static stopServer() {

        server.stop()
        server = null

        serverThread.join(1000)

        if(serverThread && serverThread.isAlive()) {
            serverThread.interrupt()
        }

        serverThread = null
    }
}
