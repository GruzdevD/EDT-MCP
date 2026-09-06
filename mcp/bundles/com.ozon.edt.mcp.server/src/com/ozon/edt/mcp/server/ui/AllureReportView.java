/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: embedded browser view that shows the Allure report of a BDD run.
 */

package com.ozon.edt.mcp.server.ui;

import java.nio.file.Path;

import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
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

    @Override
    public void createPartControl(Composite parent)
    {
        browser = new Browser(parent, org.eclipse.swt.SWT.NONE);
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
            Activator.logError("Failed to open the Allure report view", e); //$NON-NLS-1$
        }
    }
}
