/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;

import org.junit.Test;

public class HttpSnapshotAuthenticationTest
{
    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 8765);
    private static final InetSocketAddress NON_LOCAL = new InetSocketAddress("192.0.2.1", 8765);

    @Test
    public void bindsIdentityOnlyToAuthenticatedLoopback()
    {
        assertFalse(HttpTransport.authenticate("test-a", null, LOCAL, LOCAL).allowed());
        assertNull(HttpTransport.authenticate("test-a", "incorrect", LOCAL, LOCAL).client());
        assertNull(HttpTransport.authenticate("test-a", "test-a", LOCAL, NON_LOCAL).client());
        assertNull(HttpTransport.authenticate("test-a", "test-a", NON_LOCAL, LOCAL).client());
        HttpTransport.Admission local = HttpTransport.authenticate("test-a", "test-a", LOCAL, LOCAL);
        assertTrue(local.allowed());
        assertTrue(local.client().matches("test-a"));
        assertFalse(local.client().matches("test-b"));
        assertEquals(local.client(), HttpTransport.authenticate("test-a", "test-a", LOCAL, LOCAL).client());
        assertNotEquals(local.client(), HttpTransport.authenticate("test-b", "test-b", LOCAL, LOCAL).client());
    }
}
