/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.tomcat.util.net;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.security.KeyStore;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SecureNioChannel.ApplicationBufferHandler;
import org.apache.tomcat.util.net.jsse.JSSESocketFactory;
import org.apache.tomcat.util.net.jsse.NioX509KeyManager;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 * @author Filip Hanik
 */
public class NioEndpoint extends AbstractEndpoint {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    public static final int OP_REGISTER = 0x100; //register interest op
    public static final int OP_CALLBACK = 0x200; //callback interest op
    
    // ----------------------------------------------------------------- Fields

    protected NioSelectorPool selectorPool = new NioSelectorPool();
    
    /**
     * Server socket "pointer".
     */
    protected ServerSocketChannel serverSock = null;
    
    /**
     * use send file
     */
    protected boolean useSendfile = true;
    
    /**
     * The size of the OOM parachute.
     */
    protected int oomParachute = 1024*1024;
    /**
     * The oom parachute, when an OOM error happens, 
     * will release the data, giving the JVM instantly 
     * a chunk of data to be able to recover with.
     */
    protected byte[] oomParachuteData = null;
    
    /**
     * Make sure this string has already been allocated
     */
    protected static final String oomParachuteMsg = 
        "SEVERE:Memory usage is low, parachute is non existent, your system may start failing.";
    
    /**
     * Keep track of OOM warning messages.
     */
    long lastParachuteCheck = System.currentTimeMillis();
    
    /**
     * 
     */
    protected volatile CountDownLatch stopLatch = null;
    
    /**
     * Cache for SocketProcessor objects
     */
    protected ConcurrentLinkedQueue<SocketProcessor> processorCache = new ConcurrentLinkedQueue<SocketProcessor>() {
        private static final long serialVersionUID = 1L;
        protected AtomicInteger size = new AtomicInteger(0);
        @Override
        public boolean offer(SocketProcessor sc) {
            sc.reset(null,null);
            boolean offer = socketProperties.getProcessorCache()==-1?true:size.get()<socketProperties.getProcessorCache();
            //avoid over growing our cache or add after we have stopped
            if ( running && (!paused) && (offer) ) {
                boolean result = super.offer(sc);
                if ( result ) {
                    size.incrementAndGet();
                }
                return result;
            }
            else return false;
        }
        
        @Override
        public SocketProcessor poll() {
            SocketProcessor result = super.poll();
            if ( result != null ) {
                size.decrementAndGet();
            }
            return result;
        }
        
        @Override
        public void clear() {
            super.clear();
            size.set(0);
        }
    };


    /**
     * Cache for key attachment objects
     */
    protected ConcurrentLinkedQueue<KeyAttachment> keyCache = new ConcurrentLinkedQueue<KeyAttachment>() {
        private static final long serialVersionUID = 1L;
        protected AtomicInteger size = new AtomicInteger(0);
        @Override
        public boolean offer(KeyAttachment ka) {
            ka.reset();
            boolean offer = socketProperties.getKeyCache()==-1?true:size.get()<socketProperties.getKeyCache();
            //avoid over growing our cache or add after we have stopped
            if ( running && (!paused) && (offer) ) {
                boolean result = super.offer(ka);
                if ( result ) {
                    size.incrementAndGet();
                }
                return result;
            }
            else return false;
        }

        @Override
        public KeyAttachment poll() {
            KeyAttachment result = super.poll();
            if ( result != null ) {
                size.decrementAndGet();
            }
            return result;
        }

        @Override
        public void clear() {
            super.clear();
            size.set(0);
        }
    };

    
    /**
     * Cache for poller events
     */
    protected ConcurrentLinkedQueue<PollerEvent> eventCache = new ConcurrentLinkedQueue<PollerEvent>() {
        private static final long serialVersionUID = 1L;
        protected AtomicInteger size = new AtomicInteger(0);
        @Override
        public boolean offer(PollerEvent pe) {
            pe.reset();
            boolean offer = socketProperties.getEventCache()==-1?true:size.get()<socketProperties.getEventCache();
            //avoid over growing our cache or add after we have stopped
            if ( running && (!paused) && (offer) ) {
                boolean result = super.offer(pe);
                if ( result ) {
                    size.incrementAndGet();
                }
                return result;
            }
            else return false;
        }

        @Override
        public PollerEvent poll() {
            PollerEvent result = super.poll();
            if ( result != null ) {
                size.decrementAndGet();
            }
            return result;
        }

        @Override
        public void clear() {
            super.clear();
            size.set(0);
        }
    };


    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     */
    protected ConcurrentLinkedQueue<NioChannel> nioChannels = new ConcurrentLinkedQueue<NioChannel>() {
        private static final long serialVersionUID = 1L;
        protected AtomicInteger size = new AtomicInteger(0);
        protected AtomicInteger bytes = new AtomicInteger(0);
        @Override
        public boolean offer(NioChannel socket) {
            boolean offer = socketProperties.getBufferPool()==-1?true:size.get()<socketProperties.getBufferPool();
            offer = offer && (socketProperties.getBufferPoolSize()==-1?true:(bytes.get()+socket.getBufferSize())<socketProperties.getBufferPoolSize());
            //avoid over growing our cache or add after we have stopped
            if ( running && (!paused) && (offer) ) {
                boolean result = super.offer(socket);
                if ( result ) {
                    size.incrementAndGet();
                    bytes.addAndGet(socket.getBufferSize());
                }
                return result;
            }
            else return false;
        }
        
        @Override
        public NioChannel poll() {
            NioChannel result = super.poll();
            if ( result != null ) {
                size.decrementAndGet();
                bytes.addAndGet(-result.getBufferSize());
            }
            return result;
        }
        
        @Override
        public void clear() {
            super.clear();
            size.set(0);
            bytes.set(0);
        }
    };


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else { 
                return super.setProperty(name, value);
            }
        }catch ( Exception x ) {
            log.error("Unable to set attribute \""+name+"\" to \""+value+"\"",x);
            return false;
        }
    }


    /**
     * Priority of the acceptor threads.
     */
    protected int acceptorThreadPriority = Thread.NORM_PRIORITY;
    public void setAcceptorThreadPriority(int acceptorThreadPriority) { this.acceptorThreadPriority = acceptorThreadPriority; }
    public int getAcceptorThreadPriority() { return acceptorThreadPriority; }

    /**
     * Priority of the poller threads.
     */
    protected int pollerThreadPriority = Thread.NORM_PRIORITY;
    public void setPollerThreadPriority(int pollerThreadPriority) { this.pollerThreadPriority = pollerThreadPriority; }
    public int getPollerThreadPriority() { return pollerThreadPriority; }


    /**
     * Handling of accepted sockets.
     */
    protected Handler handler = null;
    public void setHandler(Handler handler ) { this.handler = handler; }
    public Handler getHandler() { return handler; }


    /**
     * Allow comet request handling.
     */
    protected boolean useComet = true;
    public void setUseComet(boolean useComet) { this.useComet = useComet; }
    public boolean getUseComet() { return useComet; }


    /**
     * Acceptor thread count.
     */
    protected int acceptorThreadCount = 1;
    public void setAcceptorThreadCount(int acceptorThreadCount) { this.acceptorThreadCount = acceptorThreadCount; }
    public int getAcceptorThreadCount() { return acceptorThreadCount; }


    /**
     * Poller thread count.
     */
    protected int pollerThreadCount = Runtime.getRuntime().availableProcessors();
    public void setPollerThreadCount(int pollerThreadCount) { this.pollerThreadCount = pollerThreadCount; }
    public int getPollerThreadCount() { return pollerThreadCount; }

    protected long selectorTimeout = 1000;
    public void setSelectorTimeout(long timeout){ this.selectorTimeout = timeout;}
    public long getSelectorTimeout(){ return this.selectorTimeout; }
    /**
     * The socket poller.
     */
    protected Poller[] pollers = null;
    protected AtomicInteger pollerRotater = new AtomicInteger(0);
    /**
     * Return an available poller in true round robin fashion
     */
    public Poller getPoller0() {
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;
        return pollers[idx];
    }


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    public void setSocketProperties(SocketProperties socketProperties) {
        this.socketProperties = socketProperties;
    }

    public void setUseSendfile(boolean useSendfile) {
        this.useSendfile = useSendfile;
    }

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }

    public void setOomParachute(int oomParachute) {
        this.oomParachute = oomParachute;
    }

    public void setOomParachuteData(byte[] oomParachuteData) {
        this.oomParachuteData = oomParachuteData;
    }


    protected SSLContext sslContext = null;
    public SSLContext getSSLContext() { return sslContext;}
    public void setSSLContext(SSLContext c) { sslContext = c;}
    
    // --------------------------------------------------------- OOM Parachute Methods

    protected void checkParachute() {
        boolean para = reclaimParachute(false);
        if (!para && (System.currentTimeMillis()-lastParachuteCheck)>10000) {
            try {
                log.fatal(oomParachuteMsg);
            }catch (Throwable t) {
                System.err.println(oomParachuteMsg);
            }
            lastParachuteCheck = System.currentTimeMillis();
        }
    }
    
    protected boolean reclaimParachute(boolean force) {
        if ( oomParachuteData != null ) return true;
        if ( oomParachute > 0 && ( force || (Runtime.getRuntime().freeMemory() > (oomParachute*2))) )  
            oomParachuteData = new byte[oomParachute];
        return oomParachuteData != null;
    }
    
    protected void releaseCaches() {
        this.keyCache.clear();
        this.nioChannels.clear();
        this.processorCache.clear();
        if ( handler != null ) handler.releaseCaches();
        
    }
    
    // --------------------------------------------------------- Public Methods
    /**
     * Number of keepalive sockets.
     */
    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            int sum = 0;
            for (int i=0; i<pollers.length; i++) {
                sum += pollers[i].getKeyCount();
            }
            return sum;
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods


    /**
     * Initialize the endpoint.
     */
    @Override
    public void init()
        throws Exception {

        if (initialized)
            return;

        serverSock = ServerSocketChannel.open();
        socketProperties.setProperties(serverSock.socket());
        InetSocketAddress addr = (getAddress()!=null?new InetSocketAddress(getAddress(),getPort()):new InetSocketAddress(getPort()));
        serverSock.socket().bind(addr,getBacklog()); 
        serverSock.configureBlocking(true); //mimic APR behavior
        serverSock.socket().setSoTimeout(getSocketProperties().getSoTimeout());

        // Initialize thread count defaults for acceptor, poller
        if (acceptorThreadCount == 0) {
            // FIXME: Doesn't seem to work that well with multiple accept threads
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount <= 0) {
            //minimum one poller thread
            pollerThreadCount = 1;
        }
        stopLatch = new CountDownLatch(pollerThreadCount);

        // Initialize SSL if needed
        if (isSSLEnabled()) {
            // Initialize SSL
            String keystorePass = getKeystorePass();
            if (keystorePass == null) {
                keystorePass = JSSESocketFactory.DEFAULT_KEY_PASS;
            }
            char[] passphrase = keystorePass.toCharArray();

            char[] tpassphrase = (getTruststorePass()!=null)?getTruststorePass().toCharArray():passphrase;
            String ttype = (getTruststoreType()!=null)?getTruststoreType():getKeystoreType();
            
            KeyStore ks = KeyStore.getInstance(getKeystoreType());
            ks.load(new FileInputStream(getKeystoreFile()), passphrase);
            KeyStore ts = null;
            if (getTruststoreFile()==null) {
                //no op, same as for BIO connector
            }else {
                ts = KeyStore.getInstance(ttype);
                ts.load(new FileInputStream(getTruststoreFile()), tpassphrase);
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(getAlgorithm());
            kmf.init(ks, passphrase);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(getAlgorithm());
            tmf.init(ts);

            sslContext = SSLContext.getInstance(getSslProtocol());
            sslContext.init(wrap(kmf.getKeyManagers()), tmf.getTrustManagers(), null);
            SSLSessionContext sessionContext =
                sslContext.getServerSessionContext();
            if (sessionContext != null) {
                if (getSessionCacheSize() != null) {
                    sessionContext.setSessionCacheSize(
                            Integer.parseInt(getSessionCacheSize()));
                }
                if (getSessionTimeout() != null) {
                    sessionContext.setSessionTimeout(
                            Integer.parseInt(getSessionTimeout()));
                }
            }
        }
        
        if (oomParachute>0) reclaimParachute(true);
        selectorPool.open();
        initialized = true;

    }
    
    public KeyManager[] wrap(KeyManager[] managers) {
        if (managers==null) return null;
        KeyManager[] result = new KeyManager[managers.length];
        for (int i=0; i<result.length; i++) {
            if (managers[i] instanceof X509KeyManager && getKeyAlias()!=null) {
                result[i] = new NioX509KeyManager((X509KeyManager)managers[i],getKeyAlias());
            } else {
                result[i] = managers[i];
            }
        }
        return result;
    }


    /**
     * Start the NIO endpoint, creating acceptor, poller threads.
     */
    @Override
    public void start()
        throws Exception {
        // Initialize socket if not done before
        if (!initialized) {
            init();
        }
        if (!running) {
            running = true;
            paused = false;
            
            // Create worker collection
            if ( getExecutor() == null ) {
                createExecutor();
            }

            // Start poller threads
            pollers = new Poller[getPollerThreadCount()];
            for (int i=0; i<pollers.length; i++) {
                pollers[i] = new Poller();
                Thread pollerThread = new Thread(pollers[i], getName() + "-ClientPoller-"+i);
                pollerThread.setPriority(threadPriority);
                pollerThread.setDaemon(true);
                pollerThread.start();
            }

            // Start acceptor threads
            for (int i = 0; i < acceptorThreadCount; i++) {
                Thread acceptorThread = new Thread(new Acceptor(), getName() + "-Acceptor-" + i);
                acceptorThread.setPriority(threadPriority);
                acceptorThread.setDaemon(getDaemon());
                acceptorThread.start();
            }
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    @Override
    public void stop() {
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
            for (int i=0; pollers!=null && i<pollers.length; i++) {
                if (pollers[i]==null) continue;
                pollers[i].destroy();
                pollers[i] = null;
            }
            try { stopLatch.await(selectorTimeout+100,TimeUnit.MILLISECONDS); } catch (InterruptedException ignore ) {}
        }
        eventCache.clear();
        keyCache.clear();
        nioChannels.clear();
        processorCache.clear();
        shutdownExecutor();
        
    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    @Override
    public void destroy() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for "+new InetSocketAddress(getAddress(),getPort()));
        }
        if (running) {
            stop();
        }
        // Close server socket
        serverSock.socket().close();
        serverSock.close();
        serverSock = null;
        sslContext = null;
        initialized = false;
        releaseCaches();
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for "+new InetSocketAddress(getAddress(),getPort()));
        }
    }


    // ------------------------------------------------------ Protected Methods


    public int getWriteBufSize() {
        return socketProperties.getTxBufSize();
    }

    public int getReadBufSize() {
        return socketProperties.getRxBufSize();
    }

    public NioSelectorPool getSelectorPool() {
        return selectorPool;
    }

    @Override
    public boolean getUseSendfile() {
        return useSendfile;
    }

    public int getOomParachute() {
        return oomParachute;
    }

    public byte[] getOomParachuteData() {
        return oomParachuteData;
    }


    /**
     * Process the specified connection.
     */
    protected boolean setSocketOptions(SocketChannel socket) {
        // Process the connection
        try {
            //disable blocking, APR style, we are gonna be polling it
            socket.configureBlocking(false);
            Socket sock = socket.socket();
            socketProperties.setProperties(sock);

            NioChannel channel = nioChannels.poll();
            if ( channel == null ) {
                // SSL setup
                if (sslContext != null) {
                    SSLEngine engine = createSSLEngine();
                    int appbufsize = engine.getSession().getApplicationBufferSize();
                    NioBufferHandler bufhandler = new NioBufferHandler(Math.max(appbufsize,socketProperties.getAppReadBufSize()),
                                                                       Math.max(appbufsize,socketProperties.getAppWriteBufSize()),
                                                                       socketProperties.getDirectBuffer());
                    channel = new SecureNioChannel(socket, engine, bufhandler, selectorPool);
                } else {
                    // normal tcp setup
                    NioBufferHandler bufhandler = new NioBufferHandler(socketProperties.getAppReadBufSize(),
                                                                       socketProperties.getAppWriteBufSize(),
                                                                       socketProperties.getDirectBuffer());

                    channel = new NioChannel(socket, bufhandler);
                }
            } else {                
                channel.setIOChannel(socket);
                if ( channel instanceof SecureNioChannel ) {
                    SSLEngine engine = createSSLEngine();
                    ((SecureNioChannel)channel).reset(engine);
                } else {
                    channel.reset();
                }
            }
            getPoller0().register(channel);
        } catch (Throwable t) {
            try {
                log.error("",t);
            }catch ( Throwable tt){}
            // Tell to close the socket
            return false;
        }
        return true;
    }

    protected SSLEngine createSSLEngine() {
        SSLEngine engine = sslContext.createSSLEngine();
        if ("false".equals(getClientAuth())) {
            engine.setNeedClientAuth(false);
            engine.setWantClientAuth(false);
        } else if ("true".equals(getClientAuth()) || "yes".equals(getClientAuth())){
            engine.setNeedClientAuth(true);
        } else if ("want".equals(getClientAuth())) {
            engine.setWantClientAuth(true);
        }
        engine.setUseClientMode(false);
        if ( getCiphersArray().length > 0 ) engine.setEnabledCipherSuites(getCiphersArray());
        if ( getSslEnabledProtocolsArray().length > 0 ) engine.setEnabledProtocols(getSslEnabledProtocolsArray());
        
        return engine;
    }


    /**
     * Returns true if a worker thread is available for processing.
     * @return boolean
     */
    protected boolean isWorkerAvailable() {
        return true;
    }

    public boolean processSocket(NioChannel socket, SocketStatus status, boolean dispatch) {
        try {
            KeyAttachment attachment = (KeyAttachment)socket.getAttachment(false);
            attachment.setCometNotify(false); //will get reset upon next reg
            SocketProcessor sc = processorCache.poll();
            if ( sc == null ) sc = new SocketProcessor(socket,status);
            else sc.reset(socket,status);
            if ( dispatch && getExecutor()!=null ) getExecutor().execute(sc);
            else sc.run();
        } catch (RejectedExecutionException rx) {
            log.warn("Socket processing request was rejected for:"+socket,rx);
            return false;
        } catch (Throwable t) {
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }

    @Override
    protected Log getLog() {
        return log;
    }

    // --------------------------------------------------- Acceptor Inner Class


    /**
     * Server socket acceptor thread.
     */
    protected class Acceptor implements Runnable {
        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        @Override
        public void run() {
            
            // Loop until we receive a shutdown command
            while (running) {
                
                // Loop if endpoint is paused
                while (paused && running) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                if (!running) {
                    break;
                }
                try {
                    // Accept the next incoming connection from the server socket
                    SocketChannel socket = serverSock.accept();
                    // Hand this socket off to an appropriate processor
                    //TODO FIXME - this is currently a blocking call, meaning we will be blocking
                    //further accepts until there is a thread available.
                    if ( running && (!paused) && socket != null ) {
                        // setSocketOptions() will add channel to the poller
                        // if successful
                        if (!setSocketOptions(socket)) {
                            try {
                                socket.socket().close();
                                socket.close();
                            } catch (IOException ix) {
                                if (log.isDebugEnabled())
                                    log.debug("", ix);
                            }
                        } 
                    }
                } catch (SocketTimeoutException sx) {
                    //normal condition
                } catch (IOException x) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), x);
                    }
                } catch (OutOfMemoryError oom) {
                    try {
                        oomParachuteData = null;
                        releaseCaches();
                        log.error("", oom);
                    }catch ( Throwable oomt ) {
                        try {
                            try {
                                System.err.println(oomParachuteMsg);
                                oomt.printStackTrace();
                            }catch (Throwable letsHopeWeDontGetHere){}
                        }catch (Throwable letsHopeWeDontGetHere){}
                    }
                } catch (Throwable t) {
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }//while
        }//run
    }


    // ----------------------------------------------------- Poller Inner Classes

    /**
     * 
     * PollerEvent, cacheable object for poller events to avoid GC
     */
    public class PollerEvent implements Runnable {
        
        protected NioChannel socket;
        protected int interestOps;
        protected KeyAttachment key;
        public PollerEvent(NioChannel ch, KeyAttachment k, int intOps) {
            reset(ch, k, intOps);
        }
    
        public void reset(NioChannel ch, KeyAttachment k, int intOps) {
            socket = ch;
            interestOps = intOps;
            key = k;
        }
    
        public void reset() {
            reset(null, null, 0);
        }
    
        @Override
        public void run() {
            if ( interestOps == OP_REGISTER ) {
                try {
                    socket.getIOChannel().register(socket.getPoller().getSelector(), SelectionKey.OP_READ, key);
                } catch (Exception x) {
                    log.error("", x);
                }
            } else {
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try {
                    boolean cancel = false;
                    if (key != null) {
                        final KeyAttachment att = (KeyAttachment) key.attachment();
                        if ( att!=null ) {
                            //handle callback flag
                            if (att.getComet() && (interestOps & OP_CALLBACK) == OP_CALLBACK ) {
                                att.setCometNotify(true);
                            } else {
                                att.setCometNotify(false);
                            }
                            interestOps = (interestOps & (~OP_CALLBACK));//remove the callback flag
                            att.access();//to prevent timeout
                            //we are registering the key to start with, reset the fairness counter.
                            int ops = key.interestOps() | interestOps;
                            att.interestOps(ops);
                            key.interestOps(ops);
                        } else {
                            cancel = true;
                        }
                    } else {
                        cancel = true;
                    }
                    if ( cancel ) socket.getPoller().cancelledKey(key,SocketStatus.ERROR,false);
                }catch (CancelledKeyException ckx) {
                    try {
                        socket.getPoller().cancelledKey(key,SocketStatus.DISCONNECT,true);
                    }catch (Exception ignore) {}
                }
            }//end if
        }//run
        
        @Override
        public String toString() {
            return super.toString()+"[intOps="+this.interestOps+"]";
        }
    }
    
    /**
     * Poller class.
     */
    public class Poller implements Runnable {

        protected Selector selector;
        protected ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<Runnable>();
        
        protected volatile boolean close = false;
        protected long nextExpiration = 0;//optimize expiration handling
        
        protected AtomicLong wakeupCounter = new AtomicLong(0l);
        
        protected volatile int keyCount = 0;

        public Poller() throws IOException {
            this.selector = Selector.open();
        }
        
        public int getKeyCount() { return keyCount; }
        
        public Selector getSelector() { return selector;}

        /**
         * Destroy the poller.
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            events.clear();
            selector.wakeup();
        }
        
        public void addEvent(Runnable event) {
            events.offer(event);
            if ( wakeupCounter.incrementAndGet() == 0 ) selector.wakeup();
        }
        
        public void cometInterest(NioChannel socket) {
            KeyAttachment att = (KeyAttachment)socket.getAttachment(false);
            add(socket,att.getCometOps());
            if ( (att.getCometOps()&OP_CALLBACK) == OP_CALLBACK ) {
                nextExpiration = 0; //force the check for faster callback
                selector.wakeup();
            }
        }
        
        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket to add to the poller
         */
        public void add(final NioChannel socket) {
            add(socket,SelectionKey.OP_READ);
        }
        
        public void add(final NioChannel socket, final int interestOps) {
            PollerEvent r = eventCache.poll();
            if ( r==null) r = new PollerEvent(socket,null,interestOps);
            else r.reset(socket,null,interestOps);
            addEvent(r);
        }
        
        public boolean events() {
            boolean result = false;
            //synchronized (events) {
                Runnable r = null;
                result = (events.size() > 0);
                while ( (r = events.poll()) != null ) {
                    try {
                        r.run();
                        if ( r instanceof PollerEvent ) {
                            ((PollerEvent)r).reset();
                            eventCache.offer((PollerEvent)r);
                        }
                    } catch ( Throwable x ) {
                        log.error("",x);
                    }
                }
                //events.clear();
            //}
            return result;
        }
        
        public void register(final NioChannel socket)
        {
            socket.setPoller(this);
            KeyAttachment key = keyCache.poll();
            final KeyAttachment ka = key!=null?key:new KeyAttachment(socket);
            ka.reset(this,socket,getSocketProperties().getSoTimeout());
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            PollerEvent r = eventCache.poll();
            ka.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
            if ( r==null) r = new PollerEvent(socket,ka,OP_REGISTER);
            else r.reset(socket,ka,OP_REGISTER);
            addEvent(r);
        }
        public void cancelledKey(SelectionKey key, SocketStatus status, boolean dispatch) {
            try {
                if ( key == null ) return;//nothing to do
                KeyAttachment ka = (KeyAttachment) key.attachment();
                if (ka != null && ka.getComet() && status != null) {
                    //the comet event takes care of clean up
                    //processSocket(ka.getChannel(), status, dispatch);
                    ka.setComet(false);//to avoid a loop
                    if (status == SocketStatus.TIMEOUT ) {
                        if (processSocket(ka.getChannel(), status, true)) {
                            return; // don't close on comet timeout
                        }
                    } else {
                        processSocket(ka.getChannel(), status, false); //don't dispatch if the lines below are cancelling the key
                    }                    
                }
                key.attach(null);
                if (ka!=null) handler.release(ka.getChannel());
                else handler.release((SocketChannel)key.channel());
                if (key.isValid()) key.cancel();
                if (key.channel().isOpen()) try {key.channel().close();}catch (Exception ignore){}
                try {if (ka!=null) ka.getSocket().close(true);}catch (Exception ignore){}
                try {if (ka!=null && ka.getSendfileData()!=null && ka.getSendfileData().fchannel!=null && ka.getSendfileData().fchannel.isOpen()) ka.getSendfileData().fchannel.close();}catch (Exception ignore){}
                if (ka!=null) ka.reset();
            } catch (Throwable e) {
                if ( log.isDebugEnabled() ) log.error("",e);
                // Ignore
            }
        }
        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        @Override
        public void run() {
            // Loop until we receive a shutdown command
            while (running) {
                try {
                    // Loop if endpoint is paused
                    while (paused && (!close) ) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            // Ignore
                        }
                    }
                    boolean hasEvents = false;

                    hasEvents = (hasEvents | events());
                    // Time to terminate?
                    if (close) {
                        timeout(0, false);
                        break;
                    }
                    int keyCount = 0;
                    try {
                        if ( !close ) {
                            if (wakeupCounter.get()>0) {
                                //if we are here, means we have other stuff to do
                                //do a non blocking select
                                keyCount = selector.selectNow();
                            }else {
                                keyCount = selector.keys().size();
                                wakeupCounter.set(-1);
                                keyCount = selector.select(selectorTimeout);
                            }
                            wakeupCounter.set(0);
                        }
                        if (close) {
                            timeout(0, false);
                            selector.close(); 
                            break; 
                        }
                    } catch ( NullPointerException x ) {
                        //sun bug 5076772 on windows JDK 1.5
                        if ( log.isDebugEnabled() ) log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5",x);
                        if ( wakeupCounter == null || selector == null ) throw x;
                        continue;
                    } catch ( CancelledKeyException x ) {
                        //sun bug 5076772 on windows JDK 1.5
                        if ( log.isDebugEnabled() ) log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5",x);
                        if ( wakeupCounter == null || selector == null ) throw x;
                        continue;
                    } catch (Throwable x) {
                        log.error("",x);
                        continue;
                    }
                    //either we timed out or we woke up, process events first
                    if ( keyCount == 0 ) hasEvents = (hasEvents | events());

                    Iterator<SelectionKey> iterator =
                        keyCount > 0 ? selector.selectedKeys().iterator() : null;
                    // Walk through the collection of ready keys and dispatch
                    // any active event.
                    while (iterator != null && iterator.hasNext()) {
                        SelectionKey sk = iterator.next();
                        KeyAttachment attachment = (KeyAttachment)sk.attachment();
                        attachment.access();
                        iterator.remove();
                        processKey(sk, attachment);
                    }//while

                    //process timeouts
                    timeout(keyCount,hasEvents);
                    if ( oomParachute > 0 && oomParachuteData == null ) checkParachute();
                } catch (OutOfMemoryError oom) {
                    try {
                        oomParachuteData = null;
                        releaseCaches();
                        log.error("", oom);
                    }catch ( Throwable oomt ) {
                        try {
                            System.err.println(oomParachuteMsg);
                            oomt.printStackTrace();
                        }catch (Throwable letsHopeWeDontGetHere){}
                    }
                }
            }//while
            synchronized (this) {
                this.notifyAll();
            }
            stopLatch.countDown();

        }
        
        protected boolean processKey(SelectionKey sk, KeyAttachment attachment) {
            boolean result = true;
            try {
                if ( close ) {
                    cancelledKey(sk, SocketStatus.STOP, false);
                } else if ( sk.isValid() && attachment != null ) {
                    attachment.access();//make sure we don't time out valid sockets
                    sk.attach(attachment);//cant remember why this is here
                    NioChannel channel = attachment.getChannel();
                    if (sk.isReadable() || sk.isWritable() ) {
                        if ( attachment.getSendfileData() != null ) {
                            processSendfile(sk,attachment,true, false);
                        } else if ( attachment.getComet() ) {
                            //check if thread is available
                            if ( isWorkerAvailable() ) {
                                //set interest ops to 0 so we don't get multiple
                                //Invocations for both read and write on separate threads
                                reg(sk, attachment, 0);
                                //read goes before write
                                if (sk.isReadable()) {
                                    //read notification
                                    if (!processSocket(channel, SocketStatus.OPEN, true))
                                        processSocket(channel, SocketStatus.DISCONNECT, true);
                                } else {
                                    //future placement of a WRITE notif
                                    if (!processSocket(channel, SocketStatus.OPEN, true))
                                        processSocket(channel, SocketStatus.DISCONNECT, true);
                                }
                            } else {
                                result = false;
                            }
                        } else {
                            //later on, improve latch behavior
                            if ( isWorkerAvailable() ) {
                                unreg(sk, attachment,sk.readyOps());
                                boolean close = (!processSocket(channel, null, true));
                                if (close) {
                                    cancelledKey(sk,SocketStatus.DISCONNECT,false);
                                }
                            } else {
                                result = false;
                            }
                        }
                    } 
                } else {
                    //invalid key
                    cancelledKey(sk, SocketStatus.ERROR,false);
                }
            } catch ( CancelledKeyException ckx ) {
                cancelledKey(sk, SocketStatus.ERROR,false);
            } catch (Throwable t) {
                log.error("",t);
            }
            return result;
        }
        
        public boolean processSendfile(SelectionKey sk, KeyAttachment attachment, boolean reg, boolean event) {
            NioChannel sc = null;
            try {
                //unreg(sk,attachment);//only do this if we do process send file on a separate thread
                SendfileData sd = attachment.getSendfileData();
                if ( sd.fchannel == null ) {
                    File f = new File(sd.fileName);
                    if ( !f.exists() ) {
                        cancelledKey(sk,SocketStatus.ERROR,false);
                        return false;
                    }
                    sd.fchannel = new FileInputStream(f).getChannel();
                }
                sc = attachment.getChannel();
                sc.setSendFile(true);
                WritableByteChannel wc = ((sc instanceof SecureNioChannel)?sc:sc.getIOChannel());
                
                if (sc.getOutboundRemaining()>0) {
                    if (sc.flushOutbound()) {
                        attachment.access();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos,sd.length,wc);
                    if ( written > 0 ) {
                        sd.pos += written;
                        sd.length -= written;
                        attachment.access();
                    }
                }
                if ( sd.length <= 0 && sc.getOutboundRemaining()<=0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for:"+sd.fileName);
                    }
                    attachment.setSendfileData(null);
                    try {sd.fchannel.close();}catch(Exception ignore){}
                    if ( sd.keepAlive ) {
                        if (reg) {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, registering back for OP_READ");
                            }
                            if (event) {
                                this.add(attachment.getChannel(),SelectionKey.OP_READ);
                            } else {
                                reg(sk,attachment,SelectionKey.OP_READ);
                            }
                        }
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Send file connection is being closed");
                        }
                        cancelledKey(sk,SocketStatus.STOP,false);
                    }
                } else if ( attachment.interestOps() == 0 && reg ) {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendilfe:"+sd.fileName);
                    }
                    if (event) {
                        add(attachment.getChannel(),SelectionKey.OP_WRITE);
                    } else {
                        reg(sk,attachment,SelectionKey.OP_WRITE);
                    }
                }
            }catch ( IOException x ) {
                if ( log.isDebugEnabled() ) log.debug("Unable to complete sendfile request:", x);
                cancelledKey(sk,SocketStatus.ERROR,false);
                return false;
            }catch ( Throwable t ) {
                log.error("",t);
                cancelledKey(sk, SocketStatus.ERROR, false);
                return false;
            }finally {
                if (sc!=null) sc.setSendFile(false);
            }
            return true;
        }

        protected void unreg(SelectionKey sk, KeyAttachment attachment, int readyOps) {
            //this is a must, so that we don't have multiple threads messing with the socket
            reg(sk,attachment,sk.interestOps()& (~readyOps));
        }
        
        protected void reg(SelectionKey sk, KeyAttachment attachment, int intops) {
            sk.interestOps(intops); 
            attachment.interestOps(intops);
            attachment.setCometOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            //don't process timeouts too frequently, but if the selector simply timed out
            //then we can check timeouts to avoid gaps
            if ( ((keyCount>0 || hasEvents) ||(now < nextExpiration)) && (!close) ) {
                return;
            }
            long prevExp = nextExpiration; //for logging purposes only
            nextExpiration = now + socketProperties.getTimeoutInterval();
            //timeout
            Set<SelectionKey> keys = selector.keys();
            int keycount = 0;
            for (Iterator<SelectionKey> iter = keys.iterator(); iter.hasNext();) {
                SelectionKey key = iter.next();
                keycount++;
                try {
                    KeyAttachment ka = (KeyAttachment) key.attachment();
                    if ( ka == null ) {
                        cancelledKey(key, SocketStatus.ERROR,false); //we don't support any keys without attachments
                    } else if ( ka.getError() ) {
                        cancelledKey(key, SocketStatus.ERROR,true);//TODO this is not yet being used
                    } else if (ka.getComet() && ka.getCometNotify() ) {
                        ka.setCometNotify(false);
                        reg(key,ka,0);//avoid multiple calls, this gets reregistered after invocation
                        //if (!processSocket(ka.getChannel(), SocketStatus.OPEN_CALLBACK)) processSocket(ka.getChannel(), SocketStatus.DISCONNECT);
                        if (!processSocket(ka.getChannel(), SocketStatus.OPEN, true)) processSocket(ka.getChannel(), SocketStatus.DISCONNECT, true);
                    }else if ((ka.interestOps()&SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                              (ka.interestOps()&SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                        //only timeout sockets that we are waiting for a read from
                        long delta = now - ka.getLastAccess();
                        long timeout = (ka.getTimeout()==-1)?((long) socketProperties.getSoTimeout()):(ka.getTimeout());
                        boolean isTimedout = delta > timeout;
                        if ( close ) {
                            key.interestOps(0); 
                            ka.interestOps(0); //avoid duplicate stop calls
                            processKey(key,ka);
                        } else if (isTimedout) {
                            key.interestOps(0); 
                            ka.interestOps(0); //avoid duplicate timeout calls
                            cancelledKey(key, SocketStatus.TIMEOUT,true);
                        } else {
                            long nextTime = now+(timeout-delta);
                            nextExpiration = (nextTime < nextExpiration)?nextTime:nextExpiration;
                        }
                    }else if (ka.isAsync()) {
                        long delta = now - ka.getLastAccess();
                        long timeout = (ka.getTimeout()==-1)?((long) socketProperties.getSoTimeout()):(ka.getTimeout());
                        boolean isTimedout = delta > timeout;
                        if (isTimedout) {
                            processSocket(ka.getChannel(), SocketStatus.TIMEOUT, true);
                        }
                    }//end if
                }catch ( CancelledKeyException ckx ) {
                    cancelledKey(key, SocketStatus.ERROR,false);
                }
            }//for
            if ( log.isTraceEnabled() ) log.trace("timeout completed: keys processed="+keycount+"; now="+now+"; nextExpiration="+prevExp+"; "+
                                                  "keyCount="+keyCount+"; hasEvents="+hasEvents +"; eval="+( (now < prevExp) && (keyCount>0 || hasEvents) && (!close) ));

        }
    }

// ----------------------------------------------------- Key Attachment Class   
    public static class KeyAttachment extends SocketWrapper<NioChannel> {
        
        public KeyAttachment(NioChannel channel) {
            super(channel);
        }
        
        public void reset(Poller poller, NioChannel channel, long soTimeout) {
            this.socket = channel;
            this.poller = poller;
            lastAccess = System.currentTimeMillis();
            comet = false;
            timeout = soTimeout;
            error = false;
            lastRegistered = 0;
            sendfileData = null;
            if ( readLatch!=null ) try {for (int i=0; i<(int)readLatch.getCount();i++) readLatch.countDown();}catch (Exception ignore){}
            readLatch = null;
            if ( writeLatch!=null ) try {for (int i=0; i<(int)writeLatch.getCount();i++) writeLatch.countDown();}catch (Exception ignore){}
            writeLatch = null;
            cometNotify = false;
            cometOps = SelectionKey.OP_READ;
            sendfileData = null;
            keepAliveLeft = 100;
            async = false;
        }
        
        public void reset() {
            reset(null,null,-1);
        }
        
        public Poller getPoller() { return poller;}
        public void setPoller(Poller poller){this.poller = poller;}
        public void setComet(boolean comet) { this.comet = comet; }
        public boolean getComet() { return comet; }
        public void setCometNotify(boolean notify) { this.cometNotify = notify; }
        public boolean getCometNotify() { return cometNotify; }
        public void setCometOps(int ops) { this.cometOps = ops; }
        public int getCometOps() { return cometOps; }
        public NioChannel getChannel() { return getSocket();}
        public void setChannel(NioChannel channel) { this.socket = channel;}
        protected Poller poller = null;
        protected int interestOps = 0;
        public int interestOps() { return interestOps;}
        public int interestOps(int ops) { this.interestOps  = ops; return ops; }
        public CountDownLatch getReadLatch() { return readLatch; }
        public CountDownLatch getWriteLatch() { return writeLatch; }
        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if ( latch==null || latch.getCount() == 0 ) return null;
            else throw new IllegalStateException("Latch must be at count 0");
        }
        public void resetReadLatch() { readLatch = resetLatch(readLatch); }
        public void resetWriteLatch() { writeLatch = resetLatch(writeLatch); }
        
        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) { 
            if ( latch == null || latch.getCount() == 0 ) {
                return new CountDownLatch(cnt);
            }
            else throw new IllegalStateException("Latch must be at count 0 or null.");
        }
        public void startReadLatch(int cnt) { readLatch = startLatch(readLatch,cnt);}
        public void startWriteLatch(int cnt) { writeLatch = startLatch(writeLatch,cnt);}
        
        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if ( latch == null ) throw new IllegalStateException("Latch cannot be null");
            latch.await(timeout,unit);
        }
        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(readLatch,timeout,unit);}
        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(writeLatch,timeout,unit);}
        
        public long getLastRegistered() { return lastRegistered; }
        public void setLastRegistered(long reg) { lastRegistered = reg; }
        
        public void setSendfileData(SendfileData sf) { this.sendfileData = sf;}
        public SendfileData getSendfileData() { return this.sendfileData;}
        
        protected boolean comet = false;
        protected int cometOps = SelectionKey.OP_READ;
        protected boolean cometNotify = false;
        protected CountDownLatch readLatch = null;
        protected CountDownLatch writeLatch = null;
        protected SendfileData sendfileData = null;
        
    }

    // ------------------------------------------------ Application Buffer Handler
    public class NioBufferHandler implements ApplicationBufferHandler {
        protected ByteBuffer readbuf = null;
        protected ByteBuffer writebuf = null;
        
        public NioBufferHandler(int readsize, int writesize, boolean direct) {
            if ( direct ) {
                readbuf = ByteBuffer.allocateDirect(readsize);
                writebuf = ByteBuffer.allocateDirect(writesize);
            }else {
                readbuf = ByteBuffer.allocate(readsize);
                writebuf = ByteBuffer.allocate(writesize);
            }
        }
        
        @Override
        public ByteBuffer expand(ByteBuffer buffer, int remaining) {return buffer;}
        @Override
        public ByteBuffer getReadBuffer() {return readbuf;}
        @Override
        public ByteBuffer getWriteBuffer() {return writebuf;}

    }

    // ------------------------------------------------ Handler Inner Interface


    /**
     * Bare bones interface used for socket processing. Per thread data is to be
     * stored in the ThreadWithAttributes extra folders, or alternately in
     * thread local fields.
     */
    public interface Handler extends AbstractEndpoint.Handler {
        public SocketState process(NioChannel socket);
        public SocketState event(NioChannel socket, SocketStatus status);
        public void releaseCaches();
        public void release(NioChannel socket);
        public void release(SocketChannel socket);
    }


    // ---------------------------------------------- SocketProcessor Inner Class
    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor implements Runnable {

        protected NioChannel socket = null;
        protected SocketStatus status = null; 

        public SocketProcessor(NioChannel socket, SocketStatus status) {
            reset(socket,status);
        }
        
        public void reset(NioChannel socket, SocketStatus status) {
            this.socket = socket;
            this.status = status;
        }
         
        @Override
        public void run() {
            boolean launch = false;
            synchronized (socket) {
                SelectionKey key = null;
                try {
                    key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                    int handshake = -1;
                    
                    try {
                        if (key!=null) handshake = socket.handshake(key.isReadable(), key.isWritable());
                    }catch ( IOException x ) {
                        handshake = -1;
                        if ( log.isDebugEnabled() ) log.debug("Error during SSL handshake",x);
                    }catch ( CancelledKeyException ckx ) {
                        handshake = -1;
                    }
                    if ( handshake == 0 ) {
                        SocketState state = SocketState.OPEN;
                        // Process the request from this socket
                        state = (status==null)?handler.process(socket):handler.event(socket,status);
    
                        if (state == SocketState.CLOSED) {
                            // Close socket and pool
                            try {
                                KeyAttachment ka = null;
                                if (key!=null) {
                                    ka = (KeyAttachment) key.attachment();
                                    if (ka!=null) ka.setComet(false);
                                    socket.getPoller().cancelledKey(key, SocketStatus.ERROR, false);
                                }
                                if (socket!=null) nioChannels.offer(socket);
                                socket = null;
                                if ( ka!=null ) keyCache.offer(ka);
                                ka = null;
                            }catch ( Exception x ) {
                                log.error("",x);
                            }
                        } else if (state == SocketState.ASYNC_END) {
                            launch = true;
                        }
                    } else if (handshake == -1 ) {
                        KeyAttachment ka = null;
                        if (key!=null) {
                            ka = (KeyAttachment) key.attachment();
                            socket.getPoller().cancelledKey(key, SocketStatus.DISCONNECT, false);
                        }
                        if (socket!=null) nioChannels.offer(socket);
                        socket = null;
                        if ( ka!=null ) keyCache.offer(ka);
                        ka = null;
                    } else {
                        final SelectionKey fk = key;
                        final int intops = handshake;
                        final KeyAttachment ka = (KeyAttachment)fk.attachment();
                        ka.getPoller().add(socket,intops);
                    }
                }catch(CancelledKeyException cx) {
                    socket.getPoller().cancelledKey(key,null,false);
                } catch (OutOfMemoryError oom) {
                    try {
                        oomParachuteData = null;
                        socket.getPoller().cancelledKey(key,SocketStatus.ERROR,false);
                        releaseCaches();
                        log.error("", oom);
                    }catch ( Throwable oomt ) {
                        try {
                            System.err.println(oomParachuteMsg);
                            oomt.printStackTrace();
                        }catch (Throwable letsHopeWeDontGetHere){}
                    }
                }catch ( Throwable t ) {
                    log.error("",t);
                    socket.getPoller().cancelledKey(key,SocketStatus.ERROR,false);
                } finally {
                    if (launch) {
                        try {
                            getExecutor().execute(new SocketProcessor(socket, SocketStatus.OPEN));
                        } catch (NullPointerException npe) {
                            if (running) {
                                log.error(sm.getString("endpoint.launch.fail"),
                                        npe);
                            }
                        }
                    }
                    socket = null;
                    status = null;
                    //return to cache
                    processorCache.offer(this);
                }
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class
    /**
     * SendfileData class.
     */
    public static class SendfileData {
        // File
        public String fileName;
        public FileChannel fchannel;
        public long pos;
        public long length;
        // KeepAlive flag
        public boolean keepAlive;
    }
}
