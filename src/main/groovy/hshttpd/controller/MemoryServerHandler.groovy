package hshttpd.controller

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelProgressiveFuture
import io.netty.channel.ChannelProgressiveFutureListener
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpChunkedInput
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMessage
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.ssl.SslHandler
import io.netty.handler.stream.ChunkedInput
import io.netty.handler.stream.ChunkedStream
import io.netty.util.CharsetUtil

import java.text.SimpleDateFormat
import java.util.regex.Pattern

import static io.netty.handler.codec.http.HttpMethod.GET
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN
import static io.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED
import static io.netty.handler.codec.http.HttpResponseStatus.OK
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1

class MemoryServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    // constants
    private static final Pattern INSECURE_URI = Pattern.compile(".*[<>&\"].*")

    private static final HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz"
    static final HTTP_DATE_GMT_TIMEZONE = "GMT"
    static final HTTP_CACHE_SECONDS = 60

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {

        if (!request.decoderResult().isSuccess()) {
            sendError ctx, BAD_REQUEST
            return
        }

        if (request.method() != GET) {
            sendError ctx, METHOD_NOT_ALLOWED
            return
        }

        // get uri and sanitize it
        final String uri = request.uri()
        final String path = sanitizeUri(uri)

        if (!path) {
            sendError ctx, FORBIDDEN
            return
        }

        // Cache Validation
        String ifModifiedSince = request.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE)

        if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {

            def dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US)
            Date ifModifiedSinceDate = dateFormatter.parse(ifModifiedSince)

            // Only compare up to the second because the datetime format we send to the client
            // does not have milliseconds
            long ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime() / 1000 as long
            long fileLastModifiedSeconds = HttpServer.lastModified[path] / 1000 as long

            if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
                sendNotModified(ctx)
                return
            }
        }

        sendPage ctx, request, path
    }

    private static sendPage(ctx, request, path) {

        byte[] page = HttpServer.memory[path as String]

        if (!page) {
            sendError ctx as ChannelHandlerContext, NOT_FOUND as HttpResponseStatus
            return
        }

        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK)
        HttpUtil.setContentLength response, page.size()

        setContentTypeHeader response, HttpServer.contentType[path as String] as String
        setDateAndCacheHeaders response, HttpServer.lastModified[path as String]

        if (HttpUtil.isKeepAlive(request as FullHttpRequest)) {
            response.headers().set HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE
        }

        // Write the initial line and the header.
        ctx.write response

        // Write the content.
        ChannelFuture sendFileFuture
        ChannelFuture lastContentFuture

        if (!ctx.pipeline().get(SslHandler.class)) {
            sendFileFuture = ctx.write(Unpooled.wrappedBuffer(page), ctx.newProgressivePromise())
            // Write the end marker.
            lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
        }
        else {
            sendFileFuture = ctx.writeAndFlush new HttpChunkedInput(new ChunkedStream(new ByteArrayInputStream(page)) as ChunkedInput<ByteBuf>, ctx.newProgressivePromise() as LastHttpContent)

            // HttpChunkedInput will write the end marker (LastHttpContent) for us.
            lastContentFuture = sendFileFuture
        }

        sendFileFuture.addListener(new ChannelProgressiveFutureListener() {

            @Override
            void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {

                if (total < 0) { // total unknown
                    System.err.println(future.channel().toString() + " Transfer progress: ${progress}")
                }
                else {
                    System.err.println(future.channel().toString() + " Transfer progress: ${progress}/${total}")
                }
            }

            @Override
            void operationComplete(ChannelProgressiveFuture future) {
                System.err.println(future.channel().toString() + " Transfer complete.")
            }
        })

        // Decide whether to close the connection or not.
        if (!HttpUtil.isKeepAlive(request as HttpMessage)) {
            // Close the connection when the whole content is written out.
            lastContentFuture.addListener(ChannelFutureListener.CLOSE)
        }
    }

    private static sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {

        def response = new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.copiedBuffer("Failure: ${status}\r\n", CharsetUtil.UTF_8))
        response.headers().set HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8"

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener ChannelFutureListener.CLOSE
    }

    private static String sanitizeUri(String uri) {

        def decoded

        // Decode the path.
        try {
            decoded = URLDecoder.decode(uri, "UTF-8")
        }
        catch (UnsupportedEncodingException e) {
            throw new Error(e)
        }

        if (decoded.isEmpty() || decoded[0] != '/') {
            return null
        }

        // Convert file separators.
        decoded = decoded.replace('/', "${File.separatorChar}")

        // Simplistic dumb security check.
        // You will have to do something serious in the production environment.
        if (decoded.contains(File.separator + '.') ||
                decoded.contains('.' + File.separator) ||
                decoded[0] == '.' || decoded[decoded.length() - 1] == '.' ||
                INSECURE_URI.matcher(decoded).matches()) {
            return null
        }

        if(decoded.indexOf("?")!=-1) {
            decoded = decoded[0..decoded.indexOf("?")-1]
        }

        if(decoded.indexOf("@2x")) {
            decoded = decoded.replaceAll("@2x", "")
        }

        // Convert to absolute path.
        decoded == "${File.separatorChar}"? "${File.separatorChar}index.html" : decoded
    }

    /**
     * Sets the content type header for the HTTP Response
     *
     * @param response HTTP response
     * @param file file to extract content type
     */
    private static void setContentTypeHeader(HttpResponse response, String type) {
        response.headers().set HttpHeaderNames.CONTENT_TYPE, type
    }

    /**
     * When file timestamp is the same as what the browser is sending up, send a "304 Not Modified"
     *
     * @param ctx Context
     */
    private static void sendNotModified(ChannelHandlerContext ctx) {

        def response = new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED)

        setDateHeader(response)

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener ChannelFutureListener.CLOSE
    }

    /**
     * Sets the Date header for the HTTP response
     *
     * @param response
     *            HTTP response
     */
    private static void setDateHeader(FullHttpResponse response) {
        SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US)
        dateFormatter.setTimeZone TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE)

        Calendar time = new GregorianCalendar()
        response.headers().set HttpHeaderNames.DATE, dateFormatter.format(time.time)
    }

    /**
     * Sets the Date and Cache headers for the HTTP Response
     *
     * @param response
     *            HTTP response
     * @param fileToCache
     *            file to extract content type
     */
    private static void setDateAndCacheHeaders(HttpResponse response, long lastModified) {

        SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US)
        dateFormatter.setTimeZone TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE)

        // Date header
        Calendar time = new GregorianCalendar()
        response.headers().set HttpHeaderNames.DATE, dateFormatter.format(time.getTime())

        // Add cache headers
        time.add Calendar.SECOND, HTTP_CACHE_SECONDS
        response.headers().set HttpHeaderNames.EXPIRES, dateFormatter.format(time.getTime())
        response.headers().set HttpHeaderNames.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS
        response.headers().set HttpHeaderNames.LAST_MODIFIED, dateFormatter.format(new Date(lastModified))
    }
}
