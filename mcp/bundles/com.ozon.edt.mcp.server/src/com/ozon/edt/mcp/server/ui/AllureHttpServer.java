/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: tiny loopback HTTP server serving a generated Allure report.
 */

package com.ozon.edt.mcp.server.ui;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.ozon.edt.mcp.server.Activator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Serves a generated Allure report directory to the SWT {@code Browser} over
 * loopback (127.0.0.1) on a random free port. Allure's report root needs no
 * server-side logic — it is a static tree — so a {@link com.sun.net.httpserver
 * jdk.httpserver} static-file handler is enough. The server is a process-wide
 * singleton reused across opens: opening a report already being served is a
 * no-op (returns the same base URL).
 */
public final class AllureHttpServer
{
    private static final String HOST = "127.0.0.1"; //$NON-NLS-1$

    private static final AllureHttpServer INSTANCE = new AllureHttpServer();

    private HttpServer server;
    private Path root;
    private int port;

    private AllureHttpServer()
    {
    }

    /** Returns the process-wide server. */
    public static AllureHttpServer getInstance()
    {
        return INSTANCE;
    }

    /**
     * Makes {@code reportDir} reachable over loopback, reusing a running server
     * when it already serves the same directory.
     *
     * @param reportDir generated report dir (must contain {@code index.html})
     * @return the base URL, e.g. {@code http://127.0.0.1:43210/}
     * @throws IOException when the directory is missing or the server cannot start
     */
    public synchronized String start(Path reportDir) throws IOException
    {
        if (reportDir == null || !Files.isDirectory(reportDir))
        {
            throw new IOException("No such report dir: " + reportDir); //$NON-NLS-1$
        }
        if (server != null && root != null
            && root.toAbsolutePath().equals(reportDir.toAbsolutePath()))
        {
            return baseUrl();
        }
        stop();
        this.root = reportDir.toAbsolutePath();
        this.server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
        this.port = server.getAddress().getPort();
        server.createContext("/", this::serve); //$NON-NLS-1$
        server.start();
        Activator.logInfo("Allure report HTTP server on http://" + HOST + ":" + port + " serving " + root); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return baseUrl();
    }

    /** @return the base URL when serving, else {@code null}. */
    public synchronized String baseUrl()
    {
        return server == null ? null : "http://" + HOST + ":" + port + "/"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** @return the bound loopback port, or 0 when not serving. */
    public synchronized int port()
    {
        return port;
    }

    /** @return the absolute report dir currently being served, or {@code null}. */
    public synchronized Path root()
    {
        return root;
    }

    /** Stops the server (if running). Safe to call repeatedly. */
    public synchronized void stop()
    {
        if (server != null)
        {
            server.stop(0);
            server = null;
            root = null;
            port = 0;
        }
    }

    private void serve(HttpExchange exchange) throws IOException
    {
        String raw = exchange.getRequestURI().getPath();
        String name = (raw == null || raw.isEmpty() || "/".equals(raw)) ? "index.html" : raw; //$NON-NLS-1$ //$NON-NLS-2$
        if (name.startsWith("/")) //$NON-NLS-1$
        {
            name = name.substring(1);
        }
        Path file = root.resolve(name).normalize();
        // Guard against path traversal out of the report root.
        if (!file.startsWith(root) || !Files.isRegularFile(file))
        {
            send(exchange, 404, "text/plain", "Not found".getBytes(java.nio.charset.StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        byte[] body = Files.readAllBytes(file);
        send(exchange, 200, contentType(file), body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
        throws IOException
    {
        exchange.getResponseHeaders().set("Content-Type", contentType); //$NON-NLS-1$
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody())
        {
            os.write(body);
        }
    }

    private static String contentType(Path file)
    {
        String n = file.getFileName().toString().toLowerCase();
        int dot = n.lastIndexOf('.'); //$NON-NLS-1$
        String ext = dot >= 0 ? n.substring(dot + 1) : ""; //$NON-NLS-1$
        String ct = TYPES.get(ext);
        return ct != null ? ct : "application/octet-stream"; //$NON-NLS-1$
    }

    private static final Map<String, String> TYPES = new HashMap<>();
    static
    {
        TYPES.put("html", "text/html; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPES.put("htm", "text/html; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPES.put("js", "text/javascript; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPES.put("mjs", "text/javascript; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPES.put("json", "application/json; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPES.put("css", "text/css; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPES.put("svg", "image/svg+xml"); //$NON-NLS-1$
        TYPES.put("png", "image/png"); //$NON-NLS-1$
        TYPES.put("jpg", "image/jpeg"); //$NON-NLS-1$
        TYPES.put("jpeg", "image/jpeg"); //$NON-NLS-1$
        TYPES.put("gif", "image/gif"); //$NON-NLS-1$
        TYPES.put("csv", "text/csv; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPES.put("txt", "text/plain; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPES.put("woff", "font/woff"); //$NON-NLS-1$
        TYPES.put("woff2", "font/woff2"); //$NON-NLS-1$
        TYPES.put("ttf", "font/ttf"); //$NON-NLS-1$
        TYPES.put("ico", "image/x-icon"); //$NON-NLS-1$
        TYPES.put("map", "application/json"); //$NON-NLS-1$
        TYPES.put("xml", "text/xml; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
