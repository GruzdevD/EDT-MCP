/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ozon.edt.mcp.server.ui;

import org.eclipse.osgi.util.NLS;

/**
 * Messages for the MCP request/response History view. The English
 * {@code messages.properties} is the default/fallback; {@code messages_ru.properties}
 * provides the Russian translation. The locale is auto-selected by the Eclipse/EDT
 * runtime (Platform NL) - no manual locale handling. Only this in-EDT view UI is
 * bilingual; the MCP tool/wire surface stays English-only.
 */
public class Messages extends NLS {

    private static final String BUNDLE_NAME = "com.ozon.edt.mcp.server.ui.messages"; //$NON-NLS-1$

    public static String McpHistoryView_TabHistory; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_TabStatistics; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ColumnTime; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ColumnMethodTool; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ColumnDuration; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ColumnStatus; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatusOk; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatusError; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_DetailHeader; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_CopyJson; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_CopyJsonTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_DetailNone; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_RequestSection; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ResponseSection; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsToolMethod; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsCalls; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsShare; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsAvg; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsReqChars; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsRespChars; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsTokens; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsErrors; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsContextWeight; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_PauseAction; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_PauseTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ClearAction; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ClearTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterAction; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterMethodLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterMethodTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterMethodPlaceholder; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterErrorsOnlyLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterErrorsOnlyTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterMinDurationLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterMinDurationTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterIntervalLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterIntervalTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterIntervalTo; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_AutoScrollAction; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_AutoScrollTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_RecordingOn; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_RecordingOff; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_UpdatesPaused; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_UpdatesLive; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ContentDescription; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_Summary; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_Top3None; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_Top3Prefix; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_LoadReport; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_ResetResults; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_ResetTitle; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_ResetMessage; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_StatusGenerating; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_StatusServed; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_StatusCleared; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_StatusNoReport; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_NoReportToLoad; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_NoAllureCli; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_GenerateFailed; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String AllureView_ResetFailed; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key

    public static String testSelection_view_name; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String testSelection_view_description; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_TabYaxunit; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_TabBdd; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_ProjectLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_ProjectTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_ConfigLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_ConfigTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_LoadButton; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_FilterLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_FilterPlaceholder; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_ColumnPath; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_ColumnType; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_ColumnParent; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_ColumnFeature; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_ColumnTags; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_RefreshButton; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_RunYaxunitButton; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_RunBddButton; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_BddProjectLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_BddProjectTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_SelectProjectFirst; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_StatusLoadingModules; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_StatusModulesLoaded; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_StatusSuitesLoaded; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_StatusLoadError; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_EnterBddProject; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_StatusLoadingFeatures; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_StatusFeaturesLoaded; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_NoModulesSelected; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_EnterConfigFirst; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_StatusRunningYaxunit; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_StatusWaitingJob; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_JobEnded; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_CancelButton; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_CancelTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_NoActiveJob; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_CancellingJob; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_NoFeaturesSelected; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_EnterBddProjectArgs; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_StatusRunningBdd; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_StatusWaitingBdd; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_BddLaunchFailed; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_BddEnded; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_StatusBddPolling; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String TestSelection_Done; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key

    static {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }

    private Messages() {
    }
}
