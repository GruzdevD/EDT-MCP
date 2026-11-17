/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.preferences;

import java.util.Set;

/** Keeps historical fixtures independent of unrelated, separately tested newer migrations. */
final class ToolMigrationTestSupport
{
    /** The 23 tool names the v25 read-only migration disables (mirrors the production registry). */
    static final Set<String> READ_ONLY_V25_ADDITIONS = Set.of(
        "create_project_application", //$NON-NLS-1$
        "create_specter_launch_config", //$NON-NLS-1$
        "delete_project_application", //$NON-NLS-1$
        "get_launch_state", //$NON-NLS-1$
        "import_project", //$NON-NLS-1$
        "list_infobases", //$NON-NLS-1$
        "list_yaxunit_tests", //$NON-NLS-1$
        "plugin_check_for_update", //$NON-NLS-1$
        "plugin_update", //$NON-NLS-1$
        "remove_standalone_server", //$NON-NLS-1$
        "set_project_checks", //$NON-NLS-1$
        "vanessa_doctor", //$NON-NLS-1$
        "vanessa_get_artifacts", //$NON-NLS-1$
        "vanessa_get_execution_status", //$NON-NLS-1$
        "vanessa_get_feature", //$NON-NLS-1$
        "vanessa_get_log", //$NON-NLS-1$
        "vanessa_get_test_report", //$NON-NLS-1$
        "vanessa_list_features", //$NON-NLS-1$
        "vanessa_list_launches", //$NON-NLS-1$
        "vanessa_open_allure_report", //$NON-NLS-1$
        "vanessa_run_by_tags", //$NON-NLS-1$
        "vanessa_run_feature", //$NON-NLS-1$
        "vanessa_setup"); //$NON-NLS-1$

    private ToolMigrationTestSupport()
    {
    }

    /** Historical steps remain literal assertions; append only later declared obligations. */
    static void addReadOnlyAdditionsAfter(int testedVersion, ToolPreset preset, Set<String> expected)
    {
        if (!preset.isReadOnly())
        {
            return;
        }
        var additions = preset == ToolPreset.ANALYSIS_ONLY
            ? ToolSettingsService.ANALYSIS_ONLY_MIGRATION_ADDITIONS_BY_VERSION
            : ToolSettingsService.CODE_REVIEW_MIGRATION_ADDITIONS_BY_VERSION;
        additions.forEach((version, names) -> {
            if (version > testedVersion)
            {
                expected.addAll(names);
            }
        });
    }
}
