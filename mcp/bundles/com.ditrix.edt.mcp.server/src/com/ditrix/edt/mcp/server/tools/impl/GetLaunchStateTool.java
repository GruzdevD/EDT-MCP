package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamsProxy;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ExternalProcess;

/**
 * State view over MCP: every EDT launch (live and recently terminated) with its OS
 * processes, console output tail and exit codes, running 1C executables at OS level,
 * modal dialogs waiting for a button, and cross-check diagnostics (orphaned processes,
 * non-zero exits). Use when a launch or a DB update appears hung.
 */
public class GetLaunchStateTool implements IMcpTool {

    public static final String NAME = "get_launch_state"; //$NON-NLS-1$

    private static final String PARAM_INCLUDE_TERMINATED = "includeTerminated"; //$NON-NLS-1$
    private static final String PARAM_TAIL_LINES = "tailLines"; //$NON-NLS-1$
    private static final String PARAM_PROJECT_NAME = "projectName"; //$NON-NLS-1$

    private static final int DEFAULT_TAIL_LINES = 40;
    private static final int MAX_TAIL_LINES = 200;
    private static final long OS_SWEEP_TIMEOUT_MS = 5_000L;
    private static final long WINDOW_PROBE_TIMEOUT_MS = 5_000L;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Full visibility into launch processes (the State view over MCP): every EDT launch "
            + "(live and recently terminated) with its OS processes, console output tail and exit codes, "
            + "plus all running 1C executables (1cv8/1cv8c/ibsrv/...) with pid and start time, and any "
            + "modal dialogs waiting for a button. Use this when a launch or a DB update appears hung. "
            + "Full parameters and examples: call get_tool_guide('get_launch_state').";
    }

    @Override
    public String getInputSchema() {
        return JsonSchemaBuilder.object()
            .stringProperty(PARAM_PROJECT_NAME, "Optional project filter (launch-configuration project name)")
            .booleanProperty(PARAM_INCLUDE_TERMINATED, "Include terminated launches and their exit codes (default true)")
            .integerProperty(PARAM_TAIL_LINES, "Console tail lines per process (default 40, max 200)")
            .build();
    }

    @Override
    public String getOutputSchema() {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true)
            .objectArrayProperty("launches", "Eclipse launches (live and terminated) with their processes and console tails")
            .integerProperty("launchCount", "Number of launches returned")
            .objectArrayProperty("osProcesses", "Running 1C executables at OS level (1cv8, 1cv8c, ibsrv, ...): pid, etime, cpu, command")
            .integerProperty("osProcessCount", "Number of running 1C OS processes")
            .objectArrayProperty("modalWindows", "Visible 1C windows with a title (macOS best-effort probe): a 1cv8 window waiting for a button shows up here with its dialog text")
            .objectProperty("diagnostics", "Cross-check summary: orphaned 1C processes (running at OS level but not owned by any live launch), terminated processes with non-zero exit codes, live-launch count")
            .build();
    }

    @Override
    public IMcpTool.ResponseType getResponseType() {
        return IMcpTool.ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params) {
        String projectFilter = JsonUtils.extractStringArgument(params, PARAM_PROJECT_NAME);
        boolean includeTerminated = !JsonUtils.extractBooleanArgument(params, PARAM_INCLUDE_TERMINATED, false);
        int tailLines = JsonUtils.extractIntArgument(params, PARAM_TAIL_LINES, DEFAULT_TAIL_LINES);
        if (tailLines <= 0) {
            tailLines = DEFAULT_TAIL_LINES;
        }
        tailLines = Math.min(tailLines, MAX_TAIL_LINES);
        try {
            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            if (debugPlugin == null) {
                return ToolResult.error("DebugPlugin not available (headless workspace?)").toJson();
            }
            ILaunchManager mgr = debugPlugin.getLaunchManager();
            List<Map<String, Object>> launches = new ArrayList<>();
            int liveCount = 0;
            for (ILaunch launch : mgr.getLaunches()) {
                boolean terminated = launch.isTerminated();
                if (!terminated && !includeTerminated) {
                    continue;
                }
                Map<String, Object> entry = buildLaunchEntry(launch, projectFilter, tailLines);
                if (entry != null) {
                    if (!terminated) {
                        liveCount++;
                    }
                    launches.add(entry);
                }
            }
            List<Map<String, Object>> osProcesses = listOsProcesses();
            List<Map<String, Object>> modalWindows = listModalWindows();
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("liveLaunchCount", liveCount);
            diagnostics.put("orphanedOsProcesses", orphanedOsProcesses(osProcesses, launches));
            diagnostics.put("nonZeroExitProcesses", nonZeroExitProcesses(launches));
            return ToolResult.success()
                .put("launches", launches)
                .put("launchCount", launches.size())
                .put("osProcesses", osProcesses)
                .put("osProcessCount", osProcesses.size())
                .put("modalWindows", modalWindows)
                .put("diagnostics", diagnostics)
                .toJson();
        } catch (Exception e) {
            Activator.logError("Error in get_launch_state", e);
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    private static Map<String, Object> buildLaunchEntry(ILaunch launch, String projectFilter, int tailLines) {
        ILaunchConfiguration config = launch.getLaunchConfiguration();
        String projectName = resolveProjectName(config);
        if (projectFilter != null && !projectFilter.isEmpty()
                && (projectName == null || !projectName.contains(projectFilter))) {
            return null;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        if (config != null) {
            entry.put("launchConfiguration", config.getName());
            try {
                entry.put("configurationType", config.getType().getIdentifier());
            } catch (CoreException e) {
                entry.put("configurationType", "<unavailable>");
            }
        }
        entry.put("project", projectName);
        entry.put("terminated", launch.isTerminated());
        entry.put("mode", launch.getLaunchMode());
        List<Map<String, Object>> processes = new ArrayList<>();
        for (IProcess process : launch.getProcesses()) {
            if (process != null) {
                processes.add(buildProcessEntry(process, tailLines));
            }
        }
        entry.put("processes", processes);
        entry.put("processCount", processes.size());
        return entry;
    }

    private static Map<String, Object> buildProcessEntry(IProcess process, int tailLines) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("label", safe(process.getLabel()));
        entry.put("terminated", process.isTerminated());
        if (process.isTerminated()) {
            try {
                entry.put("exitValue", process.getExitValue());
            } catch (Exception e) {
                // already terminated without a value; skip
            }
        }
        String command = safe(process.getAttribute(IProcess.ATTR_CMDLINE));
        if (!command.isEmpty()) {
            entry.put("command", command);
        }
        String tail = consoleTail(process, tailLines);
        if (!tail.isEmpty()) {
            entry.put("outputTail", tail);
        }
        return entry;
    }

    private static String consoleTail(IProcess process, int tailLines) {
        IStreamsProxy streams = process.getStreamsProxy();
        if (streams == null) {
            return "";
        }
        String content = streams.getOutputStreamMonitor().getContents();
        if (content == null || content.isEmpty()) {
            return "";
        }
        try {
            String[] lines = content.split("\r\n|\r|\n");
            int from = Math.max(0, lines.length - tailLines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.length; i++) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(lines[i]);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<Map<String, Object>> listOsProcesses() {
        List<Map<String, Object>> out = new ArrayList<>();
        ExternalProcess.Result ps = ExternalProcess.run(
            Arrays.asList("/bin/ps", "-axo", "pid=,etime=,%cpu=,command="), null, OS_SWEEP_TIMEOUT_MS);
        if (ps == null || !ps.succeeded() || ps.output == null) {
            return out;
        }
        for (String line : ps.output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 4);
            if (parts.length < 4 || !isOneCExecutable(parts[3])) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("pid", parseIntSafe(parts[0]));
            entry.put("elapsed", parts[1]);
            entry.put("cpuPercent", parts[2]);
            entry.put("command", parts[3]);
            out.add(entry);
        }
        return out;
    }

    private static boolean isOneCExecutable(String command) {
        String name = command;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int space = name.indexOf(' ');
        if (space >= 0) {
            name = name.substring(0, space);
        }
        return name.equals("1cv8") || name.equals("1cv8c") || name.equals("1cv8s")
            || name.equals("ibcmd") || name.equals("ibsrv")
            || name.equals("ras") || name.equals("rphost") || name.equals("rmngr");
    }

    private static List<Map<String, Object>> listModalWindows() {
        List<Map<String, Object>> out = new ArrayList<>();
        String script = "tell application \"System Events\" to get {name, unix id} of (every process whose name is \"1cv8\" or name is \"1cv8c\")";
        ExternalProcess.Result osa = ExternalProcess.run(
            Arrays.asList("/usr/bin/osascript", "-e", script), null, WINDOW_PROBE_TIMEOUT_MS);
        if (osa == null || !osa.succeeded() || osa.output == null || osa.output.isBlank()) {
            return out;
        }
        for (String line : osa.output.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.equals("missing value")) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("windowTitle", trimmed);
                out.add(entry);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> orphanedOsProcesses(List<Map<String, Object>> osProcesses,
            List<Map<String, Object>> launches) {
        List<Map<String, Object>> orphans = new ArrayList<>();
        for (Map<String, Object> os : osProcesses) {
            Object pidObj = os.get("pid");
            if (pidObj == null) {
                continue;
            }
            String pid = String.valueOf(pidObj);
            boolean owned = false;
            for (Map<String, Object> launch : launches) {
                if (Boolean.TRUE.equals(launch.get("terminated"))) {
                    continue;
                }
                List<Map<String, Object>> processes = (List<Map<String, Object>>) launch.get("processes");
                if (processes == null) {
                    continue;
                }
                for (Map<String, Object> process : processes) {
                    String label = String.valueOf(process.get("label"));
                    String command = String.valueOf(process.get("command"));
                    if (label.contains(pid) || command.contains(pid)) {
                        owned = true;
                    }
                }
            }
            if (owned) {
                continue;
            }
            Map<String, Object> orphan = new LinkedHashMap<>(os);
            orphan.put("hint", "1C process running at OS level but owned by no live launch; likely orphaned "
                + "after an EDT restart. It can hold an infobase lock and block update_database. Kill it "
                + "manually (kill <pid>) or via terminate_launch if a matching launch reappears.");
            orphans.add(orphan);
        }
        return orphans;
    }

    private static List<Map<String, Object>> nonZeroExitProcesses(List<Map<String, Object>> launches) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> launch : launches) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> processes = (List<Map<String, Object>>) launch.get("processes");
            if (processes == null) {
                continue;
            }
            for (Map<String, Object> process : processes) {
                Object exit = process.get("exitValue");
                if (exit instanceof Integer && (Integer) exit != 0) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("launchConfiguration", launch.get("launchConfiguration"));
                    entry.put("label", process.get("label"));
                    entry.put("exitValue", exit);
                    out.add(entry);
                }
            }
        }
        return out;
    }

    private static String resolveProjectName(ILaunchConfiguration config) {
        if (config == null) {
            return "";
        }
        try {
            return safe(config.getAttribute("com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME", "")); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (Exception e) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Integer parseIntSafe(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
