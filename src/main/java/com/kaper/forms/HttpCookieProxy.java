package com.kaper.forms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * This clas contains an HTTP proxy. It can talk to http or https servers, and it's main purpose is to monitor for any cookies going to and from the server,
 * put them in a local cookie-jar (hashmap), and keep sending them to the target server, even if the caller does not pass them on anymore.
 * We need this for the Oracle Forms Applet. It's native network layer does not handle cookies, as it was used to run inside a browser which handled that.
 * No browser is available here, but this proxy will take over the role of handling the cookies now.
 * Note: we need the cookies mainly for a possible load-balanced forms server setup. E.g. for us, the dev, test and acceptance servers do not use load-balancing, and
 * can function without this proxy, but the production system uses a load balancing setup. However, it is cleaner to use this proxy also for the single server
 * instances, as those do try to set balancing cookies as well, and without this proxy they keep generating new ones and send them on every response.
 * Note: ALL cookies are handled as if they are SESSION cookies. So we do not keep a persistent state across restarts.
 * You can pass in a list of proxy overrides, to return local files when certain target paths are matched.
 * Format for overrides: URLREGEX:LOCALFILE or URLREGEX:LOCALFILE:CONTENTTYPE, where URLREGEX is a regex to match the original url path+querystring.
 * Example: .*Registry.dat.*:MyRegistry.dat:text/plain
 */
public class HttpCookieProxy {
    /** The main proxy thread. */
    private transient Thread proxyThread = null;

    /** Flag indicating if we should keep running, or if we should stop. */
    private transient boolean keepRunning = true;

    /** Our local cookie storage. Only access this via the putCookieInCookieJar and getAllCookiesFromCookieJar methods (for synchronization). */
    private final Map<String, String> cookieJarStorage = new HashMap<>();

    /** List of files to serve from local filesystem, instead of proxying them. */
    private final List<ProxyOverride> proxyOverrides = new ArrayList<>();

    /**
     * This "main" method is mainly for testing/debugging purposes. The proxy will be instantiated from the OracleFormsRunner class.
     * Does not need "manual" start.
     * <pre>
     *     Usage: HttpCookieProxy [target-base-url] [local-port] [-v]
     *     Where the optional -v will enable debug logging mode.
     * </pre>
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            Logger.logInfo(
                    "HttpCookieProxy::main; Pass on two or more arguments: 'http(s)://targethost/ <localport-number>' or '-v http(s)://targethost/ <localport-number>', and add ant proxy file overrides after the port");
            Logger.logInfo(
                    "Format for overrides: URLREGEX:LOCALFILE or URLREGEX:LOCALFILE:CONTENTTYPE, where URLREGEX is a regex to match the original url path+querystring. Example: '.*Registry.dat.*:MyRegistry.dat:text/plain'");
            return;
        }
        // Verbose logging?
        boolean verbose = args.length > 2 && args[0].equalsIgnoreCase("-v");
        Logger.setDebugEnabled(verbose);
        List<String> localProxyFiles = Arrays.asList(args).subList((verbose ? 3 : 2), args.length);

        // This starts a new thread with a running proxy, and returns immediately.
        HttpCookieProxy proxy = new HttpCookieProxy(args[verbose ? 1 : 0], Integer.parseInt(args[verbose ? 2 : 1]), localProxyFiles);
        proxy.waitForProxyThreadToStop();
    }

    /**
     * Create and start a cookie retaining proxy on the given localPort to the given targetBaseUrl.
     *
     * @param targetBaseUrl destination "http[s]://host:port/" (path will be ignored)
     * @param localPort     local listening port, will only listen on 127.0.0.1 as we do not want it publicly open.
     * @throws IOException when either local port is already in use, or a silly (unparsable) targetBaseUrl is passed on.
     */
    public HttpCookieProxy(String targetBaseUrl, int localPort, List<String> localProxyFiles) throws IOException {
        URL url = new URL(targetBaseUrl);
        boolean isHttps = url.getProtocol().equalsIgnoreCase("https");
        int port = url.getPort();
        if (port == -1) {
            port = isHttps ? 443 : 80;
        }
        parseProxyOverrideConfig(localProxyFiles);
        runServer(isHttps, url.getHost(), port, localPort);
    }

    /**
     * Split proxy override values into objects.
     * Input values look like: URLREGEX:LOCALFILE:CONTENTTYPE (colon separated values).
     *
     * @param localProxyFiles list of overrides.
     */
    private void parseProxyOverrideConfig(List<String> localProxyFiles) {
        localProxyFiles.stream()
                .map(entry -> entry.split(":"))
                .filter(parts -> parts.length >= 2 && parts.length <= 3)
                .map(parts -> new ProxyOverride(parts[0], parts[1], parts.length > 2 ? parts[2] : null))
                .forEach(proxyOverrides::add);
    }

    /**
     * Can be called from external starter, to terminate the proxy.
     * It will take max 1 second before it stops.
     */
    public void terminateProxy() {
        keepRunning = false;
        Logger.logDebug("Called HttpCookieProxy::terminateProxy");
    }

    /**
     * Can be called from external starter, to wait for proxy to have terminated.
     */
    public void waitForProxyThreadToStop() {
        Logger.logDebug("Waiting for proxy to stop...");
        try {
            proxyThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        Logger.logInfo("Proxy stopped...");
    }

    /**
     * Start the proxy server in a new thread.
     *
     * @throws IOException if local port is already in use.
     */
    public void runServer(boolean serverUsesSsl, String host, int remoteport, int localport) throws IOException {
        Logger.logInfo("Starting cookie retaining proxy for " + (serverUsesSsl ? "https://" : "http://") + host + ":" + remoteport + "/ on local port " + localport);
        // Creating a ServerSocket to listen for connections with. Only listen on localhost, as we do not want to expose this to the outside.
        ServerSocket localServerSocket = new ServerSocket(localport, 0, InetAddress.getByName("127.0.0.1"));
        // Set timeout to let "accept()" break out every so often, to check for our keepRunning state.
        localServerSocket.setSoTimeout(1000);

        // Start the proxy in a new thread (as this is what we need from the caller).
        proxyThread = new Thread(() -> {
            Thread.currentThread().setName("Proxy-Listener");
            while (keepRunning) {
                try {
                    // wait for a connection on the local port
                    Socket client = localServerSocket.accept();
                    // handle the request in a new thread to allow us to do multiple at the same time
                    Thread t = new Thread(() -> handleRequest(client, serverUsesSsl, host, remoteport));
                    t.start();
                } catch (SocketTimeoutException ste) {
                    // no worries, this is a planned timeout, just to allow us to check the keepRunning regularly
                } catch (IOException e) {
                    Logger.logInfo("Error listening to port? " + e.getMessage());
                    keepRunning = false;
                    throw new RuntimeException(e);
                }
            }
        });
        proxyThread.start();
    }

    /**
     * Handle one request.
     */
    private void handleRequest(Socket client, boolean serverUsesSsl, String host, int remoteport) {
        Thread.currentThread().setName("Proxy-Thread-" + client.getPort() + "-A");
        Logger.logDebug("Incoming request " + client.getPort());
        Socket server = null;
        try {
            final InputStream streamFromClient = client.getInputStream();
            final OutputStream streamToClient = client.getOutputStream();

            // Read request headers, and pre-process them to be passed on to the real server (request body NOT handled yet!)
            List<String> lines = processRequestHeaders(client, host, remoteport, streamFromClient);

            // Check if we do NOT want to proxy the request, but for one or more files return a local file instead...
            // We can use this to override the Registry.dat oracle forms config file if needed.
            if (handleProxyFileOverrides(streamToClient, lines)) {
                try {
                    client.close();
                } catch (IOException e) {
                    // ignore
                }
                return;
            }

            // Connect to target server.
            try {
                if (!serverUsesSsl) {
                    // non-secure channel.
                    server = new Socket(host, remoteport);
                } else {
                    // setup secure channel.
                    server = SSLSocketFactory.getDefault().createSocket(host, remoteport);
                    ((SSLSocket) server).startHandshake();
                }
            } catch (IOException e) {
                // Let's log an error, and send back a status 502 proxy error message.
                String message = "Proxy server cannot connect to " + (serverUsesSsl ? "https://" : "http://") + host + ":" + remoteport + "/... " + e.getMessage();
                Logger.logInfo(message);
                message = "Error 502; " + message + "\r\n";
                PrintWriter out = new PrintWriter(streamToClient);
                out.print("HTTP/1.1 502 Proxy-Error\r\nContent-Type: text/html\r\nContent-Length: " + message.getBytes().length + "\r\n\r\n" + message);
                out.flush();
                client.close();
                return;
            }

            // Get server streams.
            final InputStream streamFromServer = server.getInputStream();
            final OutputStream streamToServer = server.getOutputStream();

            // Send data to server
            Thread t = new Thread(() -> {
                Thread.currentThread().setName("Proxy-Thread-" + client.getPort() + "-B");

                // pass on the header block to the server
                sendHeaderLines(streamToServer, lines, ">");

                // send body (if any)
                copyBodyStream(streamFromClient, streamToServer, ">");

                try {
                    streamToServer.close();
                } catch (IOException e) {
                    Logger.logDebug("end send body: " + e.getMessage());
                }
                Logger.logDebug("send done");
            });

            // Start the client-to-server request thread running
            t.start();

            // Read the server's responses
            // and pass them back to the client.

            // get header lines
            List<String> linesRead = readHeaderLines(streamFromServer);

            // find any "Set-Cookie" lines, and store in cookie-jar for use on next request
            collectSetCookieHeaders(linesRead);

            // pass on the header block to the client
            sendHeaderLines(streamToClient, linesRead, "<");

            // pass on optional body
            copyBodyStream(streamFromServer, streamToClient, "<");
            // The server closed its connection to us, so we close our
            // connection to our client.
            streamToClient.close();
        } catch (IOException e) {
            Logger.logInfo(e.getMessage());
        } finally {
            try {
                if (server != null)
                    server.close();
                if (client != null)
                    client.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * Check proxyOverrides config for matching request. If found, return a local file, instead of proxying to server.
     *
     * @return TRUE if local file was returned (no server call needed), FALSE if the request should go to the server.
     */
    private boolean handleProxyFileOverrides(OutputStream streamToClient, List<String> lines) {
        // Parse first line to get the target path AND query string.
        String request = lines.get(0).replaceAll("^[^ ]+ ([^ ]+) .*$", "$1");
        Logger.logDebug("Request: " + request);

        for (ProxyOverride proxyOverride : proxyOverrides) {
            if (request.matches(proxyOverride.requestRegex)) {
                Logger.logInfo("Request: " + lines.get(0) + ", matches: " + proxyOverride.requestRegex + ", returning local file (not proxied): " + proxyOverride.localFile);
                String fileData;
                try {
                    fileData = new String(Files.readAllBytes(Paths.get(proxyOverride.localFile)));
                } catch (IOException e) {
                    Logger.logInfo("Error reading local file: " + e.getMessage() + " (working directory: " + System.getProperty("user.dir") + ") - request will be proxied");
                    return false;
                }
                PrintWriter out = new PrintWriter(streamToClient);
                out.print("HTTP/1.1 200 OK\r\nContent-Length: " + fileData.getBytes().length + "\r\n");
                if (proxyOverride.contentType != null) {
                    out.print("ContentType: " + proxyOverride.contentType + "\r\n");
                }
                out.print("\r\n" + fileData);
                out.flush();
                return true;
            }
        }
        return false;
    }

    /**
     * Read request headers, and prepare some updates in them for proxying on to the server (e.g. handle cookies).
     *
     * @return list of headers to pass on to server.
     */
    private List<String> processRequestHeaders(Socket client, String host, int remoteport, InputStream streamFromClient) {
        // get header lines
        changeReadTimeout(client, 1000);
        List<String> lines = new ArrayList<>(readHeaderLines(streamFromClient));

        // remove http(s)://host:port/ from first line
        fixRequestMethodLine(lines);

        // replace host header with new value
        removeHeaders(lines, "host");
        lines.add("Host: " + host + ":" + remoteport);

        // remove Connection: line
        removeHeaders(lines, "connection");

        // add close after single use command (we do not want connection re-use to keep this proxy simpler)
        lines.add("Connection: close");

        // find any "Cookie" lines, and store in cookie-jar
        collectSetCookieHeaders(lines);

        // remove Cookie: lines
        removeHeaders(lines, "cookie");

        // add cookies from our cookie storage
        addCookieHeaders(lines);

        changeReadTimeout(client, 30000);
        return lines;
    }

    /**
     * Change timeout on incoming streams, to make sure we do not hang forever if content-length for example is wrong.
     */
    private static void changeReadTimeout(Socket client, int timeoutMillis) {
        try {
            client.setSoTimeout(timeoutMillis);
        } catch (SocketException e) {
            Logger.logInfo("Error setting timeout? " + e.getMessage());
        }
    }

    /**
     * Read input stream up to and including "\r\n\r\n", e.g. the end of header block with the empty line.
     * We do not close the stream, to allow a following stream copy loop to copy the message BODY.
     *
     * @param stream input
     * @return list of header lines
     */
    private List<String> readHeaderLines(InputStream stream) {
        int bytesRead;
        final byte[] fullRequest = new byte[1_000_000]; // max 1 MB to fit full request
        int fullBytesRead = 0;
        try {
            // read the stream byte by byte (ugly, but I guess the OS will cache the real incoming network packet anyway).
            while ((bytesRead = stream.read(fullRequest, fullBytesRead, 1)) != -1) {
                fullBytesRead += bytesRead;
                // Check last 4 bytes read for "\r\n\r\n"
                if (fullBytesRead > 4 && (fullRequest[fullBytesRead - 4] == '\r'
                        && fullRequest[fullBytesRead - 3] == '\n' && fullRequest[fullBytesRead - 2] == '\r'
                        && fullRequest[fullBytesRead - 1] == '\n')) {
                    // do we have two newlines? if so, we have the full header!
                    Logger.logDebug("Header size: " + fullBytesRead);
                    return Arrays.asList(new String(fullRequest, 0, fullBytesRead).split("\r\n"));
                }
            }
            Logger.logInfo("--- end of stream --- eof?");
        } catch (IOException e) {
            Logger.logInfo("--- end of stream --- " + e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Copy body data of the http request or response.
     */
    private void copyBodyStream(InputStream inputStream, OutputStream outputStream, String direction) {
        byte[] reply = new byte[10 * 1024 * 1024]; // 10 MB buffer
        int totalBytes = 0;
        int totalPackages = 0;
        try {
            int bytesRead;
            Logger.logDebug(direction + " body copy");
            while ((bytesRead = inputStream.read(reply)) != -1) {
                totalBytes += bytesRead;
                totalPackages++;
                outputStream.write(reply, 0, bytesRead);
                outputStream.flush();
            }
            Logger.logDebug(direction + " copied: " + totalBytes + " bytes in " + totalPackages + " chunks");
            Logger.logDebug(direction + " end of body - eof");
        } catch (IOException e) {
            Logger.logDebug(direction + " end of body - " + e.getMessage());
        }
    }

    /**
     * If this proxy is used as a "real" http proxy, then the request line will contain the target base url.
     * Here we strip that off again. Note: this is not actually needed for the Oracle Forms Proxy, as in there
     * I will just replace the target URL by a URL pointing to this proxy.
     */
    private void fixRequestMethodLine(List<String> lines) {
        String[] splitFirstLine = lines.get(0).split(" ");
        lines.set(0, splitFirstLine[0] + " " + (splitFirstLine[1].replaceFirst("https?://[^/]+/", "/")));
        if (splitFirstLine.length > 2) {
            for (int i = 2; i < splitFirstLine.length; i++) {
                lines.set(0, lines.get(0) + " " + splitFirstLine[i]);
            }
        }
    }

    /**
     * Find given header name, and remove it from the list.
     */
    private void removeHeaders(List<String> lines, String filterHeader) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (!line.toLowerCase().startsWith(filterHeader + ":")) {
                result.add(line);
            } else {
                Logger.logDebug("Removing header: " + line);
            }
        }
        lines.clear();
        lines.addAll(result);
    }

    /**
     * Send header lines to client or server.
     */
    private void sendHeaderLines(OutputStream stream, List<String> lines, String direction) {
        StringBuilder data = new StringBuilder();
        for (String line : lines) {
            Logger.logDebug(direction + " " + line);
            data.append(line).append("\r\n");
        }
        data.append("\r\n");
        try {
            stream.write(data.toString().getBytes());
            stream.flush();
        } catch (IOException e) {
            Logger.logInfo(direction + " header send error? - " + e.getMessage());
        }
    }

    /**
     * Read through header lines, and find all "Cookie:" and/or "Set-Cookie:" headers.
     * Store the found cookies in our cookie-jar. The main purpose of this proxy ;-)
     */
    private void collectSetCookieHeaders(List<String> lines) {
        for (String line : lines) {
            if (line.toLowerCase().startsWith("set-cookie: ") || line.toLowerCase().startsWith("cookie: ")) {
                String headerName = line.replaceFirst(": .*", ": ");
                String headerValue = line.substring(headerName.length());
                if (headerName.toLowerCase().startsWith("set")) {
                    // set-cookie: This only set's a single cookie in one go...
                    String key = line.substring(headerName.length()).replaceFirst("=.*", "");
                    String value = line.substring(headerName.length() + key.length() + 1).replaceFirst(";.*", "");
                    Logger.logInfo("Update Cookie Jar: key:" + key + ", value:" + value);
                    putCookieInCookieJar(key.trim(), value.trim());
                } else {
                    // cookie: This can pass on ONE or MORE cookies in one go... Split on "; ".
                    for (String part : headerValue.split("; ")) {
                        String key = part.replaceFirst("=.*", "");
                        String value = part.substring(key.length() + 1);
                        Logger.logInfo("Update Cookie Jar: key:" + key + ", value:" + value);
                        putCookieInCookieJar(key.trim(), value.trim());
                    }
                }
            }
        }
    }

    /**
     * Append all cookies we have in our cookie-jar to the server header list.
     * The main purpose of this proxy ;-)
     */
    private void addCookieHeaders(List<String> lines) {
        StringBuilder cookies = new StringBuilder();
        for (Map.Entry<String, String> entry : getAllCookiesFromCookieJar()) {
            cookies.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
        }
        if (cookies.length() > 0) {
            String cookieHeader = "Cookie: " + cookies.toString().replaceFirst("; $", "");
            Logger.logDebug("Add; " + cookieHeader);
            lines.add(cookieHeader);
        }
    }

    /**
     * Synchronize access to the cookie jar, as we can have multiple requests/threads at the same time.
     */
    public void putCookieInCookieJar(String key, String value) {
        synchronized (cookieJarStorage) {
            cookieJarStorage.put(key, value);
        }
    }

    /**
     * Synchronize access to the cookie jar, as we can have multiple requests/threads at the same time.
     */
    public Set<Map.Entry<String, String>> getAllCookiesFromCookieJar() {
        synchronized (cookieJarStorage) {
            return new HashMap<>(cookieJarStorage).entrySet();
        }
    }
}
