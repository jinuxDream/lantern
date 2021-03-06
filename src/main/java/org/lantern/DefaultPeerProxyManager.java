package org.lantern;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.group.ChannelGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Class the keeps track of P2P connections to peers, dispatching them and
 * creating new ones as needed.
 */
public class DefaultPeerProxyManager implements PeerProxyManager {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private final Executor exec = Executors.newCachedThreadPool(
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat(
            "P2P-Socket-Creation-Thread-%d").build());
    
    /**
     * Priority queue of sockets ordered by how long it took them to be 
     * established.
     * 
     * Package-access for easier testing.
     */
    final PriorityBlockingQueue<ConnectionTimeSocket> timedSockets = 
        new PriorityBlockingQueue<ConnectionTimeSocket>(40, 
            new Comparator<ConnectionTimeSocket>() {

            @Override
            public int compare(final ConnectionTimeSocket cts1, 
                final ConnectionTimeSocket cts2) {
                return cts1.elapsed.compareTo(cts2.elapsed);
            }
        });

    private final boolean anon;
    
    /**
     * Online peers we've exchanged certs with.
     */
    private final Collection<URI> certPeers = new HashSet<URI>();

    private final ChannelGroup channelGroup;
    
    public DefaultPeerProxyManager(final boolean anon, 
        final ChannelGroup channelGroup) {
        this.anon = anon;
        this.channelGroup = channelGroup;
    }

    @Override
    public HttpRequestProcessor processRequest(
        final Channel browserToProxyChannel, final ChannelHandlerContext ctx, 
        final MessageEvent me) throws IOException {
        log.debug("Processing request...sockets in queue {} on this {}", 
            this.timedSockets.size(), this);
        
        final ConnectionTimeSocket cts;
        try {
            cts = selectSocket();
        } catch (final IOException e) {
            // This means there's no socket available.
            return null;
        }
        if (!cts.requestProcessor.processRequest(browserToProxyChannel, ctx, me)) {
            log.info("Peer could not process the request...");
            // We return null here because that's how the dispatcher knows of
            // failures on peers.
            
            // TODO: We could also move on to other peers in this case instead
            // of falling back to centralized nodes.
            return null;
        }
        
        // When we use sockets we replace them.
        final int socketsToFetch;
        if (this.timedSockets.size() > 20) {
            socketsToFetch = 0;
        } else if (this.timedSockets.size() > 10) {
            socketsToFetch = 1;
        } else if (this.timedSockets.size() > 4) {
            socketsToFetch = 2;
        } else {
            socketsToFetch = 3;
        }
        onPeer(cts.peerUri, socketsToFetch);
        return cts.requestProcessor;
    }

    private ConnectionTimeSocket selectSocket() throws IOException {
        pruneSockets();
        if (this.timedSockets.isEmpty()) {
            // Try to create some more sockets using peers we've learned about.
            for (final URI peer : certPeers) {
                onPeer(peer, 2);
            }
        }
        // This removes the highest priority socket.
        for (int i = 0; i < this.timedSockets.size(); i++) {
            final ConnectionTimeSocket cts;
            try {
                cts = this.timedSockets.poll(20, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                log.info("Interrupted?", e);
                return null;
            }
            if (cts == null) {
                log.info("No peer sockets available!! TRUSTED: "+!anon);
                return null;
            }
            final Socket s = cts.sock;
            if (s != null) {
                if (!s.isClosed()) {
                    log.info("Found connected socket!");
                    return cts;
                }
            }
        }
        
        log.info("Could not find connected socket");
        throw new IOException("No availabe connected sockets in "+
            this.timedSockets);
    }
    

    private void pruneSockets() {
        final Iterator<ConnectionTimeSocket> iter = this.timedSockets.iterator();
        while (iter.hasNext()) {
            final ConnectionTimeSocket cts = iter.next();
            if (cts.sock != null) {
                if (cts.sock.isClosed()) {
                    iter.remove();
                }
            }
        }
    }

    @Override
    public void onPeer(final URI peerUri) {
        onPeer(peerUri, 6);
    }

    private void onPeer(final URI peerUri, final int sockets) {
        if (!LanternHub.settings().isGetMode()) {
            log.info("Ingoring peer when we're in give mode");
            return;
        }
        if (this.anon && !LanternHub.settings().isUseAnonymousPeers()) {
            log.info("Ignoring anonymous peer");
            return;
        }
        if (!this.anon && !LanternHub.settings().isUseTrustedPeers()) {
            log.info("Ignoring trusted peer");
            return;
        }
        log.info("Received peer URI {}...attempting {} connections...", 
            peerUri, sockets);
        
        certPeers.add(peerUri);
        // Unclear how this count will be used for now.
        final Map<URI, AtomicInteger> peerFailureCount = 
            new HashMap<URI, AtomicInteger>();
        exec.execute(new Runnable() {
            @Override
            public void run() {
                boolean gotConnected = false;
                try {
                    // We open a number of sockets because in almost every
                    // scenario the browser makes many connections to the proxy
                    // to open a single page.
                    for (int i = 0; i < sockets; i++) {
                        final ConnectionTimeSocket ts = 
                            new ConnectionTimeSocket(peerUri);

                        final Socket sock = LanternUtils.openOutgoingPeerSocket(
                            peerUri, LanternHub.xmppHandler().getP2PClient(), 
                            peerFailureCount);
                        log.info("Got socket and adding it for peer: {}", peerUri);
                        ts.onSocket(sock);
                        timedSockets.add(ts);
                        if (!gotConnected) {
                            LanternHub.eventBus().post(
                                    new ConnectivityStatusChangeEvent(
                                        ConnectivityStatus.CONNECTED));
                        }
                        gotConnected = true;
                    }
                } catch (final IOException e) {
                    log.info("Could not create peer socket", e);
                }                
            }
        });
    }

    /**
     * Class holding a socket and an HTTP request processor that also tracks
     * connection times.
     * 
     * Package-access for easier testing.
     */
    final class ConnectionTimeSocket {
        private final long startTime = System.currentTimeMillis();
        Long elapsed;
        
        /**
         * We only store the peer URI so we can create a new connection to the
         * peer when this one succeeds.
         */
        private final URI peerUri;
        private HttpRequestProcessor requestProcessor;
        private Socket sock;
        
        public ConnectionTimeSocket(final URI peerUri) {
            this.peerUri = peerUri;
        }

        private void onSocket(final Socket socket) {
            this.elapsed = System.currentTimeMillis() - this.startTime;
            this.sock = socket;
            if (anon) {
                this.requestProcessor = 
                    new PeerHttpConnectRequestProcessor(sock, channelGroup);
            } else {
                this.requestProcessor = 
                    new PeerChannelHttpRequestProcessor(sock, channelGroup);
                    //new PeerHttpRequestProcessor(sock);
            }
        }
    }

    @Override
    public void removePeer(final URI uri) {
        this.certPeers.remove(uri);
    }
    
    @Override
    public void closeAll() {
        for (final ConnectionTimeSocket sock : this.timedSockets) {
            sock.requestProcessor.close();
        }
    }
    
    @Override
    public String toString() {
        return getClass().getSimpleName()+"-"+hashCode()+" anon: "+anon;
    }

}
