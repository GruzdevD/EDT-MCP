/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.Group;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.InfobaseType;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool to list the infobases registered in EDT's GLOBAL infobase panel (the
 * "Инфобазы" / "Infobases" view) — the file and client-server databases EDT knows
 * of across all projects, independent of any one project's applications.
 *
 * <p>This is the registry a caller reads to pick an <em>existing</em> database
 * by name when binding a new project application to it (see
 * {@code create_project_application}, which clones one of these references and
 * associates it with a project/branch without touching the on-disk database).
 *
 * <p>The panel is a tree: root {@link Section}s are either {@link Group}
 * (folders holding sub-sections) or {@link InfobaseReference} leaves. This tool
 * walks the whole tree (depth-first, {@link Group#getSubsections()}), collects
 * every leaf, and reports each with its folder path. The {@code groupName}
 * parameter filters the walk to a single folder.
 */
public class ListInfobasesTool implements IMcpTool
{
    public static final String NAME = "list_infobases"; //$NON-NLS-1$

    /** Output key: array of infobases (leaves of the panel tree). */
    private static final String KEY_INFOBASES = "infobases"; //$NON-NLS-1$

    /** Output key: summary of the groups (folders) present in the panel. */
    private static final String KEY_GROUPS = "groups"; //$NON-NLS-1$

    /** Output key: names of EDT's recent infobases (the panel's "Recent" list). */
    private static final String KEY_RECENT = "recent"; //$NON-NLS-1$

    /** Output key: number of infobases reported. */
    private static final String KEY_TOTAL = "total"; //$NON-NLS-1$

    /** Input key: optional folder name to restrict the listing to. */
    private static final String KEY_GROUP_NAME = "groupName"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List the infobases registered in EDT's global infobase panel (Инфобазы) - the file and " //$NON-NLS-1$
            + "client-server databases EDT knows of across all projects, grouped by folder, with their " //$NON-NLS-1$
            + "connection strings and types. This is the registry to pick an EXISTING database from by " //$NON-NLS-1$
            + "name (e.g. to bind a new project application to it with create_project_application) and is " //$NON-NLS-1$
            + "independent of any one project's applications. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('list_infobases')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_GROUP_NAME,
                "Optional EDT infobase-panel folder name to restrict the listing to (default: all " //$NON-NLS-1$
                    + "folders). Leave empty to list every registered infobase.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty(KEY_INFOBASES,
                "Infobases with name, uuid, type (FILE/SERVER), connectionString, file path (FILE only), " //$NON-NLS-1$
                    + "folder, external and recent markers") //$NON-NLS-1$
            .objectArrayProperty(KEY_GROUPS, "Summary of the folders present in the panel (name, count)") //$NON-NLS-1$
            .stringArrayProperty(KEY_RECENT, "Names of EDT's recent infobases") //$NON-NLS-1$
            .integerProperty(KEY_TOTAL, "Number of infobases reported") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public boolean connectsToInfobase()
    {
        // Reads the in-memory registry only; no infobase connection is opened.
        return false;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String groupName = JsonUtils.extractStringArgument(params, KEY_GROUP_NAME);
        String filter = (groupName == null || groupName.trim().isEmpty()) ? null : groupName.trim();

        IInfobaseManager manager = Activator.getDefault().getInfobaseManager();
        if (manager == null)
        {
            return ToolResult.error("EDT infobase manager is not available (the platform-services "
                + "plugin may not be ready).").toJson(); //$NON-NLS-1$
        }

        try
        {
            // Recent list drives the "recent" marker; collected once, before the walk.
            List<InfobaseReference> recent = manager.getRecent();
            Set<UUID> recentUuids = recent == null ? Set.of()
                : recent.stream()
                    .map(Section::getUuid)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());

            List<JsonObject> infobases = new ArrayList<>();
            java.util.Map<String, Integer> groupCounts = new java.util.LinkedHashMap<>();
            for (Section root : manager.getAll())
            {
                walk(root, null, filter, recentUuids, infobases, groupCounts);
            }

            // A folder filter that matched nothing must be called out, not read as "no infobases".
            if (filter != null && infobases.isEmpty())
            {
                return ToolResult.error("No EDT infobase-panel folder named '" + filter //$NON-NLS-1$
                    + "' (or it contains no infobases). Use list_infobases without groupName to see " //$NON-NLS-1$
                    + "the available folders.").toJson(); //$NON-NLS-1$
            }

            // Stable, readable ordering: by folder, then by name.
            infobases.sort(Comparator
                .comparing((JsonObject o) -> o.has("folder") ? o.get("folder").getAsString() : "") //$NON-NLS-1$
                .thenComparing(o -> o.get("name").getAsString())); //$NON-NLS-1$

            JsonArray groupsArray = new JsonArray();
            groupCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e ->
                {
                    JsonObject g = new JsonObject();
                    g.addProperty("name", e.getKey()); //$NON-NLS-1$
                    g.addProperty("count", e.getValue()); //$NON-NLS-1$
                    groupsArray.add(g);
                });

            JsonArray infobasesArray = new JsonArray();
            infobases.forEach(infobasesArray::add);

            JsonArray recentArray = new JsonArray();
            if (recent != null)
            {
                for (InfobaseReference r : recent)
                {
                    if (r.getName() != null)
                    {
                        recentArray.add(r.getName());
                    }
                }
            }

            return ToolResult.success()
                .put(KEY_INFOBASES, infobasesArray)
                .put(KEY_GROUPS, groupsArray)
                .put(KEY_RECENT, recentArray)
                .put(KEY_TOTAL, infobases.size())
                .toJson();
        }
        catch (Exception e) // NOSONAR never let the registry walk escape the tool
        {
            Activator.logError("Error listing infobases from the EDT panel", e); //$NON-NLS-1$
            return ToolResult.error("Error listing infobases: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Depth-first walk of the infobase panel tree starting at {@code section},
     * collecting every {@link InfobaseReference} leaf (subject to the folder
     * filter) into {@code out} and updating per-folder counts in
     * {@code groupCounts}. A {@link Group} is descended into; a leaf is rendered
     * via {@link #renderInfobase}.
     *
     * @param section the node to visit (root or descendant)
     * @param folder the accumulated folder path of the node (may be {@code null} at the root)
     * @param filter the folder name to restrict to, or {@code null} for all (folder path
     *            comparison is case-insensitive)
     * @param recentUuids uuids of EDT's recent infobases
     * @param out the collector for rendered leaves
     * @param groupCounts the collector of {@code folder -> count}
     */
    private void walk(Section section, String folder, String filter,
            Set<UUID> recentUuids, List<JsonObject> out,
            java.util.Map<String, Integer> groupCounts)
    {
        if (section == null)
        {
            return;
        }
        String ownFolder = section.getFolder();
        String resolvedFolder = (ownFolder != null && !ownFolder.isEmpty()) ? ownFolder : folder;
        if (section instanceof Group)
        {
            Group group = (Group)section;
            for (Section child : group.getSubsections())
            {
                walk(child, resolvedFolder, filter, recentUuids, out, groupCounts);
            }
            return;
        }
        if (!(section instanceof InfobaseReference))
        {
            return;
        }
        InfobaseReference ref = (InfobaseReference)section;
        String refFolder = (resolvedFolder == null) ? "" : resolvedFolder; //$NON-NLS-1$
        if (!matchesFolder(refFolder, filter))
        {
            return;
        }
        out.add(renderInfobase(ref, refFolder, recentUuids));
        groupCounts.merge(refFolder, 1, Integer::sum);
    }

    /**
     * Whether an infobase's folder satisfies the caller's {@code groupName}
     * filter. A {@code null}/blank filter matches everything; otherwise the
     * folder path is matched case-insensitively (EDT's panel folder names are
     * presented to the user as-is, so a caller may not reproduce the exact
     * case). Exposed (package-private) so the filter decision is unit-testable
     * without a live infobase manager.
     *
     * @param folder the resolved folder path of the infobase (may be {@code null})
     * @param filter the caller's {@code groupName} filter (may be {@code null})
     * @return whether the infobase should be reported
     */
    static boolean matchesFolder(String folder, String filter)
    {
        if (filter == null || filter.isEmpty())
        {
            return true;
        }
        String f = folder == null ? "" : folder; //$NON-NLS-1$
        return f.equalsIgnoreCase(filter);
    }

    /**
     * Renders a single {@link InfobaseReference} leaf as JSON. Never throws for
     * a malformed value read from the model — a missing connection string or a
     * null member degrades to its natural JSON absence, not an exception.
     *
     * @param ref the infobase reference
     * @param folder the resolved folder path of the leaf
     * @param recentUuids uuids of EDT's recent infobases
     * @return the JSON object describing the infobase
     */
    private JsonObject renderInfobase(InfobaseReference ref, String folder, Set<UUID> recentUuids)
    {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", ref.getName()); //$NON-NLS-1$
        UUID uuid = ref.getUuid();
        if (uuid != null)
        {
            obj.addProperty("uuid", uuid.toString()); //$NON-NLS-1$
        }
        InfobaseType type = ref.getInfobaseType();
        obj.addProperty("type", type == null ? "UNKNOWN" : type.name()); //$NON-NLS-1$ //$NON-NLS-2$
        if (!folder.isEmpty())
        {
            obj.addProperty("folder", folder); //$NON-NLS-1$
        }
        obj.addProperty("external", ref.isExternal()); //$NON-NLS-1$
        obj.addProperty("recent", recentUuids.contains(uuid)); //$NON-NLS-1$
        try
        {
            IConnectionString cs = ref.getConnectionString();
            if (cs != null)
            {
                String conn = cs.asConnectionString();
                if (conn != null && !conn.isEmpty())
                {
                    obj.addProperty("connectionString", conn); //$NON-NLS-1$
                }
                if (cs instanceof FileConnectionString)
                {
                    String file = ((FileConnectionString)cs).getFile();
                    if (file != null && !file.isEmpty())
                    {
                        obj.addProperty("file", file); //$NON-NLS-1$
                    }
                }
            }
        }
        catch (Exception e) // NOSONAR a single unreadable connection string must not drop the list
        {
            Activator.logError("list_infobases: connection string not readable for " //$NON-NLS-1$
                + ref.getName(), e);
        }
        return obj;
    }
}
