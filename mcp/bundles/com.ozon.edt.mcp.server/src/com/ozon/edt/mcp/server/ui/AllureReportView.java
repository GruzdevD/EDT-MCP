/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: embedded browser view that shows the Allure report of a BDD run.
 */

package com.ozon.edt.mcp.server.ui;

import java.nio.file.Path;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
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
import com.ozon.edt.mcp.server.tools.impl.vanessa.AllureReportService;

/**
 * An EDT view that renders the generated Allure report through SWT {@link Browser},
 * served by the process-wide {@link AllureHttpServer} on loopback. Purely presentational —
 * the generation and serving happen in {@link AllureReportService} and
 * {@link AllureHttpServer}; this view only owns the {@link Browser} widget and the
 * programmatic open/activate logic.
 */
public class AllureReportView extends ViewPart
{
    /** The view ID as declared in plugin.xml. */
    public static final String ID = "com.ozon.edt.mcp.server.allure.allureView"; //$NON-NLS-1$

    private Browser browser;

    /** URL handed to a freshly-created view; consumed by {@link #createPartControl}. */
    private static volatile String pendingUrl;

    @Override
    public void createPartControl(Composite parent)
    {
        String url = pendingUrl;
        pendingUrl = null;
        try
        {
            browser = new Browser(parent, SWT.NONE);
            if (url != null)
            {
                browser.setUrl(url);
            }
        }
        catch (Throwable t)
        {
            // The SWT browser engine is unavailable on this platform/build. Rather
            // than fail part creation (which E4 surfaces as a cause-less
            // "Could not create part"), log the real cause and keep the view usable
            // by showing the served URL for manual opening.
            Activator.logError("Allure report view: SWT browser unavailable, showing URL fallback", t); //$NON-NLS-1$
            Label label = new Label(parent, SWT.WRAP);
            label.setText("Allure report available at: " + (url == null ? "(none)" : url) //$NON-NLS-1$ //$NON-NLS-2$
                + "\nOpen it manually, or call vanessa_open_allure_report with detached=true."); //$NON-NLS-1$ //$NON-NLS-2$
        }
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
        final String url;
        try
        {
            url = AllureHttpServer.getInstance().start(reportDir);
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
                Activator.logInfo("Allure view: descriptor " + (desc == null ? "NOT FOUND" : "present")); //$NON-NLS-1$ //$NON-NLS-2$
                Object probe = desc == null ? null : desc.createView();
                Activator.logInfo("Allure view: direct descriptor.createView() -> " + probe); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (Throwable t)
            {
                Activator.logError("Allure view: direct descriptor.createPart() failed", t); //$NON-NLS-1$
            }
        }
    }
}
