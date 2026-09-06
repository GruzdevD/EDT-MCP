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
import org.eclipse.ui.views.IViewDescriptor;
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

                // Decisive control probe (one shot): are the OTHER views of this same
                // bundle registered at all? That tells us whether the whole
                // org.eclipse.ui.views contribution channel works in this E4 EDT or
                // whether NO plugin.xml <view> of ours is ever read.
                IViewDescriptor[] all = PlatformUI.getWorkbench().getViewRegistry()
                    .getViews();
                Activator.logInfo("Allure view: total registry views=" + (all == null ? -1 : all.length)); //$NON-NLS-1$ //$NON-NLS-2$
                if (all != null)
                {
                    int own = 0;
                    StringBuilder others = new StringBuilder();
                    for (IViewDescriptor v : all)
                    {
                        String vid = v.getId();
                        if (vid != null && vid.startsWith("com.ozon.edt.mcp.server")) //$NON-NLS-1$
                        {
                            own++;
                            Activator.logInfo("Allure view: OWN view registered -> " + vid); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        else if (others.length() < 1600)
                        {
                            others.append(vid).append(", "); //$NON-NLS-1$
                        }
                    }
                    Activator.logInfo("Allure view: own views count=" + own + "; sample others: " + others); //$NON-NLS-1$ //$NON-NLS-2$
                }
                // What does the Equinox extension registry itself think about this point?
                try
                {
                    org.eclipse.core.runtime.IConfigurationElement[] el = org.eclipse.core.runtime.Platform
                        .getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.views"); //$NON-NLS-1$
                    int ozon = 0;
                    String found = "";
                    for (org.eclipse.core.runtime.IConfigurationElement c : el)
                    {
                        String clazz = c.getAttribute("class"); //$NON-NLS-1$
                        if (clazz != null && clazz.contains("com.ozon.edt.mcp.server")) //$NON-NLS-1$
                        {
                            ozon++;
                            found += " " + clazz;
                        }
                    }
                    Activator.logInfo("Allure view: ext-registry org.eclipse.ui.views elements=" + el.length //$NON-NLS-1$ //$NON-NLS-2$
                        + "; our class attrs(" + ozon + "):" + found); //$NON-NLS-1$ //$NON-NLS-2$
                }
                catch (Throwable t2)
                {
                    Activator.logError("Allure view: ext registry query failed", t2); //$NON-NLS-1$
                }

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
