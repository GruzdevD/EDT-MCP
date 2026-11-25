/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.McpOriginValidator;
import com.sun.net.httpserver.HttpExchange;

/**
 * Shared HTTP transport helpers: CORS/Origin admission and raw response writing.
 * Used by both {@link McpHttpHandler} and {@link HealthHandler}. Pure transport
 * plumbing with no MCP-protocol knowledge.
 */
public final class HttpTransport
{
    private HttpTransport()
    {
        // utility
    }

    /**
     * Adds CORS headers to the HTTP exchange if an Origin is present.
     * Validates the origin (via {@link McpOriginValidator}) and returns false
     * if it's not allowed.
     *
     * @param exchange the HTTP exchange
     * @return true if origin is allowed (or absent), false if origin is invalid
     */
    public static boolean addCorsHeaders(HttpExchange exchange)
    {
        String origin = exchange.getRequestHeaders().getFirst("Origin"); //$NON-NLS-1$
        if (origin != null)
        {
            if (!McpOriginValidator.isValidOrigin(origin))
            {
                return false;
            }
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin); //$NON-NLS-1$
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Accept"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // A missing Origin means a non-browser client (CLI / MCP client) — browsers
        // always send Origin, so the browser-CSRF allow-list does not apply here.
        // Real access control is the loopback bind + the optional auth token.
        return true;
    }

    /**
     * Checks the optional shared-token authorization. When no token is configured
     * ({@code PREF_AUTH_TOKEN} empty) authentication is disabled and every request
     * is authorized — the default, backward-compatible behavior. When a token IS
     * configured, the request must carry it in the {@code Authorization} header,
     * either as {@code "Bearer <token>"} or the raw token value.
     *
     * @param exchange the HTTP exchange
     * @return admission decision and, for a protected loopback call, its authenticated client
     */
    public static Admission authorize(HttpExchange exchange)
    {
        String token = configuredAuthToken();
        if (token == null || token.isEmpty())
        {
            return new Admission(true, null); // legacy admission, not an authenticated identity
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization"); //$NON-NLS-1$
        if (header == null)
        {
            return new Admission(false, null);
        }
        // Accept "Bearer <token>" (scheme case-insensitive per RFC 6750) or the raw token.
        String trimmed = header.trim();
        String presented = trimmed.regionMatches(true, 0, "Bearer ", 0, 7) //$NON-NLS-1$
            ? trimmed.substring(7).trim()
            : trimmed;
        return authenticate(token, presented, exchange.getLocalAddress(), exchange.getRemoteAddress());
    }

    static Admission authenticate(String token, String presented, java.net.InetSocketAddress local,
        java.net.InetSocketAddress remote)
    {
        boolean allowed = constantTimeEquals(token, presented);
        boolean loopback = local != null && local.getAddress() != null && local.getAddress().isLoopbackAddress()
            && remote != null && remote.getAddress() != null && remote.getAddress().isLoopbackAddress();
        return new Admission(allowed, allowed && loopback && !token.isBlank() ? new AuthenticatedClient(token) : null);
    }

    /** HTTP admission, independent of any caller-supplied MCP session ID. */
    public record Admission(boolean allowed, AuthenticatedClient client) { }

    /** Opaque identity from successful authentication. Never stores or returns the raw token. */
    public static final class AuthenticatedClient
    {
        private static final byte[] SALT = new SecureRandom().generateSeed(32);
        private final byte[] digest;

        private AuthenticatedClient(String token)
        {
            digest = fingerprint(token);
        }

        /** Snapshot calls require a dedicated loopback listener and a still-current credential. */
        public boolean isCurrent()
        {
            Activator activator = Activator.getDefault();
            return activator != null
                && activator.getMcpServer() != null && activator.getMcpServer().isLoopbackListener()
                && !activator.getPreferenceStore().getBoolean(PreferenceConstants.PREF_ALLOW_REMOTE_ACCESS)
                && matches(configuredAuthToken());
        }

        boolean matches(String token)
        {
            return token != null && !token.isBlank() && MessageDigest.isEqual(digest, fingerprint(token));
        }

        /** Derives an opaque, per-operation owner tag for the server's persistent commit journal. */
        public String journalOwnerTag(byte[] serverSecret, UUID planId) throws IOException
        {
            String token = configuredAuthToken();
            if (!isCurrent() || !matches(token))
            {
                throw new IOException("The authenticated project commit session is unavailable.");
            }
            try
            {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(serverSecret, "HmacSHA256"));
                mac.update(planId.toString().getBytes(StandardCharsets.US_ASCII));
                return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
            }
            catch (GeneralSecurityException e)
            {
                throw new IOException("The project commit journal owner cannot be verified.");
            }
        }

        @Override
        public boolean equals(Object other)
        {
            return other instanceof AuthenticatedClient client && MessageDigest.isEqual(digest, client.digest);
        }

        @Override
        public int hashCode()
        {
            return java.util.Arrays.hashCode(digest);
        }

        private static byte[] fingerprint(String token)
        {
            try
            {
                MessageDigest sha = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
                sha.update(SALT);
                return sha.digest(token.getBytes(StandardCharsets.UTF_8));
            }
            catch (NoSuchAlgorithmException e)
            {
                throw new IllegalStateException(e);
            }
        }
    }

    private static String configuredAuthToken()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return null; // no preference store (e.g. headless tests) -> auth disabled
        }
        return activator.getPreferenceStore().getString(PreferenceConstants.PREF_AUTH_TOKEN);
    }

    /** Constant-time comparison to avoid leaking the token via response timing. */
    private static boolean constantTimeEquals(String expected, String presented)
    {
        if (expected == null || presented == null)
        {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = presented.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++)
        {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    /**
     * Writes a complete HTTP response (status + body) and flushes.
     *
     * @param exchange the HTTP exchange
     * @param statusCode the HTTP status code
     * @param response the response body
     * @throws IOException if the client connection is lost while writing
     */
    public static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException
    {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody())
        {
            os.write(bytes);
            os.flush();
        }
        catch (IOException e)
        {
            Activator.logInfo("Connection lost while sending response: " + e.getMessage()); //$NON-NLS-1$
            throw e;
        }
    }
}
