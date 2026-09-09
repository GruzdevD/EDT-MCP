/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: embedded browser view that shows the Allure report of a BDD run.
 */

package com.ozon.edt.mcp.server.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.ozon.edt.mcp.server.Activator;
import com.ozon.edt.mcp.server.preferences.PreferenceConstants;
import com.ozon.edt.mcp.server.tools.impl.vanessa.AllureReportService;

/**
 * An EDT view that renders the generated Allure report through SWT {@link Browser},
 * served by the process-wide {@link AllureHttpServer} on loopback. The view owns a
 * small toolbar: <b>Load report</b> re-generates the report from the configured (or
 * last opened) raw results and shows it here, and <b>Reset results</b> asks for
 * confirmation, then deletes both the generated report and the raw Allure results so
 * stale runs do not accumulate. Generation and deletion run on a background thread so
 * the EDT UI thread is never blocked; the {@link Browser} widget renders whatever
 * {@link #open(Path)} or the toolbar hands to it.
 */
public class AllureReportView extends ViewPart
{
    /** The view ID as declared in plugin.xml. */
    public static final String ID = "com.ozon.edt.mcp.server.allure.allureView"; //$NON-NLS-1$

    private Browser browser;
    private Button loadButton;
    private Button resetButton;
    private Label statusLabel;

    /** URL handed to a freshly-created view; consumed by {@link #createPartControl}. */
    private static volatile String pendingUrl;
    /** Raw Allure results dir of the last report shown/loaded here, if any. */
    private static volatile Path currentResultsDir;
    /** Report dir of the last report shown/loaded here, if any. */
    private static volatile Path currentReportDir;

    @Override
    public void createPartControl(Composite parent)
    {
        String url = pendingUrl;
        pendingUrl = null;

        Composite body = new Composite(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 2;
        gl.marginHeight = 2;
        gl.verticalSpacing = 2;
        body.setLayout(gl);

        createHeader(body);

        try
        {
            browser = new Browser(body, SWT.NONE);
            browser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            if (url != null)
            {
                browser.setUrl(url);
            }
            refreshStatus(url != null);
        }
        catch (Throwable t)
        {
            // The SWT browser engine is unavailable on this platform/build. Rather
            // than fail part creation (which E4 surfaces as a cause-less
            // "Could not create part"), log the real cause and keep the view usable
            // by showing the served URL for manual opening. The toolbar buttons stay
            // disabled since there is no browser to render into.
            Activator.logError("Allure report view: SWT browser unavailable, showing URL fallback", t); //$NON-NLS-1$
            Label label = new Label(body, SWT.WRAP);
            label.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            label.setText("Report available at: " + (url == null ? "(none)" : url) //$NON-NLS-1$ //$NON-NLS-2$
                + "\nOpen it manually, or call vanessa_open_allure_report with detached=true."); //$NON-NLS-1$ //$NON-NLS-2$
            setBusy(true);
        }
    }

    /** Builds the header row: Load / Reset buttons and a status label. */
    private void createHeader(Composite parent)
    {
        Composite header = new Composite(parent, SWT.NONE);
        GridLayout hgl = new GridLayout(3, false);
        hgl.marginWidth = 0;
        hgl.marginHeight = 0;
        hgl.horizontalSpacing = 6;
        header.setLayout(hgl);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        loadButton = new Button(header, SWT.PUSH);
        loadButton.setText(Messages.AllureView_LoadReport);
        loadButton.addListener(SWT.Selection, e -> loadReport());

        resetButton = new Button(header, SWT.PUSH);
        resetButton.setText(Messages.AllureView_ResetResults);
        resetButton.addListener(SWT.Selection, e -> resetResults());

        statusLabel = new Label(header, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    @Override
    public void setFocus()
    {
        if (browser != null && !browser.isDisposed())
        {
            browser.setFocus();
        }
    }

    /** Navigates this view's browser to a URL (no-op when the widget is gone). */
    public void load(String url)
    {
        if (browser != null && !browser.isDisposed() && url != null)
        {
            browser.setUrl(url);
        }
    }

    /**
     * Serves {@code reportDir} on loopback and opens it in this view. Safe in
     * headless mode: without a display the view is simply not opened (callers that
     * need an out-of-EDT destination should use {@code Program.launch} instead) and,
     * since this method never throws, a stuck UI never propagates to the caller.
     *
     * @param reportDir the generated report (must contain {@code index.html})
     * @return the served base URL (already open), or {@code null} when there is no
     *         UI available to host the view
     */
    public static String open(Path reportDir)
    {
        return open(reportDir, null);
    }

    /**
     * {@link #open(Path)} variant that also records the raw Allure {@code resultsDir}
     * the report was generated from, so the view's <b>Load report</b> button can
     * regenerate from the same results without a Preferences setting.
     *
     * @param reportDir  the generated report (must contain {@code index.html})
     * @param resultsDir the raw Allure results dir the report came from, or {@code null}
     * @return the served base URL (already open), or {@code null} when there is no
     *         UI available to host the view
     */
    public static String open(Path reportDir, Path resultsDir)
    {
        final String url;
        try
        {
            url = AllureHttpServer.getInstance().start(reportDir);
            if (reportDir != null)
            {
                currentReportDir = reportDir.toAbsolutePath();
            }
            if (resultsDir != null)
            {
                currentResultsDir = resultsDir.toAbsolutePath();
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to start the Allure report server", e); //$NON-NLS-1$
            return null;
        }
        if (url == null)
        {
            return null;
        }

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            // Headless or shutting down: nothing to navigate.
            return url;
        }

        // Hand the URL to the view so createPartControl can render it (or, when the
        // browser is unavailable, show it as a fallback) before any async navigation.
        pendingUrl = url;
        display.asyncExec(() -> openOnUiThread(url));
        return url;
    }

    private static void openOnUiThread(String url)
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            if (page == null)
            {
                // No active window yet — fall back to the first one that has a page.
                for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
                {
                    page = w.getActivePage();
                    if (page != null)
                    {
                        break;
                    }
                }
            }
            if (page == null)
            {
                return;
            }
            IViewPart part = page.showView(ID, null, IWorkbenchPage.VIEW_ACTIVATE);
            if (part instanceof AllureReportView reportView)
            {
                reportView.load(url);
            }
        }
        catch (PartInitException e)
        {
            // EDT's E4-compat WorkbenchPage turns a failed view creation into a
            // cause-less PartInitException. Re-probe the view descriptor directly so
            // the real cause is logged (createPart both throws and returns non-null
            // on success, so a NULL here pinpoints a silent registry failure).
            try
            {
                org.eclipse.ui.views.IViewDescriptor desc = PlatformUI.getWorkbench()
                    .getViewRegistry().find(ID);
                Activator.logInfo("Allure view: descriptor " + (desc == null ? "NOT FOUND" : "present")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Object probe = desc == null ? null : desc.createView();
                Activator.logInfo("Allure view: direct descriptor.createView() -> " + probe); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (Throwable t)
            {
                Activator.logError("Allure view: direct descriptor.createPart() failed", t); //$NON-NLS-1$
            }
        }
    }

    /**
     * Regenerates the Allure report from the configured (or last opened) raw results
     * and opens it in this view. Runs off the UI thread; buttons are disabled while
     * it works.
     */
    private void loadReport()
    {
        Path results = resolveResultsDir();
        if (results == null || !AllureReportService.isResultsDir(results))
        {
            setStatus(Messages.AllureView_NoReportToLoad);
            return;
        }

        setBusy(true);
        setStatus(Messages.AllureView_StatusGenerating);
        String reportPref = preference(PreferenceConstants.PREF_ALLURE_REPORT_DIR);
        new Thread(() ->
        {
            try
            {
                String allureBin = resolveAllureBin(results);
                if (allureBin == null)
                {
                    throw new IllegalStateException(Messages.AllureView_NoAllureCli);
                }
                Path reportDir = AllureReportService.generate(
                    System.getProperty("user.home"), null, //$NON-NLS-1$
                    Paths.get(allureBin), results, overridePath(reportPref, currentReportDir));
                String url = AllureHttpServer.getInstance().start(reportDir);
                Path loadedReport = reportDir.toAbsolutePath();
                Path loadedResults = results.toAbsolutePath();
                runOnUi(() ->
                {
                    currentReportDir = loadedReport;
                    currentResultsDir = loadedResults;
                    if (browser != null && !browser.isDisposed())
                    {
                        browser.setUrl(url);
                    }
                    refreshStatus(true);
                    setBusy(false);
                });
            }
            catch (Throwable t)
            {
                Activator.logError("Allure report view: load failed", t); //$NON-NLS-1$
                String msg = t.getMessage();
                runOnUi(() ->
                {
                    setStatus(MessageFormat.format(Messages.AllureView_GenerateFailed, msg));
                    setBusy(false);
                });
            }
        }, "Allure report load").start(); //$NON-NLS-1$
    }

    /**
     * Asks for confirmation, then deletes the generated report dir and the raw Allure
     * results so stale runs do not accumulate. Runs off the UI thread.
     */
    private void resetResults()
    {
        Path results = resolveResultsDir();
        Path served = AllureHttpServer.getInstance().root();
        Path report = served != null ? served : currentReportDir;
        if (results == null && report == null)
        {
            return;
        }

        MessageDialog confirm = new MessageDialog(
            getSite().getShell(),
            Messages.AllureView_ResetTitle,
            null,
            MessageFormat.format(Messages.AllureView_ResetMessage, report == null ? "-" : report), //$NON-NLS-1$
            MessageDialog.CONFIRM,
            new String[] { IDialogConstants.OK_LABEL, IDialogConstants.CANCEL_LABEL },
            1);
        if (confirm.open() != IDialogConstants.OK_ID)
        {
            return;
        }

        setBusy(true);
        Path fResults = results;
        Path fReport = report;
        new Thread(() ->
        {
            try
            {
                if (fReport != null)
                {
                    AllureReportService.clearReport(fReport);
                }
                if (fResults != null)
                {
                    AllureReportService.clearResults(fResults);
                }
                runOnUi(() ->
                {
                    if (browser != null && !browser.isDisposed())
                    {
                        browser.setUrl("about:blank"); //$NON-NLS-1$
                    }
                    currentReportDir = null;
                    AllureHttpServer.getInstance().stop();
                    refreshStatus(false);
                    setStatus(Messages.AllureView_StatusCleared);
                    setBusy(false);
                });
            }
            catch (Throwable t)
            {
                Activator.logError("Allure report view: reset failed", t); //$NON-NLS-1$
                String msg = t.getMessage();
                runOnUi(() ->
                {
                    setStatus(MessageFormat.format(Messages.AllureView_ResetFailed, msg));
                    setBusy(false);
                });
            }
        }, "Allure report reset").start(); //$NON-NLS-1$
    }

    /**
     * The raw results dir to load a report from, in order: the configured
     * directory (if it holds results), the last opened/served ones, or a results
     * dir auto-detected as a sibling of the last report (by default a report is
     * generated into {@code resultsDir.getParent()/allure-report}, so the results
     * sit next to it).
     */
    private static Path resolveResultsDir()
    {
        String pref = preference(PreferenceConstants.PREF_ALLURE_RESULTS_DIR);
        if (pref != null && !pref.trim().isEmpty())
        {
            Path p = Paths.get(pref.trim());
            if (AllureReportService.isResultsDir(p))
            {
                return p.toAbsolutePath();
            }
        }
        if (currentResultsDir != null)
        {
            return currentResultsDir;
        }
        if (currentReportDir != null)
        {
            return AllureReportService.findResultsDir(currentReportDir.getParent());
        }
        return null;
    }

    /** An explicit preference path override, or the supplied fallback. */
    private static Path overridePath(String pref, Path fallback)
    {
        if (pref != null && !pref.trim().isEmpty())
        {
            return Paths.get(pref.trim()).toAbsolutePath();
        }
        return fallback;
    }

    /** The allure binary for a results dir, or {@code null} when none is resolvable. */
    private static String resolveAllureBin(Path results)
    {
        Path bin = AllureReportService.resolveAllureBin(AllureReportService.deriveProject(results), null);
        return bin == null ? null : bin.toAbsolutePath().toString();
    }

    /** A preference string from the plugin store, or {@code null} when unavailable (e.g. headless). */
    private static String preference(String key)
    {
        try
        {
            Activator activator = Activator.getDefault();
            return activator == null ? null : activator.getPreferenceStore().getString(key);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private void setBusy(boolean busy)
    {
        if (loadButton != null && !loadButton.isDisposed())
        {
            loadButton.setEnabled(!busy);
        }
        if (resetButton != null && !resetButton.isDisposed())
        {
            resetButton.setEnabled(!busy);
        }
    }

    private void setStatus(String text)
    {
        if (statusLabel != null && !statusLabel.isDisposed())
        {
            statusLabel.setText(text == null ? "" : text); //$NON-NLS-1$
        }
    }

    /** Reflects the currently served report (or its absence) in the status label. */
    private void refreshStatus(boolean serving)
    {
        Path served = AllureHttpServer.getInstance().root();
        if (serving && served != null)
        {
            setStatus(MessageFormat.format(Messages.AllureView_StatusServed, served));
        }
        else
        {
            setStatus(Messages.AllureView_StatusNoReport);
        }
    }

    /** Runs on the UI thread when possible; otherwise (headless) in-line. */
    private void runOnUi(Runnable r)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            display.asyncExec(r);
        }
        else
        {
            r.run();
        }
    }
}
