package hshttpd.controller

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.ssl.SslContext
import io.netty.handler.stream.ChunkedWriteHandler

class HttpStaticFileServerInitializer extends ChannelInitializer<SocketChannel> {

    private final SslContext sslContext

    HttpStaticFileServerInitializer(SslContext sslContext) {

        // keep references inside
        this.sslContext = sslContext
    }

    @Override
    void initChannel(SocketChannel ch) {

        def pipeline = ch.pipeline()

        if (sslContext) {
            pipeline.addLast sslContext.newHandler(ch.alloc())
        }

        pipeline.addLast new HttpServerCodec()
        pipeline.addLast new HttpObjectAggregator(65536)
        pipeline.addLast new ChunkedWriteHandler()
        pipeline.addLast new HttpStaticFileServerHandler()
    }
}
