package com.ning.http.client.providers.apache;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.Body;
import com.ning.http.client.ByteArrayPart;
import com.ning.http.client.Cookie;
import com.ning.http.client.FilePart;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Part;
import com.ning.http.client.PerRequestConfig;
import com.ning.http.client.ProgressAsyncHandler;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.StringPart;
import com.ning.http.client.logging.LogManager;
import com.ning.http.client.logging.Logger;
import com.ning.http.client.providers.jdk.JDKAsyncHttpProvider;
import com.ning.http.client.providers.jdk.JDKAsyncHttpProviderConfig;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.UTF8UrlEncoder;
import org.apache.commons.httpclient.ConnectTimeoutException;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.NoHttpResponseException;
import org.apache.commons.httpclient.ProxyHost;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.EntityEnclosingMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.OptionsMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.methods.multipart.ByteArrayPartSource;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.PartSource;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.apache.commons.httpclient.params.HttpConnectionParams;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;
import org.apache.commons.httpclient.util.IdleConnectionTimeoutThread;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.text.AbstractDocument;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * Provides a generic http client.
 */
public class ApacheAsyncHttpProvider implements AsyncHttpProvider<HttpClient> {
    private final static Logger logger = LogManager.getLogger(JDKAsyncHttpProvider.class);


    private final AsyncHttpClientConfig config;

    private final AtomicBoolean isClose = new AtomicBoolean(false);

    private final static int MAX_BUFFERED_BYTES = 8192;
    private IdleConnectionTimeoutThread idleConnectionTimeoutThread;
    private final AtomicInteger maxConnections = new AtomicInteger();

    private final MultiThreadedHttpConnectionManager connectionManager;

    private final HttpClientParams params;

    // we do this here because HttpMethodBase access a static map if the URI is absolute

    static {
        final SocketFactory factory = new TrustingSSLSocketFactory();
        Protocol.registerProtocol("https", new Protocol("https", new ProtocolSocketFactory() {
            public Socket createSocket(String string, int i, InetAddress inetAddress, int i1) throws IOException, UnknownHostException {
                return factory.createSocket(string, i, inetAddress, i1);
            }

            public Socket createSocket(String string, int i, InetAddress inetAddress, int i1, HttpConnectionParams httpConnectionParams)
                    throws IOException, ConnectTimeoutException {
                return factory.createSocket(string, i, inetAddress, i1);
            }

            public Socket createSocket(String string, int i) throws IOException {
                return factory.createSocket(string, i);
            }
        }, 443));
    }

    public ApacheAsyncHttpProvider(AsyncHttpClientConfig config) throws IOException {
        this.config = config;
        connectionManager = new MultiThreadedHttpConnectionManager();

        params = new HttpClientParams();
        params.setParameter(HttpMethodParams.SINGLE_COOKIE_HEADER, Boolean.TRUE);
        params.setCookiePolicy(CookiePolicy.BROWSER_COMPATIBILITY);
        params.setParameter(HttpMethodParams.RETRY_HANDLER, new DefaultHttpMethodRetryHandler(0, false));

        AsyncHttpProviderConfig<?, ?> providerConfig = config.getAsyncHttpProviderConfig();
        if (providerConfig != null && JDKAsyncHttpProviderConfig.class.isAssignableFrom(providerConfig.getClass())) {
            configure(ApacheAsyncHttpProviderConfig.class.cast(providerConfig));
        }
    }

    private void configure(ApacheAsyncHttpProviderConfig config) {
    }

    public <T> Future<T> execute(Request request, AsyncHandler<T> handler) throws IOException {
        if (isClose.get()) {
            throw new IOException("Closed");
        }

        if (config.getMaxTotalConnections() > -1 && (maxConnections.get() + 1) > config.getMaxTotalConnections()) {
            throw new IOException(String.format("Too many connections %s", config.getMaxTotalConnections()));
        }

        if (idleConnectionTimeoutThread != null) {
            idleConnectionTimeoutThread.shutdown();
            idleConnectionTimeoutThread = null;
        }

        int requestTimeout = requestTimeout(config, request.getPerRequestConfig());

        if (config.getIdleConnectionTimeoutInMs() > 0 && requestTimeout < config.getIdleConnectionTimeoutInMs()) {
            idleConnectionTimeoutThread = new IdleConnectionTimeoutThread();
            idleConnectionTimeoutThread.setConnectionTimeout(config.getIdleConnectionTimeoutInMs());
            idleConnectionTimeoutThread.addConnectionManager(connectionManager);
            idleConnectionTimeoutThread.start();
        }

        HttpClient httpClient = new HttpClient(params, connectionManager);

        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
        if (realm != null) {
            httpClient.getParams().setAuthenticationPreemptive(realm.getUsePreemptiveAuth());
            Credentials defaultcreds = new UsernamePasswordCredentials(realm.getPrincipal(), realm.getPassword());
            httpClient.getState().setCredentials(new AuthScope(null, -1, AuthScope.ANY_REALM), defaultcreds);
        }

        HttpMethodBase method = createMethod(httpClient, request);
        ApacheResponseFuture f = new ApacheResponseFuture<T>(handler, config.getRequestTimeoutInMs(), request, method);
        f.touch();

        f.setInnerFuture(config.executorService().submit(new ApacheClientRunnable(request, handler, method, f, httpClient)));
        maxConnections.incrementAndGet();
        return f;
    }

    public void close() {
        if (idleConnectionTimeoutThread != null) {
            idleConnectionTimeoutThread.shutdown();
            idleConnectionTimeoutThread = null;
        }
        if (connectionManager != null) {
            try {
                connectionManager.shutdown();
            } catch (Exception e) {
                logger.error("Error shutting down connection manager", e);
            }
        }
    }

    public Response prepareResponse(HttpResponseStatus status, HttpResponseHeaders headers, Collection<HttpResponseBodyPart> bodyParts) {
        return new ApacheResponse(status, headers, bodyParts);
    }

    private HttpMethodBase createMethod(HttpClient client, Request request) throws IOException, FileNotFoundException {
        String methodName = request.getReqType();
        HttpMethodBase method = null;
        if (methodName.equalsIgnoreCase("POST") || methodName.equalsIgnoreCase("PUT")) {
            EntityEnclosingMethod post = methodName.equalsIgnoreCase("POST") ? new PostMethod(request.getUrl()) : new PutMethod(request.getUrl());
            post.getParams().setContentCharset("UTF-8");
            if (request.getByteData() != null) {
                post.setRequestEntity(new ByteArrayRequestEntity(request.getByteData()));
                post.setRequestHeader("Content-Length", String.valueOf(request.getByteData().length));
            } else if (request.getStringData() != null) {
                post.setRequestEntity(new StringRequestEntity(request.getStringData(), "text/xml", "UTF-8"));
                post.setRequestHeader("Content-Length", String.valueOf(request.getStringData().length()));
            } else if (request.getStreamData() != null) {
                InputStreamRequestEntity r = new InputStreamRequestEntity(request.getStreamData());
                post.setRequestEntity(r);
                post.setRequestHeader("Content-Length", String.valueOf(r.getContentLength()));

            } else if (request.getParams() != null) {
                StringBuilder sb = new StringBuilder();
                for (final Map.Entry<String, List<String>> paramEntry : request.getParams()) {
                    final String key = paramEntry.getKey();
                    for (final String value : paramEntry.getValue()) {
                        if (sb.length() > 0) {
                            sb.append("&");
                        }
                        UTF8UrlEncoder.appendEncoded(sb, key);
                        sb.append("=");
                        UTF8UrlEncoder.appendEncoded(sb, value);
                    }
                }

                post.setRequestHeader("Content-Length", String.valueOf(sb.length()));
                post.setRequestEntity(new StringRequestEntity(sb.toString(), "text/xml", "UTF-8"));

                if (!request.getHeaders().containsKey("Content-Type")) {
                    post.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
                }
            } else if (request.getParts() != null) {
                MultipartRequestEntity mre = createMultipartRequestEntity(request.getParts(), post.getParams());
                post.setRequestEntity(mre);
                post.setRequestHeader("Content-Type", mre.getContentType());
                post.setRequestHeader("Content-Length", String.valueOf(mre.getContentLength()));
            } else if (request.getEntityWriter() != null) {
                post.setRequestEntity(new EntityWriterRequestEntity(request.getEntityWriter(), computeAndSetContentLength(request, post)));
            } else if (request.getFile() != null) {
                File file = request.getFile();
                if (!file.isFile()) {
                    throw new IOException(String.format(Thread.currentThread()
                            + "File %s is not a file or doesn't exist", file.getAbsolutePath()));
                }
                post.setRequestHeader("Content-Length", String.valueOf(file.length()));

                FileInputStream fis = new FileInputStream(file);
                try {
                    InputStreamRequestEntity r = new InputStreamRequestEntity(fis);
                    post.setRequestEntity(r);
                    post.setRequestHeader("Content-Length", String.valueOf(r.getContentLength()));
                } finally {
                    fis.close();
                }
            } else if (request.getBodyGenerator() != null) {
                Body body = request.getBodyGenerator().createBody();
                try {
                    int length = (int) body.getContentLength();
                    if (length < 0) {
                        length = (int) request.getLength();
                    }
                    if (length >= 0) {
                        post.setRequestHeader("Content-Length", String.valueOf(length));
                    }
                    // This is totally sub optimal
                    byte[] bytes = new byte[length];
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    for (;;) {
                        buffer.clear();
                        if (body.read(buffer) < 0) {
                            break;
                        }
                    }
                    post.setRequestEntity(new ByteArrayRequestEntity(bytes));
                } finally {
                    try {
                        body.close();
                    } catch (IOException e) {
                        logger.warn( e, "Failed to close request body: %s", e.getMessage() );
                    }
                }
            }

            method = post;
        } else if (methodName.equalsIgnoreCase("DELETE")) {
            method = new DeleteMethod(request.getUrl());
        } else if (methodName.equalsIgnoreCase("HEAD")) {
            method = new HeadMethod(request.getUrl());
        } else if (methodName.equalsIgnoreCase("GET")) {
            method = new GetMethod(request.getUrl());
        } else if (methodName.equalsIgnoreCase("OPTIONS")) {
            method = new OptionsMethod(request.getUrl());
        } else {
            throw new IllegalStateException(String.format("Invalid Method", methodName));
        }

        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
        if (proxyServer != null) {
            ProxyHost proxyHost = proxyServer == null ? null : new ProxyHost(proxyServer.getHost(), proxyServer.getPort());
            client.getHostConfiguration().setProxyHost(proxyHost);
        }

        method.setFollowRedirects(config.isRedirectEnabled());
        if ((request.getCookies() != null) && !request.getCookies().isEmpty()) {
            for (Cookie cookie : request.getCookies()) {
                method.setRequestHeader("Cookie", AsyncHttpProviderUtils.encodeCookies(request.getCookies()));
            }
        }

        if (request.getHeaders() != null) {
            for (String name : request.getHeaders().keySet()) {
                if (!"host".equalsIgnoreCase(name)) {
                    for (String value : request.getHeaders().get(name)) {
                        method.setRequestHeader(name, value);
                    }
                }
            }
        }

        if (request.getHeaders().getFirstValue("User-Agent") == null && config.getUserAgent() != null) {
            method.setRequestHeader("User-Agent", config.getUserAgent() + " (ApacheAsyncHttpProvider)");
        }

        if (config.isCompressionEnabled()) {
            Header acceptableEncodingHeader = method.getRequestHeader("Accept-Encoding");
            if (acceptableEncodingHeader != null) {
                String acceptableEncodings = acceptableEncodingHeader.getValue();
                if (acceptableEncodings.indexOf("gzip") == -1) {
                    StringBuilder buf = new StringBuilder(acceptableEncodings);
                    if (buf.length() > 1) {
                        buf.append(",");
                    }
                    buf.append("gzip");
                    method.setRequestHeader("Accept-Encoding", buf.toString());
                }
            } else {
                method.setRequestHeader("Accept-Encoding", "gzip");
            }
        }

        if (request.getVirtualHost() != null) {
            method.getParams().setVirtualHost(request.getVirtualHost());
        }
        return method;
    }

    private final static int computeAndSetContentLength(Request request, HttpMethodBase m) {
        int lenght = (int) request.getLength();
        if (lenght == -1 && m.getRequestHeader("Content-Length") != null) {
            lenght = Integer.valueOf(m.getRequestHeader("Content-Length").getValue());
        }

        if (lenght != -1) {
            m.setRequestHeader("Content-Length", String.valueOf(lenght));
        }
        return lenght;
    }

    public class ApacheClientRunnable<T> implements Callable<T> {

        private final AsyncHandler<T> asyncHandler;
        private HttpMethodBase method;
        private final ApacheResponseFuture<T> future;
        private Request request;
        private final HttpClient httpClient;
        private int currentRedirectCount;
        private AtomicBoolean isAuth = new AtomicBoolean(false);
        private byte[] cachedBytes;
        private int cachedBytesLenght;

        public ApacheClientRunnable(Request request, AsyncHandler<T> asyncHandler, HttpMethodBase method, ApacheResponseFuture<T> future, HttpClient httpClient) {
            this.asyncHandler = asyncHandler;
            this.method = method;
            this.future = future;
            this.request = request;
            this.httpClient = httpClient;
        }

        public T call() {
            AsyncHandler.STATE state = AsyncHandler.STATE.ABORT;
            try {
                URI uri = null;
                try {
                    uri = AsyncHttpProviderUtils.createUri(request.getRawUrl());
                } catch (IllegalArgumentException u) {
                    uri = AsyncHttpProviderUtils.createUri(request.getUrl());
                }

                int delay = requestTimeout(config, future.getRequest().getPerRequestConfig());
                if (delay != -1) {
                    ReaperFuture reaperFuture = new ReaperFuture(future);
                    Future scheduledFuture = config.reaper().scheduleAtFixedRate(reaperFuture, 0, delay, TimeUnit.MILLISECONDS);
                    reaperFuture.setScheduledFuture(scheduledFuture);
                    future.setReaperFuture(reaperFuture);
                }

                int statusCode = httpClient.executeMethod(method);
                
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format(currentThread()
                            + "\n\nRequest %s\n\nResponse %s\n", request.toString(), method.toString()));
                }

                boolean redirectEnabled = (request.isRedirectEnabled() || config.isRedirectEnabled());
                if (redirectEnabled && (statusCode == 302 || statusCode == 301)) {

                    isAuth.set(false);

                    if (currentRedirectCount++ < config.getMaxRedirects()) {
                        String location = method.getResponseHeader("Location").getValue();
                        if (location.startsWith("/")) {
                            location = AsyncHttpProviderUtils.getBaseUrl(uri) + location;
                        }

                        if (!location.equals(uri.toString())) {
                            URI newUri = AsyncHttpProviderUtils.createUri(location);

                            RequestBuilder builder = new RequestBuilder(request);
                            String newUrl = newUri.toString();

                            if (logger.isDebugEnabled()) {
                                logger.debug(String.format(AsyncHttpProviderUtils.currentThread() + "Redirecting to %s", newUrl));
                            }
                            request = builder.setUrl(newUrl).build();
                            method = createMethod(httpClient, request);
                            return call();
                        }
                    } else {
                        throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
                    }
                }

                state = asyncHandler.onStatusReceived(new ApacheResponseStatus(uri, method, ApacheAsyncHttpProvider.this));
                if (state == AsyncHandler.STATE.CONTINUE) {
                    state = asyncHandler.onHeadersReceived(new ApacheResponseHeaders(uri, method, ApacheAsyncHttpProvider.this));
                }

                if (state == AsyncHandler.STATE.CONTINUE) {
                    InputStream is = method.getResponseBodyAsStream();
                    if (is != null) {
                        Header h = method.getResponseHeader("Content-Encoding");
                        if (h != null) {
                            String contentEncoding = h.getValue();
                            boolean isGZipped = contentEncoding == null ? false : "gzip".equalsIgnoreCase(contentEncoding);
                            if (isGZipped) {
                                is = new GZIPInputStream(is);
                            }
                        }

                        int[] lengthWrapper = new int[1];
                        byte[] bytes = AsyncHttpProviderUtils.readFully(is, lengthWrapper);
                        if (lengthWrapper[0] > 0) {
                            byte[] body = new byte[lengthWrapper[0]];
                            System.arraycopy(bytes, 0, body, 0, lengthWrapper[0]);
                            future.touch();
                            asyncHandler.onBodyPartReceived(new ApacheResponseBodyPart(uri, body, ApacheAsyncHttpProvider.this));
                        }
                    }
                }

                if (ProgressAsyncHandler.class.isAssignableFrom(asyncHandler.getClass())) {
                    ProgressAsyncHandler.class.cast(asyncHandler).onHeaderWriteCompleted();
                    ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteCompleted();
                }

                try {
                    return asyncHandler.onCompleted();
                } catch (Throwable t) {
                    RuntimeException ex = new RuntimeException();
                    ex.initCause(t);
                    throw ex;
                }
            } catch (Throwable t) {
                if (method.isAborted()) {
                    return null;
                }

                if (logger.isDebugEnabled()) {
                    logger.debug(t);
                }

                try {
                    future.abort(filterException(t));
                } catch (Throwable t2) {
                    logger.error(t2);
                }
            } finally {
                if (config.getMaxTotalConnections() != -1) {
                    maxConnections.decrementAndGet();
                }
                future.done(null);
                method.releaseConnection();
            }
            return null;
        }

        private Throwable filterException(Throwable t) {
            if (UnknownHostException.class.isAssignableFrom(t.getClass())) {
                t = new ConnectException(t.getMessage());
            }

            if (NoHttpResponseException.class.isAssignableFrom(t.getClass())) {
                int responseTimeoutInMs = config.getRequestTimeoutInMs();

                if (request.getPerRequestConfig() != null && request.getPerRequestConfig().getRequestTimeoutInMs() != -1) {
                    responseTimeoutInMs = request.getPerRequestConfig().getRequestTimeoutInMs();
                }
                t = new TimeoutException(String.format("No response received after %s", responseTimeoutInMs));
            }

            if (SSLHandshakeException.class.isAssignableFrom(t.getClass())) {
                Throwable t2 = new ConnectException();
                t2.initCause(t);
                t = t2;
            }

            return t;
        }
    }


    final static String currentThread() {
        return AsyncHttpProviderUtils.currentThread();
    }


    private MultipartRequestEntity createMultipartRequestEntity(List<Part> params, HttpMethodParams methodParams) throws FileNotFoundException {
        org.apache.commons.httpclient.methods.multipart.Part[] parts = new org.apache.commons.httpclient.methods.multipart.Part[params.size()];
        int i = 0;

        for (Part part : params) {
            if (part instanceof StringPart) {
                parts[i] = new org.apache.commons.httpclient.methods.multipart.StringPart(part.getName(),
                        ((StringPart) part).getValue(),
                        "UTF-8");
            } else if (part instanceof FilePart) {
                parts[i] = new org.apache.commons.httpclient.methods.multipart.FilePart(part.getName(),
                        ((FilePart) part).getFile(),
                        ((FilePart) part).getMimeType(),
                        ((FilePart) part).getCharSet());

            } else if (part instanceof ByteArrayPart) {
                PartSource source = new ByteArrayPartSource(((ByteArrayPart) part).getFileName(), ((ByteArrayPart) part).getData());
                parts[i] = new org.apache.commons.httpclient.methods.multipart.FilePart(part.getName(),
                        source,
                        ((ByteArrayPart) part).getMimeType(),
                        ((ByteArrayPart) part).getCharSet());

            } else if (part == null) {
                throw new NullPointerException("Part cannot be null");
            } else {
                throw new IllegalArgumentException(String.format("Unsupported part type for multipart parameter %s",
                        part.getName()));
            }
            ++i;
        }
        return new MultipartRequestEntity(parts, methodParams);
    }

    public class EntityWriterRequestEntity implements org.apache.commons.httpclient.methods.RequestEntity {
        private Request.EntityWriter entityWriter;
        private long contentLength;

        public EntityWriterRequestEntity(Request.EntityWriter entityWriter, long contentLength) {
            this.entityWriter = entityWriter;
            this.contentLength = contentLength;
        }

        public long getContentLength() {
            return contentLength;
        }

        public String getContentType() {
            return null;
        }

        public boolean isRepeatable() {
            return false;
        }

        public void writeRequest(OutputStream out) throws IOException {
            entityWriter.writeEntity(out);
        }
    }

    private static class TrustingSSLSocketFactory extends SSLSocketFactory {
        private SSLSocketFactory delegate;

        private TrustingSSLSocketFactory() {
            try {
                SSLContext sslcontext = SSLContext.getInstance("SSL");

                sslcontext.init(null, new TrustManager[]{new TrustEveryoneTrustManager()}, new SecureRandom());
                delegate = sslcontext.getSocketFactory();
            } catch (KeyManagementException e) {
                throw new IllegalStateException();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException();
            }
        }

        @Override
        public Socket createSocket(String s, int i) throws IOException, UnknownHostException {
            return delegate.createSocket(s, i);
        }

        @Override
        public Socket createSocket(String s, int i, InetAddress inetAddress, int i1) throws IOException, UnknownHostException {
            return delegate.createSocket(s, i, inetAddress, i1);
        }

        @Override
        public Socket createSocket(InetAddress inetAddress, int i) throws IOException {
            return delegate.createSocket(inetAddress, i);
        }

        @Override
        public Socket createSocket(InetAddress inetAddress, int i, InetAddress inetAddress1, int i1) throws IOException {
            return delegate.createSocket(inetAddress, i, inetAddress1, i1);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket socket, String s, int i, boolean b) throws IOException {
            return delegate.createSocket(socket, s, i, b);
        }
    }

    private static class TrustEveryoneTrustManager implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            // do nothing
        }

        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            // do nothing
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private final class ReaperFuture implements Future, Runnable {
        private Future scheduledFuture;
        private ApacheResponseFuture<?> apacheResponseFuture;

        public ReaperFuture(ApacheResponseFuture<?> apacheResponseFuture) {
            this.apacheResponseFuture = apacheResponseFuture;
        }

        public void setScheduledFuture(Future scheduledFuture) {
            this.scheduledFuture = scheduledFuture;
        }

        /**
         * @Override
         */
        public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            //cleanup references to allow gc to reclaim memory independently
            //of this Future lifecycle
            this.apacheResponseFuture = null;
            return this.scheduledFuture.cancel(mayInterruptIfRunning);
        }

        /**
         * @Override
         */
        public Object get() throws InterruptedException, ExecutionException {
            return this.scheduledFuture.get();
        }

        /**
         * @Override
         */
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException,
                TimeoutException {
            return this.scheduledFuture.get(timeout, unit);
        }

        /**
         * @Override
         */
        public boolean isCancelled() {
            return this.scheduledFuture.isCancelled();
        }

        /**
         * @Override
         */
        public boolean isDone() {
            return this.scheduledFuture.isDone();
        }

        /**
         * @Override
         */
        public synchronized void run() {
            if (this.apacheResponseFuture != null && this.apacheResponseFuture.hasExpired()) {
                if (logger.isDebugEnabled()) {
                    logger.debug(currentThread() + "Request Timeout expired for " + this.apacheResponseFuture);
                }
                int requestTimeout = config.getRequestTimeoutInMs();
                PerRequestConfig p = this.apacheResponseFuture.getRequest().getPerRequestConfig();
                if (p != null && p.getRequestTimeoutInMs() != -1) {
                    requestTimeout = p.getRequestTimeoutInMs();
                }
                apacheResponseFuture.abort(new TimeoutException(String.format("No response received after %s", requestTimeout)));

                this.apacheResponseFuture = null;
            }
        }
    }

    protected static int requestTimeout(AsyncHttpClientConfig config, PerRequestConfig perRequestConfig) {
        int result;
        if (perRequestConfig != null) {
            int prRequestTimeout = perRequestConfig.getRequestTimeoutInMs();
            result = (prRequestTimeout != 0 ? prRequestTimeout : config.getRequestTimeoutInMs());
        } else {
            result = config.getRequestTimeoutInMs();
        }
        return result;
    }
}