/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.ToNumberPolicy;

/**
 * JSON-RPC 2.0 request object.
 */
public class JsonRpcRequest
{
    private String jsonrpc;
    private Object id;
    private String method;
    private static final Gson RAW_ARGUMENTS = new GsonBuilder()
        .setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER).create();

    private JsonObject params;
    private transient Map<String, Object> decodedParams;
    
    public String getJsonrpc()
    {
        return jsonrpc;
    }
    
    public void setJsonrpc(String jsonrpc)
    {
        this.jsonrpc = jsonrpc;
    }
    
    public Object getId()
    {
        return id;
    }
    
    public void setId(Object id)
    {
        this.id = id;
    }
    
    public String getMethod()
    {
        return method;
    }
    
    public void setMethod(String method)
    {
        this.method = method;
    }
    
    @SuppressWarnings("unchecked")
    public Map<String, Object> getParams()
    {
        if (decodedParams == null && params != null)
        {
            decodedParams = GsonProvider.get().fromJson(params, Map.class);
        }
        return decodedParams;
    }
    
    public void setParams(Map<String, Object> params)
    {
        this.params = params == null ? null : GsonProvider.get().toJsonTree(params).getAsJsonObject();
        this.decodedParams = params;
    }
    
    /**
     * Gets a string parameter value.
     */
    public String getStringParam(String name)
    {
        Map<String, Object> values = getParams();
        if (values == null)
        {
            return null;
        }
        Object value = values.get(name);
        return value != null ? value.toString() : null;
    }
    
    /**
     * Gets the nested "arguments" map from params (for tools/call).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getArguments()
    {
        Map<String, Object> values = getParams();
        if (values == null)
        {
            return null; // NOSONAR null is a deliberate signal (omit/sentinel), not an empty collection
        }
        Object args = values.get("arguments"); //$NON-NLS-1$
        if (args instanceof Map)
        {
            return (Map<String, Object>) args;
        }
        return null; // NOSONAR null is a deliberate signal (omit/sentinel), not an empty collection
    }

    /** Preserves explicit nulls and numeric token text for wire-level validation. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRawArguments()
    {
        var args = params == null ? null : params.get("arguments");
        return args != null && args.isJsonObject()
            ? RAW_ARGUMENTS.fromJson(args, Map.class) : Map.of();
    }
    
    /**
     * Gets the tool name from params.name (for tools/call).
     */
    public String getToolName()
    {
        return getStringParam("name"); //$NON-NLS-1$
    }
}
