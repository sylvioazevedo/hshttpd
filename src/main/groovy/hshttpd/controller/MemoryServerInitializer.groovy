package hshttpd.controller

import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec

import io.netty.handler.stream.ChunkedWriteHandler

class MemoryServerInitializer extends ChannelInitializer<SocketChannel> {

    private sslContext

    MemoryServerInitializer(sslContext) {

        // keep references inside
        this.sslContext = sslContext
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {

        def pipeline = ch.pipeline()

        if (sslContext) {
            pipeline.addLast (sslContext as ChannelHandler).newHandler(ch.alloc())
        }

        pipeline.addLast new HttpServerCodec()
        pipeline.addLast new HttpObjectAggregator(65536)
        pipeline.addLast new ChunkedWriteHandler()
        pipeline.addLast new MemoryServerHandler()
    }
}