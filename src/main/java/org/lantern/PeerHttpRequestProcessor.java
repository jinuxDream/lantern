package org.lantern;

import static org.jboss.netty.buffer.ChannelBuffers.copiedBuffer;
import static org.jboss.netty.buffer.ChannelBuffers.wrappedBuffer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.IOUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.util.CharsetUtil;
import org.lastbamboo.common.p2p.P2PClient;
import org.littleshoot.util.ByteBufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PeerHttpRequestProcessor implements HttpRequestProcessor {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    //space ' '
    static final byte SP = 32;
    
    /**
     * Colon ':'
     */
     static final byte COLON = 58;
    
    /**
     * Carriage return
     */
    static final byte CR = 13;

    /**
     * Equals '='
     */
    static final byte EQUALS = 61;

    /**
     * Line feed character
     */
    static final byte LF = 10;

    /**
     * carriage return line feed
     */
    static final byte[] CRLF = new byte[] { CR, LF };
    
    private static final ChannelBuffer LAST_CHUNK =
        copiedBuffer("0\r\n\r\n", CharsetUtil.US_ASCII);
    
    private URI proxyAddress;
    private final ProxyStatusListener proxyStatusListener;
    private final P2PClient p2pClient;
    
    private Socket socket;
    
    /**
     * Map recording the number of consecutive connection failures for a
     * given peer. Note that a successful connection will reset this count
     * back to zero.
     */
    private static Map<URI, AtomicInteger> peerFailureCount =
        new ConcurrentHashMap<URI, AtomicInteger>();

    private final Proxy proxy;

    private boolean chunked;

    public PeerHttpRequestProcessor(final Proxy proxy, 
        final ProxyStatusListener proxyStatusListener,
        final P2PClient p2pClient){
        this.proxy = proxy;
        this.proxyStatusListener = proxyStatusListener;
        this.p2pClient = p2pClient;
    }

    public boolean hasProxy() {
        if (this.proxyAddress != null) {
            return true;
        }
        this.proxyAddress = this.proxy.getPeerProxy();
        if (this.proxyAddress != null) {
            return true;
        }
        return false;
    }
    
    /**
     * We subclass here purely to expose the encoding method of the built-in
     * request encoder.
     */
    private static final class RequestEncoder extends HttpRequestEncoder {
        private ChannelBuffer encode(final HttpRequest request, 
            final Channel ch) throws Exception {
            return (ChannelBuffer) super.encode(null, ch, request);
        }
    }

    public void processRequest(final Channel browserToProxyChannel,
        final ChannelHandlerContext ctx, final MessageEvent me) 
        throws IOException {
        if (this.socket == null) {
            this.socket = LanternUtils.openOutgoingPeerSocket(
                browserToProxyChannel, this.proxyAddress, ctx, 
                this.proxyStatusListener, this.p2pClient, peerFailureCount);
        }
        // We need to convert the Netty message to raw bytes for sending over
        // the socket.
        final RequestEncoder encoder = new RequestEncoder();
        final HttpRequest request = (HttpRequest) me.getMessage();
        this.chunked = LanternUtils.isTransferEncodingChunked(request);
        final ChannelBuffer cb;
        try {
            cb = encoder.encode(request, ctx.getChannel());
        } catch (final Exception e) {
            log.error("Could not encode request?", e);
            return;
        }
        
        final ByteBuffer buf = cb.toByteBuffer();
        final byte[] data = ByteBufferUtils.toRawBytes(buf);
        try {
            log.info("Writing {}", new String(data));
            final OutputStream os = this.socket.getOutputStream();
            os.write(data);
        } catch (final IOException e) {
            // They probably just closed the connection, as they will in
            // many cases.
            //this.proxyStatusListener.onError(this.peerUri);
        }
    }

    public void processChunk(final ChannelHandlerContext ctx, 
        final MessageEvent me) throws IOException {
        // We need to convert the Netty message to raw bytes for sending over
        // the socket.
        final HttpChunk chunk = (HttpChunk) me.getMessage();
        final ChannelBuffer cb = encodeChunk(chunk);
        if (cb == null) {
            return;
        }
        
        final ByteBuffer buf = cb.toByteBuffer();
        final byte[] data = ByteBufferUtils.toRawBytes(buf);
        log.info("Writing {}", new String(data));
        final OutputStream os = this.socket.getOutputStream();
        os.write(data);
    }
    
    private ChannelBuffer encodeChunk(final HttpChunk chunk) {
        if (chunked) {
            if (chunk.isLast()) {
                // We create new chunk writers every time, so we don't need to 
                // reset the chunk flag.
                //chunked = false;
                if (chunk instanceof HttpChunkTrailer) {
                    ChannelBuffer trailer = ChannelBuffers.dynamicBuffer();
                    trailer.writeByte((byte) '0');
                    trailer.writeByte(CR);
                    trailer.writeByte(LF);
                    encodeTrailingHeaders(trailer, (HttpChunkTrailer) chunk);
                    trailer.writeByte(CR);
                    trailer.writeByte(LF);
                    return trailer;
                } else {
                    return LAST_CHUNK.duplicate();
                }
            } else {
                ChannelBuffer content = chunk.getContent();
                int contentLength = content.readableBytes();

                return wrappedBuffer(
                        copiedBuffer(
                                Integer.toHexString(contentLength),
                                CharsetUtil.US_ASCII),
                        wrappedBuffer(CRLF),
                        content.slice(content.readerIndex(), contentLength),
                        wrappedBuffer(CRLF));
            }
        } else {
            if (chunk.isLast()) {
                return null;
            } else {
                return chunk.getContent();
            }
        }
    }
    
    private void encodeTrailingHeaders(ChannelBuffer buf, HttpChunkTrailer trailer) {
        try {
            for (Map.Entry<String, String> h: trailer.getHeaders()) {
                encodeHeader(buf, h.getKey(), h.getValue());
            }
        } catch (UnsupportedEncodingException e) {
            throw (Error) new Error().initCause(e);
        }
    }

    private void encodeHeader(ChannelBuffer buf, String header, String value)
            throws UnsupportedEncodingException {
        buf.writeBytes(header.getBytes("ASCII"));
        buf.writeByte(COLON);
        buf.writeByte(SP);
        buf.writeBytes(value.getBytes("ASCII"));
        buf.writeByte(CR);
        buf.writeByte(LF);
    }

    public void close() {
        IOUtils.closeQuietly(this.socket);
    }
}