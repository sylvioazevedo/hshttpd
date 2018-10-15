package hshttpd.controller

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelProgressiveFuture
import io.netty.channel.ChannelProgressiveFutureListener
import io.netty.channel.DefaultFileRegion
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpChunkedInput
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.ssl.SslHandler
import io.netty.handler.stream.ChunkedFile
import io.netty.util.CharsetUtil
import io.netty.util.internal.SystemPropertyUtil

import javax.activation.MimetypesFileTypeMap
import java.text.SimpleDateFormat
import java.util.regex.Pattern

import static io.netty.handler.codec.http.HttpMethod.*
import static io.netty.handler.codec.http.HttpResponseStatus.*
import static io.netty.handler.codec.http.HttpVersion.*

class HttpStaticFileServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    static final HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz"
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

        final String uri = request.uri()
        final String path = sanitizeUri(uri)

        if (!path) {
            sendError ctx, FORBIDDEN
            return
        }

        File file = new File(path)
        if (file.isHidden() || !file.exists()) {
            sendError ctx, NOT_FOUND
            return
        }

        if (file.isDirectory()) {
            if (uri.endsWith("/")) {
                sendListing(ctx, file, uri)
            }
            else {
                sendRedirect(ctx, uri + '/')
            }
            return
        }

        if (!file.isFile()) {
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
            long fileLastModifiedSeconds = file.lastModified() / 1000 as long

            if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
                sendNotModified(ctx)
                return
            }
        }

        RandomAccessFile raf
        try {
            raf = new RandomAccessFile(file, "r")
        }
        catch (FileNotFoundException ignore) {
            sendError(ctx, NOT_FOUND)
            return
        }

        long fileLength = raf.length()

        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK)
        HttpUtil.setContentLength response, fileLength

        setContentTypeHeader response, file
        setDateAndCacheHeaders response, file

        if (HttpUtil.isKeepAlive(request)) {
            response.headers().set HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE
        }

        // Write the initial line and the header.
        ctx.write response

        // Write the content.
        ChannelFuture sendFileFuture
        ChannelFuture lastContentFuture

        if (!ctx.pipeline().get(SslHandler.class)) {
            sendFileFuture = ctx.write(new DefaultFileRegion(raf.getChannel(), 0, fileLength), ctx.newProgressivePromise())
            // Write the end marker.
            lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
        }
        else {
            sendFileFuture = ctx.writeAndFlush new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)), ctx.newProgressivePromise()

            // HttpChunkedInput will write the end marker (LastHttpContent) for us.
            lastContentFuture = sendFileFuture
        }

        sendFileFuture.addListener(new ChannelProgressiveFutureListener() {

            @Override
            void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {

                if (total < 0) { // total unknown
                    System.err.println(future.channel() + " Transfer progress: " + progress)
                }
                else {
                    System.err.println(future.channel() + " Transfer progress: " + progress + " / " + total)
                }
            }

            @Override
            void operationComplete(ChannelProgressiveFuture future) {
                System.err.println(future.channel() + " Transfer complete.")
            }
        })

        // Decide whether to close the connection or not.
        if (!HttpUtil.isKeepAlive(request)) {
            // Close the connection when the whole content is written out.
            lastContentFuture.addListener(ChannelFutureListener.CLOSE)
        }

    }

    @Override
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {

        cause.printStackTrace()

        if (ctx.channel().isActive()) {
            sendError ctx, INTERNAL_SERVER_ERROR
        }
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {

        def response = new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.copiedBuffer("Failure: ${status}\r\n", CharsetUtil.UTF_8))
        response.headers().set HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8"

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener ChannelFutureListener.CLOSE
    }

    /**
     * Sets the content type header for the HTTP Response
     *
     * @param response HTTP response
     * @param file file to extract content type
     */
    private static void setContentTypeHeader(HttpResponse response, File file) {

        response.headers().set HttpHeaderNames.CONTENT_TYPE, new MimetypesFileTypeMap().getContentType(file.path)
    }

    private static final Pattern INSECURE_URI = Pattern.compile(".*[<>&\"].*")


    private static String sanitizeUri(String uri) {

        // Decode the path.
        try {
            uri = URLDecoder.decode(uri, "UTF-8")
        }
        catch (UnsupportedEncodingException e) {
            throw new Error(e)
        }

        if (uri.isEmpty() || uri.charAt(0) != '/') {
            return null
        }

        // Convert file separators.
        uri = uri.replace('/', "${File.separatorChar}")

        // Simplistic dumb security check.
        // You will have to do something serious in the production environment.
        if (uri.contains(File.separator + '.') ||
                    uri.contains('.' + File.separator) ||
                    uri.charAt(0) == '.' || uri.charAt(uri.length() - 1) == '.' ||
                    INSECURE_URI.matcher(uri).matches()) {
                return null
            }

        // Convert to absolute path.
        SystemPropertyUtil.get("user.dir") + File.separator + uri
    }

    private static final Pattern ALLOWED_FILE_NAME = Pattern.compile("[^-\\._]?[^<>&\\\"]*")

    private static void sendListing(ChannelHandlerContext ctx, File dir, String dirPath) {

        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8")

        StringBuilder buf = new StringBuilder()
            .append("<!DOCTYPE html>\r\n")
            .append("<html><head><meta charset='utf-8' /><title>")
            .append("Listing of: ")
            .append(dirPath)
            .append("</title></head><body>\r\n")

            .append("<h3>Listing of: ")
            .append(dirPath)
            .append("</h3>\r\n")

            .append("<ul>")
            .append("<li><a href=\"../\">..</a></li>\r\n")

        for (File f: dir.listFiles()) {

            if (f.isHidden() || !f.canRead()) {
               continue
            }

            if (!ALLOWED_FILE_NAME.matcher(f.name).matches()) {
               continue
           }

           buf.append("<li><a href=\"")
              .append(f.name)
              .append("\">")
              .append(f.name)
              .append("</a></li>\r\n")
       }

        buf.append("</ul></body></html>\r\n")
        ByteBuf buffer = Unpooled.copiedBuffer buf, CharsetUtil.UTF_8
        response.content().writeBytes buffer
        buffer.release()

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener ChannelFutureListener.CLOSE
    }

    private static void sendRedirect(ChannelHandlerContext ctx, String newUri) {

        def response = new DefaultFullHttpResponse(HTTP_1_1, FOUND)
        response.headers().set HttpHeaderNames.LOCATION, newUri

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener ChannelFutureListener.CLOSE
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
    private static void setDateAndCacheHeaders(HttpResponse response, File fileToCache) {

        SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US)
        dateFormatter.setTimeZone TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE)

        // Date header
        Calendar time = new GregorianCalendar()
        response.headers().set HttpHeaderNames.DATE, dateFormatter.format(time.getTime())

        // Add cache headers
        time.add Calendar.SECOND, HTTP_CACHE_SECONDS
        response.headers().set HttpHeaderNames.EXPIRES, dateFormatter.format(time.getTime())
        response.headers().set HttpHeaderNames.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS
        response.headers().set HttpHeaderNames.LAST_MODIFIED, dateFormatter.format(new Date(fileToCache.lastModified()))
    }
}
