/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Creates a NEW application for an EDT configuration project, bound to a git
 * BRANCH context and pointing at an EXISTING infobase — without creating or
 * registering a new database.
 *
 * <p>This fills the gap that {@code create_infobase} (which always
 * registers/creates a base) and {@code set_branch_infobase} (which re-binds an
 * EXISTING application, so the new branch SHARES it) both leave open: minting a
 * <em>distinct</em> application for a new branch over a base that already
 * exists. EDT has no "create application" operation of its own — an application
 * surfaces by associating an {@link InfobaseReference} with a project (and its
 * branch contexts). So a new application here is a new
 * {@link InfobaseReference} (fresh id + uuid, its own name) whose
 * <em>connection string</em> is cloned verbatim from the base the caller names
 * via {@code list_infobases}, then associated with the project's branch context
 * as not-synchronized (so EDT binds the reference without a base connect-lock and the config load is
 * deferred to a later update_database / launch with updateBeforeLaunch). Nothing on disk or in the
 * database is touched.
 *
 * <p><b>Read-back honesty (issue #412):</b> whether the new application actually
 * surfaced in {@code get_applications} is verified by a bounded re-poll, not
 * assumed — because the association is asynchronous and, for a brand-new
 * reference, whether EDT materializes a project-level application is something
 * only a live run establishes. If it does not appear within the budget the tool
 * says so explicitly ({@code NOT_BOUND}/{@code UNVERIFIED}) with the
 * applications it did read, never a false "created".
 *
 * <p><b>Silent authentication (automatic credential passthrough):</b> the read-back
 * probe reaches the infobase connection layer, and a base that needs credentials
 * would otherwise fail the connect and pop an "access-settings" dialog. Passing
 * {@code access}/user/password stores those credentials against the NEW reference
 * (via the same {@link InfobaseAccessSupport} store as {@code set_infobase_credentials})
 * before the association + read-back, so the live connect authenticates silently.
 * The password is never echoed back — only {@code passwordSet} reports one was stored.
 */
public class CreateProjectApplicationTool implements IMcpTool
{
    public static final String NAME = "create_project_application"; //$NON-NLS-1$

    /** Input param: short branch name whose context to bind the new application to. */
    private static final String KEY_BRANCH = "branch"; //$NON-NLS-1$

    /** Input param: name of the EXISTING infobase (from list_infobases) to point the new application at. */
    private static final String KEY_INFOBASE_NAME = "infobaseName"; //$NON-NLS-1$

    /** Input param: display name to give the new application (default derived from base + branch). */
    private static final String KEY_APPLICATION_NAME = "applicationName"; //$NON-NLS-1$

    /** Input param: also make the new application the branch context's default infobase. */
    private static final String KEY_SET_DEFAULT = "setDefault"; //$NON-NLS-1$

    /** Input param: authentication kind (INFOBASE / OS) to store for the new application's connect. */
    private static final String KEY_ACCESS = "access"; //$NON-NLS-1$

    /** Input param: infobase user to authenticate as for the new application's connect. */
    private static final String KEY_USER = "user"; //$NON-NLS-1$

    /** Input param: password for {@link #KEY_USER} - never echoed back to the caller. */
    private static final String KEY_PASSWORD = "password"; //$NON-NLS-1$

    /** Output key: the new application's id (when the read-back found it). */
    private static final String KEY_APPLICATION_ID_OUT = "applicationId"; //$NON-NLS-1$

    /** Output key: the new application's name (when the read-back found it). */
    private static final String KEY_APPLICATION_NAME_OUT = "applicationName"; //$NON-NLS-1$

    /** Output key: BOUND / NOT_BOUND / UNVERIFIED. */
    private static final String KEY_BINDING = "binding"; //$NON-NLS-1$

    /** Output key: read-back of the branch context bindings after the change. */
    private static final String KEY_BOUND = "bound"; //$NON-NLS-1$

    /** Output key: evidence — the applications the read-back observed. */
    private static final String KEY_APPLICATIONS = "applications"; //$NON-NLS-1$

    /** Output key: whether credentials were stored for the new application's connect. */
    private static final String KEY_CREDENTIALS_STORED = "credentialsStored"; //$NON-NLS-1$

    /** Output key: the stored user name (empty when none). */
    private static final String KEY_USER_OUT = "user"; //$NON-NLS-1$

    /** Output key: the stored access kind (INFOBASE / OS). */
    private static final String KEY_ACCESS_OUT = "access"; //$NON-NLS-1$

    /** Output key: whether a non-empty password was stored (the password itself is never returned). */
    private static final String KEY_PASSWORD_SET = "passwordSet"; //$NON-NLS-1$

    /** How many bounded re-polls of get_applications absorb the async provision race. */
    private static final int READ_BACK_MAX_POLLS = 6;

    /** Sleep between re-polls. */
    private static final long READ_BACK_POLL_MS = 300L;

    /** Binding verdict values. */
    private enum Binding
    {
        BOUND, NOT_BOUND, UNVERIFIED
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a NEW application for an EDT project, bound to a git BRANCH and pointing at an " //$NON-NLS-1$
            + "EXISTING infobase (named via list_infobases) WITHOUT creating or registering a new " //$NON-NLS-1$
            + "database. Unlike set_branch_infobase (which re-binds an existing application, so the " //$NON-NLS-1$
            + "branch SHARES it), this mints a DISTINCT application (new id) for this branch over the " //$NON-NLS-1$
            + "same physical infobase - a new app for a new branch on an existing base. Never touches " //$NON-NLS-1$
            + "the on-disk database. Optional access/user/password store the base connection " //$NON-NLS-1$
            + "credentials on the new application so it authenticates WITHOUT an access-settings " //$NON-NLS-1$
            + "dialog (OS auth or login/password). Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('create_project_application')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT configuration project to create the application for (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_BRANCH,
                "Short branch name (e.g. 'feature/x') whose context to bind the new application to " //$NON-NLS-1$
                + "(required).", true) //$NON-NLS-1$
            .stringProperty(KEY_INFOBASE_NAME,
                "Name of the EXISTING infobase to point the new application at, exactly as " //$NON-NLS-1$
                + "list_infobases reports it (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_APPLICATION_NAME,
                "Accepted for compatibility; IGNORED. An application over an EXISTING base inherits the " //$NON-NLS-1$
                    + "base's name (the EDT 'existing base' behaviour) - no new infobase is minted, so there " //$NON-NLS-1$
                    + "is no separate application name to set.") //$NON-NLS-1$
            .booleanProperty(KEY_SET_DEFAULT,
                "Also make the new application the DEFAULT infobase of the branch context (default false).") //$NON-NLS-1$
            .enumProperty(KEY_ACCESS,
                "Authentication kind to connect to the base this new application points at: 'INFOBASE' " //$NON-NLS-1$
                    + "(default, 1C user auth) or 'OS' (OS authentication). WHEN user/password/access is " //$NON-NLS-1$
                    + "passed, the credentials are STORED with the new application's infobase reference so " //$NON-NLS-1$
                    + "the live connect authenticates without an access-settings dialog.", //$NON-NLS-1$
                "INFOBASE", "OS") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_USER,
                "Infobase user to authenticate as for the new application's connect. Optional; with " //$NON-NLS-1$
                    + "access=OS or a userless base it may be omitted/empty.") //$NON-NLS-1$
            .stringProperty(KEY_PASSWORD,
                "Password for the infobase user. Optional (demo/userless bases use empty). Never " //$NON-NLS-1$
                    + "returned in the output - only passwordSet tells whether one was stored.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.PROJECT, "Target EDT project name.") //$NON-NLS-1$
            .stringProperty(KEY_BRANCH, "The branch context the new application was bound to.") //$NON-NLS-1$
            .stringProperty(KEY_INFOBASE_NAME, "The EXISTING infobase the application points at.") //$NON-NLS-1$
            .stringProperty(KEY_APPLICATION_NAME_OUT,
                "The application's display name - the EXISTING base's name (as the EDT 'existing base' " //$NON-NLS-1$
                    + "dialog surfaces it).") //$NON-NLS-1$
            .stringProperty(KEY_BINDING,
                "Whether the new application surfaced: BOUND, NOT_BOUND or UNVERIFIED.") //$NON-NLS-1$
            .stringProperty(KEY_APPLICATION_ID_OUT,
                "The new application's id (present only when the read-back found it).") //$NON-NLS-1$
            .objectArrayProperty(KEY_APPLICATIONS,
                "Evidence: the applications the read-back observed for the project.") //$NON-NLS-1$
            .objectProperty(KEY_BOUND,
                "Read-back of the branch context after the change: {infobases: [...], defaultInfobase}.") //$NON-NLS-1$
            .booleanProperty(KEY_CREDENTIALS_STORED,
                "True when connection credentials were stored alongside the new application (auth " //$NON-NLS-1$
                    + "params were passed). Present only when auth params were given.") //$NON-NLS-1$
            .stringProperty(KEY_USER_OUT, "The stored infobase user (present only when auth params were given).") //$NON-NLS-1$
            .stringProperty(KEY_ACCESS_OUT, "The stored access kind, INFOBASE or OS.") //$NON-NLS-1$
            .booleanProperty(KEY_PASSWORD_SET,
                "True when a non-empty password was stored (never the password itself).") //$NON-NLS-1$
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
        // The read-back probe (issue #412) calls appManager.getApplications(project), which — like
        // get_applications — recomputes each application's update state and so reaches the
        // infobase connection layer (and can raise the auth dialog). Marking this tool
        // connection-reaching lets McpProtocolHandler scope the auth-dialog suppressor
        // (InfobaseAuthDialogSuppressor) to its execution, so an unattended create never blocks
        // on a credentials modal (issue #270 pattern).
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_BRANCH, KEY_INFOBASE_NAME);
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String branch = JsonUtils.extractStringArgument(params, KEY_BRANCH);
        String infobaseName = JsonUtils.extractStringArgument(params, KEY_INFOBASE_NAME);
        String applicationName = JsonUtils.extractStringArgument(params, KEY_APPLICATION_NAME);
        boolean setDefault = JsonUtils.extractBooleanArgument(params, KEY_SET_DEFAULT, false);
        String access = JsonUtils.extractStringArgument(params, KEY_ACCESS);
        String user = JsonUtils.extractStringArgument(params, KEY_USER);
        String password = JsonUtils.extractStringArgument(params, KEY_PASSWORD);

        // Reject an out-of-enum access value (the schema declares a closed enum, but a client need
        // not validate against it before sending) - a typo must not silently store a different mode.
        String accessError = InfobaseAccessSupport.accessError(access);
        if (accessError != null)
        {
            return ToolResult.error(accessError).toJson();
        }

        GitRepositoryResolver.Resolution resolution = GitRepositoryResolver.resolve(projectName);
        if (!resolution.ok())
        {
            return resolution.errorJson();
        }
        try
        {
            return createApplication(resolution.project(), projectName, branch, infobaseName,
                applicationName, setDefault, user, password, access);
        }
        catch (Exception e) // NOSONAR unattended-safety: no exception may escape the tool (CLAUDE.md #8)
        {
            Activator.logError("create_project_application: failed for project '" + projectName //$NON-NLS-1$
                + "', branch '" + branch + "', infobase '" + infobaseName + "'", e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return ToolResult.error("Failed to create the project application: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            resolution.closeIfOwned();
        }
    }

    private String createApplication(IProject project, String projectName, String branch,
            String infobaseName, String applicationName, boolean setDefault, String user,
            String password, String access)
    {
        if (applicationName != null && !applicationName.trim().isEmpty())
        {
            // Accepted for compatibility but not applied: an application over an EXISTING base keeps the
            // base's name (EDT's "existing base" behaviour) — there is no separate application to name.
            Activator.logInfo("create_project_application: applicationName argument is ignored for an "
                + "existing base; the application inherits the base's name '" + infobaseName + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IInfobaseManager ibManager = Activator.getDefault().getInfobaseManager();
        if (ibManager == null)
        {
            return ToolResult.error("IInfobaseManager service is not available. Ensure EDT "
                + "platform-services are running.").toJson(); //$NON-NLS-1$
        }
        IInfobaseAssociationManager assocManager =
            Activator.getDefault().getInfobaseAssociationManager();
        if (assocManager == null)
        {
            return ToolResult.error("IInfobaseAssociationManager service is not available. Ensure EDT "
                + "platform-services are running.").toJson(); //$NON-NLS-1$
        }
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return ToolResult.error("IApplicationManager service is not available. EDT may still be "
                + "starting up - retry in a moment.").toJson(); //$NON-NLS-1$
        }

        Optional<InfobaseReference> found = findByName(ibManager, infobaseName);
        if (found.isEmpty())
        {
            return ToolResult.error("No EDT infobase named '" + infobaseName //$NON-NLS-1$
                + "'. Use list_infobases to see the registered bases (by name) to bind a new "
                + "application to.").toJson(); //$NON-NLS-1$
        }
        InfobaseReference base = found.get();

        // The application is a BINDING of the EXISTING base to the project — exactly what the EDT
        // "new application / existing base" dialog does. We do NOT clone the reference or call
        // ibManager.add: that would mint a second infobase in EDT's global registry (a "new base in
        // the list") while the real blocker — surfacing an app for the base — is purely about the
        // association. get_applications materializes applications as {active-context "Infobases"
        // UUID set} ∩ {references registered in IInfobaseManager}; the named base is already in the
        // registry, so associating IT (not a fresh clone) is what makes a normal app appear. The
        // surfaced application inherits the base's name («Инвест»), as the UI does.

        // The git-BRANCH context EDT treats as ACTIVE for get_applications must be addressed by its
        // FULL git ref ("refs/heads/<branch>"), NOT the short branch name. of(short) resolves to a
        // different context (stored under `feature/<branch>/`) that get_applications never reads —
        // the NOT_BOUND bug. The active-context file is `refs/heads/<branch>/AssociationData.properties`.
        InfobaseAssociationContext ctx = InfobaseAssociationContext.of(gitBranchContext(branch));

        // Automatic authentication passthrough: when the caller supplies credentials (access/user/
        // password, e.g. "через ос" or login/password), store them against the NEW reference BEFORE
        // the association + read-back, so the live connect to the base this app points at
        // authenticates without EDT popping an access-settings dialog. Keyed by the base's uuid -
        // the same key the surfaced application's update-state recompute resolves - so the read-back
        // poll connects silently instead of failing the SSH/user auth and flashing the modal.
        CredentialRecord credentials = null;
        if (user != null || password != null || access != null)
        {
            InfobaseAccess accessKind = InfobaseAccessSupport.parseAccess(access);
            String storeError =
                InfobaseAccessSupport.storeCredentials(base, user, password, accessKind);
            credentials = new CredentialRecord(storeError == null,
                user == null ? "" : user, accessKind.getName(), //$NON-NLS-1$
                password != null && !password.isEmpty());
            if (storeError != null)
            {
                Activator.logError("create_project_application: storing credentials for app '" //$NON-NLS-1$
                    + base.getName() + "' failed", new Exception(storeError)); //$NON-NLS-1$
            }
        }

        Binding binding;
        String appId = null;
        // The surfaced application inherits the EXISTING base's name (the UI's behaviour) — no new
        // infobase is minted, so there is no fresh name to assign.
        String appName = base.getName();
        try
        {
            // THE binding — associate the EXISTING base into the EXPLICIT branch context via
            // notSynchronized(ctx). get_applications materializes applications from the ACTIVE context,
            // and a branch with its own AssociationData.properties SHADOWS the default context — earlier
            // code associated with no context (wrote `.default/`), which the branch file hid, so the
            // application never surfaced (the NOT_BOUND bug). Writing the branch context directly is what
            // EDT's own binding path does and what get_applications then reads.
            // notSynchronized (NOT alreadySynchronized) is deliberate: alreadySynchronized means "this is
            // ALREADY a synchronized application, just record the branch binding" — but the named base is
            // not (yet) an application of this project, so nothing materializes (log showed only the
            // registry-list refresh, never a connection). notSynchronized is exactly what create_infobase
            // uses to SURFACE an application; binding the existing base with it surfaces the app in
            // get_applications while deferring the actual configuration load to update_database/launch.
            // No ibManager.add: the provision delegate materializes an app for every infobase in the
            // association regardless of the reference's origin, so reusing the named base is enough —
            // no new infobase is minted ("не плодить новых инфобаз в списке").
            // The DEFAULT is NOT set here: setDefaultInfobase is a REBIND primitive — it demands the ref
            // already be in the active association (checked before the write settles) and throws "Project
            // ... is not associated with infobase ..." on a fresh bootstrap. It is applied below, on the
            // application the read-back actually finds, via appManager.setDefaultApplication.
            assocManager.associate(project, base, InfobaseAssociationSettings.notSynchronized(ctx));
        }
        catch (InfobaseAssociationException e)
        {
            String msg = e.getMessage() == null ? "" : e.getMessage(); //$NON-NLS-1$
            // "Project ... is already associated with context <branch>" means the branch's association
            // already exists (e.g. a file with an EMPTY Infobases=). Non-forced associate cannot add to an
            // existing context — surface an actionable conflict rather than silently no-op. The read-back
            // below stays the source of truth for whether the app is actually there.
            Activator.logError("create_project_application: association failed for project " //$NON-NLS-1$
                + projectName + ", branch '" + branch + "': " + msg, e); //$NON-NLS-1$
            return ToolResult.error("Could not bind '" + base.getName() + "' to branch '" + branch //$NON-NLS-1$
                + "': the branch already has an infobase association that this base is not a part of ("
                + msg + "). Use get_applications to see the branch's current association, detach/clean it "
                + "with set_branch_infobase (action=detach) or delete_project_application, then retry.")
                .toJson();
        }
        catch (Exception e) // NOSONAR associate may surface unchecked
        {
            Activator.logError("create_project_application: association surfaced an unchecked error", e); //$NON-NLS-1$
            return ToolResult.error("Failed to associate '" + base.getName() + "' with project '" //$NON-NLS-1$
                + projectName + "' (branch '" + branch + "'): " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // associate() mutates the in-memory association and fires the provision events, but the branch
        // context's AssociationData.properties is only flushed when EDT persists the workspace — without
        // this save, get_applications (which reads the persisted active context) stays empty even though
        // the in-memory read-back already shows the base bound. Save explicitly so the app materializes.
        saveWorkspace();

        // Issue #412 read-back: does the application actually surface? Bounded re-poll, honest.
        ApplicationProbe probe = pollForApplication(appManager, project, base);
        binding = probe.binding;
        if (probe.app != null)
        {
            appId = probe.app.getId();
            appName = probe.app.getName();
        }

        // Default is applied AFTER the read-back, on the application the read-back actually FOUND, via
        // appManager.setDefaultApplication (mirrors create_infobase). setDefaultInfobase right after
        // associate is a rebind primitive that throws on a fresh bootstrap — see the associate comment.
        String setDefaultNote = null;
        if (setDefault)
        {
            setDefaultNote = applySetDefault(appManager, project, probe);
        }

        String message = bindingMessage(projectName, branch, base.getName(), binding);
        if (setDefaultNote != null)
        {
            message += setDefaultNote;
        }

        ToolResult result = ToolResult.success()
            .put(McpKeys.PROJECT, projectName)
            .put(KEY_BRANCH, branch)
            .put(KEY_INFOBASE_NAME, infobaseName)
            .put(KEY_APPLICATION_NAME_OUT, appName)
            .put(KEY_BINDING, binding.name())
            .put(McpKeys.MESSAGE, message);
        if (appId != null)
        {
            result.put(KEY_APPLICATION_ID_OUT, appId);
        }
        if (probe.appsArray != null)
        {
            result.put(KEY_APPLICATIONS, probe.appsArray);
        }
        Map<String, Object> bound = readBack(assocManager, project, ctx);
        if (!bound.isEmpty())
        {
            result.put(KEY_BOUND, bound);
        }
        if (credentials != null)
        {
            result.put(KEY_CREDENTIALS_STORED, credentials.stored)
                .put(KEY_USER_OUT, credentials.user)
                .put(KEY_ACCESS_OUT, credentials.access)
                .put(KEY_PASSWORD_SET, credentials.passwordSet);
        }
        return result.toJson();
    }

    /**
     * Sets the application the read-back FOUND as the branch's default (issue #412). Mirrors
     * create_infobase's {@code applySetDefault}: setDefault is applied AFTER the bounded re-poll, on
     * the application the read-back actually established, via {@code IApplicationManager
     * .setDefaultApplication} — NOT {@code setDefaultInfobase}, which is a rebind primitive that throws
     * on a freshly-bootstrapped association. Non-fatal: returns a note to append to the result message
     * when the default could not be set (or there was nothing to set), or {@code null} when it was set.
     */
    private static String applySetDefault(IApplicationManager appManager, IProject project,
            ApplicationProbe probe)
    {
        if (probe.app == null)
        {
            return probe.binding == Binding.BOUND
                ? " setDefault was not applied: no application to set." //$NON-NLS-1$
                : " setDefault was not applied: the application could not be read back - check "
                    + "get_applications and set it manually."; //$NON-NLS-1$
        }
        try
        {
            appManager.setDefaultApplication(project, probe.app);
        }
        catch (Exception e)
        {
            Activator.logError("create_project_application: setDefault failed", e); //$NON-NLS-1$
            return " setDefault failed: " + e.getMessage() + " - set it manually or retry."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Outcome of the optional automatic-credentials step: whether they were stored, the stored user
     * (never the password), the access kind, and whether a non-empty password was stored. {@code null}
     * presence (not used) means the caller passed no auth params and nothing was attempted.
     */
    private static final class CredentialRecord
    {
        final boolean stored;
        final String user;
        final String access;
        final boolean passwordSet;

        CredentialRecord(boolean stored, String user, String access, boolean passwordSet)
        {
            this.stored = stored;
            this.user = user;
            this.access = access;
            this.passwordSet = passwordSet;
        }
    }

    /**
     * Normalizes the caller's {@code branch} argument to the FULL git-ref context string that EDT
     * treats as ACTIVE for {@code get_applications}. A short name like {@code feature/x} becomes
     * {@code refs/heads/feature/x}; an already-qualified {@code refs/...} is passed through.
     */
    static String gitBranchContext(String branch)
    {
        String b = branch == null ? "" : branch.trim(); //$NON-NLS-1$
        return b.startsWith("refs/") ? b : "refs/heads/" + b; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Best-effort name lookup that never throws: a {@code findInfobaseByName} failure degrades
     * to "not found" so the caller's "no such base" naming stays the single error path.
     */
    private static Optional<InfobaseReference> findByName(IInfobaseManager ibManager, String name)
    {
        try
        {
            return ibManager.findInfobaseByName(name);
        }
        catch (Exception e) // NOSONAR probe must never crash the tool
        {
            Activator.logError("create_project_application: findInfobaseByName failed for '" //$NON-NLS-1$
                + name + "'", e); //$NON-NLS-1$
            return Optional.empty();
        }
    }

    /**
     * Forces the workspace to persist project-local settings, which includes the branch context's
     * {@code AssociationData.properties} that {@code associate} mutates only in memory. Non-fatal on
     * failure: the save is why the app materializes; a failure is logged and the read-back below
     * stays the honest verdict.
     */
    private static void saveWorkspace()
    {
        try
        {
            ResourcesPlugin.getWorkspace().save(true, null);
        }
        catch (Exception e) // NOSONAR non-fatal persistence best-effort
        {
            Activator.logError("create_project_application: workspace save after associate failed", e); //$NON-NLS-1$
        }
    }

    /**
     * Bounded re-poll of {@code getApplications(project)} looking for the application whose
     * infobase uuid matches the new reference. Returns the found application (or {@code null})
     * with the matching {@link Binding} verdict and the last applications snapshot as evidence.
     */
    private static ApplicationProbe pollForApplication(IApplicationManager appManager, IProject project,
            InfobaseReference clone)
    {
        List<IApplication> applications = null;
        boolean readCompleted = false;
        for (int poll = 0; poll < READ_BACK_MAX_POLLS; poll++) // NOSONAR multiple intentional exits
        {
            try
            {
                applications = appManager.getApplications(project);
                readCompleted = true;
            }
            catch (ApplicationException e)
            {
                Activator.logError("create_project_application: getApplications read-back failed", e); //$NON-NLS-1$
                readCompleted = false;
            }
            if (!readCompleted)
            {
                break; // Read failed — absence is NOT established.
            }
            IApplication match = findMatchingApplication(applications, clone);
            if (match != null)
            {
                return new ApplicationProbe(match, Binding.BOUND, renderApplications(applications));
            }
            if (poll < READ_BACK_MAX_POLLS - 1)
            {
                try
                {
                    Thread.sleep(READ_BACK_POLL_MS);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    return new ApplicationProbe(null, Binding.UNVERIFIED, renderApplications(applications));
                }
            }
        }
        if (!readCompleted)
        {
            return new ApplicationProbe(null, Binding.UNVERIFIED, renderApplications(applications));
        }
        return new ApplicationProbe(null, Binding.NOT_BOUND, renderApplications(applications));
    }

    /**
     * Finds the application bound to {@code clone}'s uuid among the project's applications, or
     * {@code null}. An application whose infobase reference cannot be resolved is skipped (never
     * crashes the probe).
     */
    private static IApplication findMatchingApplication(List<IApplication> applications,
            InfobaseReference clone)
    {
        if (applications == null || clone.getUuid() == null)
        {
            return null;
        }
        for (IApplication app : applications)
        {
            if (app == null)
            {
                continue;
            }
            InfobaseReference ref = InfobaseAccessSupport.resolveInfobaseReference(app);
            if (ref != null && clone.getUuid().equals(ref.getUuid()))
            {
                return app;
            }
        }
        return null;
    }

    /**
     * Renders the applications list as the evidence array ({@code id}, {@code name}) — or
     * {@code null} when no read produced a snapshot (a failed read must not look like an empty
     * project).
     */
    private static JsonArray renderApplications(List<IApplication> applications)
    {
        if (applications == null)
        {
            return null;
        }
        JsonArray array = new JsonArray();
        for (IApplication app : applications)
        {
            if (app == null)
            {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", app.getId()); //$NON-NLS-1$
            o.addProperty("name", app.getName()); //$NON-NLS-1$
            array.add(o);
        }
        return array;
    }

    /**
     * Best-effort read-back of the branch context's bindings (proof the clone is now bound), or an
     * empty map on failure — a read-back must not fail an already-successful change.
     */
    private static Map<String, Object> readBack(IInfobaseAssociationManager assocManager, IProject project,
        InfobaseAssociationContext ctx)
    {
        try
        {
            Optional<IInfobaseAssociation> assoc = assocManager.getAssociation(project, ctx);
            List<String> names = new ArrayList<>();
            String defaultName = null;
            if (assoc.isPresent())
            {
                Collection<InfobaseReference> infobases = assoc.get().getInfobases();
                if (infobases != null)
                {
                    for (InfobaseReference ib : infobases)
                    {
                        if (ib != null)
                        {
                            names.add(ib.getName());
                        }
                    }
                }
                InfobaseReference def = assoc.get().getDefaultInfobase();
                defaultName = def != null ? def.getName() : null;
            }
            Map<String, Object> bound = new LinkedHashMap<>();
            bound.put("infobases", names); //$NON-NLS-1$
            bound.put("defaultInfobase", defaultName); //$NON-NLS-1$
            return bound;
        }
        catch (RuntimeException e)
        {
            Activator.logError("create_project_application: bindings read-back failed for project '" //$NON-NLS-1$
                + project.getName() + "'", e); //$NON-NLS-1$
            return Collections.emptyMap();
        }
    }

    private static String bindingMessage(String projectName, String branch, String appName, Binding binding)
    {
        switch (binding)
        {
            case BOUND:
                return "Application '" + appName //$NON-NLS-1$
                    + "' was created and is bound to branch '" + branch + "' of project '" //$NON-NLS-1$
                    + projectName + "'."; //$NON-NLS-1$
            case NOT_BOUND:
                return "The association for '" + appName + "' was requested and raised no error, but " //$NON-NLS-1$
                    + "no matching application appeared for project '" + projectName + "' within the " //$NON-NLS-1$
                    + "read-back budget on branch '" + branch + "'. Use get_applications to check the " //$NON-NLS-1$
                    + "actual state."; //$NON-NLS-1$
            default: // UNVERIFIED
                return "The association for '" + appName + "' was requested but the application " //$NON-NLS-1$
                    + "read-back could not be completed (see the EDT log). Use get_applications to "
                    + "check the actual state."; //$NON-NLS-1$
        }
    }

    /** Result of the read-back probe: the found application (or null), its verdict, and evidence. */
    private static final class ApplicationProbe
    {
        final IApplication app;
        final Binding binding;
        final JsonArray appsArray;

        ApplicationProbe(IApplication app, Binding binding, JsonArray appsArray)
        {
            this.app = app;
            this.binding = binding;
            this.appsArray = appsArray;
        }
    }
}
