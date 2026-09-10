/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: in-EDT view for choosing and running tests (YAxUnit modules and
 * Vanessa BDD features), with an embedded JUnit-Markdown report pane.
 */

package com.ditrix.edt.mcp.server.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.part.ViewPart;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.ui.TestSelectionSupport.FeatureRow;
import com.ditrix.edt.mcp.server.ui.TestSelectionSupport.SuiteRow;
import com.ditrix.edt.mcp.server.ui.TestSelectionSupport.TestRow;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * In-EDT view over the plugin's two test runners. A {@link CTabFolder} with two tabs:
 * <ul>
 *   <li><b>YAxUnit</b> &mdash; a checkbox tree of test suites (modules with test methods, from
 *       {@code list_yaxunit_tests}) and their subordinate tests, filtered by name; <i>Run
 *       selected</i> invokes {@code run_yaxunit_tests} at the chosen granularity.</li>
 *   <li><b>Vanessa BDD</b> &mdash; a checkbox list of {@code .feature} files (from
 *       {@code vanessa_list_features}); <i>Run selected</i> invokes {@code vanessa_run_feature}
 *       and follows status to the JUnit report.</li>
 * </ul>
 * A shared bottom pane shows whichever tool returned (an embedded Markdown report:
 * for YAxUnit the job's JUnit-Markdown terminal result, for BDD the parsed report body).
 *
 * <p><b>Threading.</b> Every tool call and poll runs on a worker {@link Thread}; the UI is
 * touched only through {@link #runOnUi(Runnable)} with disposed-guards, matching
 * {@code AllureReportView}. The view never re-implements launch logic &mdash; it calls the
 * registered MCP tools in-process via {@link McpToolRegistry}.
 */
public class TestSelectionView extends ViewPart
{
    /** The view ID as declared in plugin.xml. */
    public static final String ID = "com.ditrix.edt.mcp.server.tests.testSelectionView"; //$NON-NLS-1$

    private static final String TOOL_LIST_YAXUNIT_TESTS = "list_yaxunit_tests"; //$NON-NLS-1$
    private static final String TOOL_LIST_CONFIGURATIONS = "list_configurations"; //$NON-NLS-1$
    private static final String TOOL_RUN_YAXUNIT = "run_yaxunit_tests"; //$NON-NLS-1$
    private static final String TOOL_GET_JOB_STATUS = "get_job_status"; //$NON-NLS-1$
    private static final String TOOL_CANCEL_JOB = "cancel_job"; //$NON-NLS-1$
    private static final String TOOL_LIST_FEATURES = "vanessa_list_features"; //$NON-NLS-1$
    private static final String TOOL_RUN_FEATURE = "vanessa_run_feature"; //$NON-NLS-1$
    private static final String TOOL_EXEC_STATUS = "vanessa_get_execution_status"; //$NON-NLS-1$
    private static final String TOOL_TEST_REPORT = "vanessa_get_test_report"; //$NON-NLS-1$

    private static final int POLL_INTERVAL_MS = 1500;

    // Shared report pane
    private Text reportText;
    private Label statusLabel;

    // YAxUnit tab
    private Combo projectCombo;
    private Combo yaxunitConfigCombo;
    private Text moduleFilterText;
    private CheckboxTreeViewer suiteViewer;
    private Button yaxunitRunButton;
    private Button yaxunitCancelButton;
    /** YAxUnit background job currently being polled, or {@code null} when none is active. */
    private volatile String activeYaxunitJobId;
    private final List<SuiteRow> suiteRows = new ArrayList<>();

    // Vanessa BDD tab
    private Combo bddProjectCombo;
    private Combo bddConfigCombo;
    private Text featureFilterText;
    private CheckboxTableViewer featureViewer;
    private Button featureRunButton;
    private final List<FeatureRow> featureRows = new ArrayList<>();

    @Override
    public void createPartControl(Composite parent)
    {
        parent.setLayout(new FillLayout());

        SashForm sash = new SashForm(parent, SWT.VERTICAL);
        createTabs(sash);
        createReportPane(sash);
        sash.setWeights(60, 40);

        fillProjectCombo();
        fillBddProjects();
        loadYaxunitConfigs();
        loadBddConfigs();
    }

    // ------------------------------------------------------------------- tabs

    private void createTabs(Composite parent)
    {
        CTabFolder tabFolder = new CTabFolder(parent, SWT.BORDER);
        tabFolder.setSimple(true);

        CTabItem yaxunitTab = new CTabItem(tabFolder, SWT.NONE);
        yaxunitTab.setText(Messages.TestSelection_TabYaxunit);
        yaxunitTab.setControl(createYaxunitTab(tabFolder));

        CTabItem bddTab = new CTabItem(tabFolder, SWT.NONE);
        bddTab.setText(Messages.TestSelection_TabBdd);
        bddTab.setControl(createBddTab(tabFolder));

        tabFolder.setSelection(yaxunitTab);
    }

    private Composite createYaxunitTab(Composite parent)
    {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 4;
        layout.marginHeight = 4;
        container.setLayout(layout);

        RowLayout contextLayout = new RowLayout(SWT.HORIZONTAL);
        contextLayout.center = true;
        contextLayout.marginTop = 2;
        contextLayout.marginBottom = 2;
        Composite contextBar = new Composite(container, SWT.NONE);
        contextBar.setLayout(contextLayout);
        contextBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label projectLabel = new Label(contextBar, SWT.NONE);
        projectLabel.setText(Messages.TestSelection_ProjectLabel);
        projectCombo = new Combo(contextBar, SWT.READ_ONLY | SWT.BORDER);
        projectCombo.setToolTipText(Messages.TestSelection_ProjectTooltip);
        projectCombo.setLayoutData(new RowData(180, SWT.DEFAULT));

        Label configLabel = new Label(contextBar, SWT.NONE);
        configLabel.setText(Messages.TestSelection_ConfigLabel);
        yaxunitConfigCombo = new Combo(contextBar, SWT.READ_ONLY | SWT.BORDER);
        yaxunitConfigCombo.setToolTipText(Messages.TestSelection_ConfigTooltip);
        yaxunitConfigCombo.setLayoutData(new RowData(180, SWT.DEFAULT));

        Button loadButton = new Button(contextBar, SWT.PUSH);
        loadButton.setText(Messages.TestSelection_LoadButton);
        loadButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                loadYaxunitSuites();
            }
        });

        RowLayout filterLayout = new RowLayout(SWT.HORIZONTAL);
        filterLayout.center = true;
        Composite filterBar = new Composite(container, SWT.NONE);
        filterBar.setLayout(filterLayout);
        filterBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label filterLabel = new Label(filterBar, SWT.NONE);
        filterLabel.setText(Messages.TestSelection_FilterLabel);
        moduleFilterText = new Text(filterBar, SWT.BORDER | SWT.SEARCH);
        moduleFilterText.setMessage(Messages.TestSelection_FilterPlaceholder);
        moduleFilterText.setLayoutData(new RowData(220, SWT.DEFAULT));
        moduleFilterText.addModifyListener(e -> suiteViewer.refresh());

        // Tree of test suites (module nodes) with their subordinate test methods.
        suiteViewer = new CheckboxTreeViewer(container,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
        Tree suiteTree = suiteViewer.getTree();
        suiteTree.setHeaderVisible(true);
        suiteTree.setLinesVisible(true);
        suiteTree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        suiteViewer.setContentProvider(new ITreeContentProvider()
        {
            @Override
            public Object[] getElements(Object inputElement)
            {
                return suiteRows.toArray();
            }

            @Override
            public Object[] getChildren(Object parentElement)
            {
                if (parentElement instanceof SuiteRow row)
                {
                    List<TestRow> children = new ArrayList<>();
                    for (String name : row.tests)
                    {
                        children.add(new TestRow(row.moduleName, name));
                    }
                    return children.toArray();
                }
                return new Object[0];
            }

            @Override
            public Object getParent(Object element)
            {
                return null;
            }

            @Override
            public boolean hasChildren(Object element)
            {
                return element instanceof SuiteRow row && !row.tests.isEmpty();
            }
        });
        suiteViewer.addFilter(new ViewerFilter()
        {
            @Override
            public boolean select(Viewer viewer, Object parentElement, Object element)
            {
                String filter = moduleFilterText.getText().toLowerCase(Locale.ROOT);
                if (filter.isEmpty())
                {
                    return true;
                }
                if (element instanceof SuiteRow row)
                {
                    if (row.modulePath.toLowerCase(Locale.ROOT).contains(filter))
                    {
                        return true;
                    }
                    for (String name : row.tests)
                    {
                        if (name.toLowerCase(Locale.ROOT).contains(filter))
                        {
                            return true;
                        }
                    }
                    return false;
                }
                if (element instanceof TestRow test)
                {
                    return test.name.toLowerCase(Locale.ROOT).contains(filter);
                }
                return false;
            }
        });
        addSuiteColumn(Messages.TestSelection_ColumnPath, 360, TestSelectionView::suiteOrTestLabel);
        addSuiteColumn(Messages.TestSelection_ColumnType, 130, TestSelectionView::suiteOrTestType);
        suiteViewer.setInput(suiteRows);
        suiteViewer.expandAll();

        RowLayout actionLayout = new RowLayout(SWT.HORIZONTAL);
        actionLayout.center = true;
        actionLayout.pack = false;
        actionLayout.justify = true;
        actionLayout.marginTop = 3;
        Composite actionBar = new Composite(container, SWT.NONE);
        actionBar.setLayout(actionLayout);
        actionBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button refreshButton = new Button(actionBar, SWT.PUSH);
        refreshButton.setText(Messages.TestSelection_RefreshButton);
        refreshButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                loadYaxunitSuites();
            }
        });

        yaxunitRunButton = new Button(actionBar, SWT.PUSH);
        yaxunitRunButton.setText(Messages.TestSelection_RunYaxunitButton);
        yaxunitRunButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                runSelectedYaxunit();
            }
        });

        yaxunitCancelButton = new Button(actionBar, SWT.PUSH);
        yaxunitCancelButton.setText(Messages.TestSelection_CancelButton);
        yaxunitCancelButton.setToolTipText(Messages.TestSelection_CancelTooltip);
        yaxunitCancelButton.setEnabled(false);
        yaxunitCancelButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                cancelActiveYaxunitJob();
            }
        });

        return container;
    }

    private Composite createBddTab(Composite parent)
    {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 4;
        layout.marginHeight = 4;
        container.setLayout(layout);

        RowLayout contextLayout = new RowLayout(SWT.HORIZONTAL);
        contextLayout.center = true;
        contextLayout.marginTop = 2;
        contextLayout.marginBottom = 2;
        Composite contextBar = new Composite(container, SWT.NONE);
        contextBar.setLayout(contextLayout);
        contextBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label projectLabel = new Label(contextBar, SWT.NONE);
        projectLabel.setText(Messages.TestSelection_BddProjectLabel);
        bddProjectCombo = new Combo(contextBar, SWT.READ_ONLY | SWT.BORDER);
        bddProjectCombo.setToolTipText(Messages.TestSelection_BddProjectTooltip);
        bddProjectCombo.setLayoutData(new RowData(120, SWT.DEFAULT));

        Label configLabel = new Label(contextBar, SWT.NONE);
        configLabel.setText(Messages.TestSelection_ConfigLabel);
        bddConfigCombo = new Combo(contextBar, SWT.READ_ONLY | SWT.BORDER);
        bddConfigCombo.setToolTipText(Messages.TestSelection_ConfigTooltip);
        bddConfigCombo.setLayoutData(new RowData(180, SWT.DEFAULT));

        Button loadButton = new Button(contextBar, SWT.PUSH);
        loadButton.setText(Messages.TestSelection_LoadButton);
        loadButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                loadBddFeatures();
            }
        });

        RowLayout filterLayout = new RowLayout(SWT.HORIZONTAL);
        filterLayout.center = true;
        Composite filterBar = new Composite(container, SWT.NONE);
        filterBar.setLayout(filterLayout);
        filterBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label filterLabel = new Label(filterBar, SWT.NONE);
        filterLabel.setText(Messages.TestSelection_FilterLabel);
        featureFilterText = new Text(filterBar, SWT.BORDER | SWT.SEARCH);
        featureFilterText.setMessage(Messages.TestSelection_FilterPlaceholder);
        featureFilterText.setLayoutData(new RowData(220, SWT.DEFAULT));
        featureFilterText.addModifyListener(e -> featureViewer.refresh());

        featureViewer = CheckboxTableViewer.newCheckList(container,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
        Table featureTable = featureViewer.getTable();
        featureTable.setHeaderVisible(true);
        featureTable.setLinesVisible(true);
        featureTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        featureViewer.setContentProvider(ArrayContentProvider.getInstance());
        featureViewer.addFilter(new ViewerFilter()
        {
            @Override
            public boolean select(Viewer viewer, Object parentElement, Object element)
            {
                if (!(element instanceof FeatureRow row))
                {
                    return false;
                }
                String filter = featureFilterText.getText().toLowerCase(Locale.ROOT);
                return filter.isEmpty()
                    || row.path.toLowerCase(Locale.ROOT).contains(filter)
                    || row.featureName.toLowerCase(Locale.ROOT).contains(filter);
            }
        });
        addFeatureColumn(Messages.TestSelection_ColumnPath, 320, row -> row.path);
        addFeatureColumn(Messages.TestSelection_ColumnFeature, 220, row -> row.featureName);
        addFeatureColumn(Messages.TestSelection_ColumnTags, 120, row -> row.tags);
        featureViewer.setInput(featureRows);

        RowLayout actionLayout = new RowLayout(SWT.HORIZONTAL);
        actionLayout.center = true;
        actionLayout.pack = false;
        actionLayout.justify = true;
        actionLayout.marginTop = 3;
        Composite actionBar = new Composite(container, SWT.NONE);
        actionBar.setLayout(actionLayout);
        actionBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button refreshButton = new Button(actionBar, SWT.PUSH);
        refreshButton.setText(Messages.TestSelection_RefreshButton);
        refreshButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                loadBddFeatures();
            }
        });

        featureRunButton = new Button(actionBar, SWT.PUSH);
        featureRunButton.setText(Messages.TestSelection_RunBddButton);
        featureRunButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                runSelectedFeatures();
            }
        });

        return container;
    }

    // ----------------------------------------------------------- report pane

    private void createReportPane(Composite parent)
    {
        Composite composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 4;
        layout.marginHeight = 4;
        composite.setLayout(layout);

        statusLabel = new Label(composite, SWT.WRAP);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        reportText = new Text(composite,
            SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        reportText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }

    // ------------------------------------------------------------- loaders

    private void fillProjectCombo()
    {
        try
        {
            IProject[] projects = ProjectContext.allProjects();
            List<String> names = new ArrayList<>();
            for (IProject p : projects)
            {
                if (p != null && p.getName() != null)
                {
                    names.add(p.getName());
                }
            }
            projectCombo.setItems(names.toArray(new String[0]));
            if (!names.isEmpty())
            {
                projectCombo.select(0);
            }
            // Re-scope the launch-configuration dropdown when the EDT project changes.
            projectCombo.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    loadYaxunitConfigs();
                }
            });
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to enumerate projects for the test view", e); //$NON-NLS-1$
        }
    }

    private void fillBddProjects()
    {
        List<String> projects = TestSelectionSupport.listVanessaProjects();
        if (bddProjectCombo.isDisposed())
        {
            return;
        }
        bddProjectCombo.setItems(projects.toArray(new String[0]));
        if (!projects.isEmpty())
        {
            bddProjectCombo.select(0);
        }
    }

    private void loadYaxunitConfigs()
    {
        // No projectName filter: launch configs often carry a mismatched or empty
        // ATTR_PROJECT_NAME, which list_configurations would drop — the dropdown came
        // back empty and nothing could be selected. run_yaxunit_tests resolves the
        // launch entirely from launchConfigurationName, so list every runtime-client
        // config (same as the BDD tab) and let the user pick.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "client"); //$NON-NLS-1$ //$NON-NLS-2$
        new Thread(() ->
        {
            try
            {
                String resp = callTool(TOOL_LIST_CONFIGURATIONS, params);
                List<String> names = TestSelectionSupport.parseConfigNames(resp);
                runOnUi(() -> fillCombo(yaxunitConfigCombo, names));
            }
            catch (RuntimeException ex)
            {
                Activator.logError("YAxUnit configuration load failed", ex); //$NON-NLS-1$
            }
        }).start();
    }

    private void loadBddConfigs()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "client"); //$NON-NLS-1$ //$NON-NLS-2$
        new Thread(() ->
        {
            try
            {
                String resp = callTool(TOOL_LIST_CONFIGURATIONS, params);
                List<String> names = TestSelectionSupport.parseConfigNames(resp);
                runOnUi(() -> fillCombo(bddConfigCombo, names));
            }
            catch (RuntimeException ex)
            {
                Activator.logError("Vanessa BDD configuration load failed", ex); //$NON-NLS-1$
            }
        }).start();
    }

    private static void fillCombo(Combo combo, List<String> names)
    {
        if (combo == null || combo.isDisposed())
        {
            return;
        }
        String previous = combo.getText();
        combo.setItems(names.toArray(new String[0]));
        if (!previous.isEmpty() && names.contains(previous))
        {
            combo.setText(previous);
        }
        else if (!names.isEmpty())
        {
            combo.select(0);
        }
    }

    private void loadYaxunitSuites()
    {
        String projectName = projectCombo.getText();
        if (projectName.isEmpty())
        {
            setStatus(Messages.TestSelection_SelectProjectFirst);
            return;
        }
        setBusyYaxunit(true);
        setStatus(NLS1(Messages.TestSelection_StatusLoadingModules, projectName));
        new Thread(() ->
        {
            try
            {
                Map<String, String> params = new LinkedHashMap<>();
                params.put(McpKeys.PROJECT_NAME, projectName);
                params.put(McpKeys.LIMIT, "1000"); //$NON-NLS-1$
                String resp = callTool(TOOL_LIST_YAXUNIT_TESTS, params);
                String toolError = TestSelectionSupport.extractToolError(resp);
                if (toolError != null)
                {
                    final String msg = toolError;
                    runOnUi(() -> setStatus(NLS1(Messages.TestSelection_StatusLoadError, msg)));
                    return;
                }
                List<SuiteRow> rows = TestSelectionSupport.parseYaxunitSuites(resp);
                int testCount = 0;
                for (SuiteRow r : rows)
                {
                    testCount += r.tests.size();
                }
                final int tests = testCount;
                runOnUi(() ->
                {
                    suiteRows.clear();
                    suiteRows.addAll(rows);
                    suiteViewer.refresh();
                    suiteViewer.expandAll();
                    setStatus(NLS2(Messages.TestSelection_StatusSuitesLoaded,
                        Integer.valueOf(rows.size()), Integer.valueOf(tests)));
                });
            }
            catch (RuntimeException ex)
            {
                Activator.logError("YAxUnit suite load failed", ex); //$NON-NLS-1$
                runOnUi(() -> setStatus(ex.getMessage()));
            }
            finally
            {
                runOnUi(() -> setBusyYaxunit(false));
            }
        }).start();
    }

    private void loadBddFeatures()
    {
        String project = bddProjectCombo.getText().trim();
        if (project.isEmpty())
        {
            setStatus(Messages.TestSelection_EnterBddProject);
            return;
        }
        setBusyBdd(true);
        setStatus(NLS1(Messages.TestSelection_StatusLoadingFeatures, project));
        new Thread(() ->
        {
            try
            {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("project", project); //$NON-NLS-1$
                String resp = callTool(TOOL_LIST_FEATURES, params);
                List<FeatureRow> rows = TestSelectionSupport.parseFeatures(resp);
                runOnUi(() ->
                {
                    featureRows.clear();
                    featureRows.addAll(rows);
                    featureViewer.refresh();
                    setStatus(NLS1(Messages.TestSelection_StatusFeaturesLoaded,
                        Integer.valueOf(rows.size())));
                });
            }
            catch (RuntimeException ex)
            {
                Activator.logError("Vanessa feature load failed", ex); //$NON-NLS-1$
                runOnUi(() -> setStatus(ex.getMessage()));
            }
            finally
            {
                runOnUi(() -> setBusyBdd(false));
            }
        }).start();
    }

    // -------------------------------------------------------------- runners

    private void runSelectedYaxunit()
    {
        List<SuiteRow> checkedSuites = new ArrayList<>();
        List<TestRow> checkedTests = new ArrayList<>();
        for (Object o : suiteViewer.getCheckedElements())
        {
            if (o instanceof SuiteRow row)
            {
                checkedSuites.add(row);
            }
            else if (o instanceof TestRow test)
            {
                checkedTests.add(test);
            }
        }
        if (checkedSuites.isEmpty() && checkedTests.isEmpty())
        {
            setStatus(Messages.TestSelection_NoModulesSelected);
            return;
        }
        String config = yaxunitConfigCombo.getText().trim();
        if (config.isEmpty())
        {
            setStatus(Messages.TestSelection_EnterConfigFirst);
            return;
        }

        Map<String, String> base = new LinkedHashMap<>();
        base.put("launchConfigurationName", config); //$NON-NLS-1$
        Map<String, String> params =
            TestSelectionSupport.buildYaxunitRunParams(base, checkedSuites, checkedTests);

        int granularity = checkedTests.isEmpty() ? checkedSuites.size() : checkedTests.size();
        setBusyYaxunit(true);
        setStatus(NLS1(Messages.TestSelection_StatusRunningYaxunit, Integer.valueOf(granularity)));
        new Thread(() ->
        {
            try
            {
                String result = callTool(TOOL_RUN_YAXUNIT, params);
                String jobId = TestSelectionSupport.extractJobId(result);
                if (jobId == null)
                {
                    runOnUi(() -> showReport(result, Messages.TestSelection_Done));
                    return;
                }
                runOnUi(() ->
                {
                    activeYaxunitJobId = jobId;
                    setYaxunitCancelEnabled(true);
                    setStatus(Messages.TestSelection_StatusWaitingJob);
                });
                pollYaxunitJob(jobId);
            }
            catch (RuntimeException ex)
            {
                Activator.logError("YAXUnit run failed", ex); //$NON-NLS-1$
                runOnUi(() -> setStatus(ex.getMessage()));
            }
            finally
            {
                runOnUi(() -> setBusyYaxunit(false));
            }
        }).start();
    }

    private void pollYaxunitJob(String jobId)
    {
        while (!Thread.currentThread().isInterrupted())
        {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("jobId", jobId); //$NON-NLS-1$
            params.put("waitSeconds", "10"); //$NON-NLS-1$
            String render = callTool(TOOL_GET_JOB_STATUS, params);
            String state = TestSelectionSupport.extractJobStatus(render);
            runOnUi(() ->
            {
                if (reportText != null && !reportText.isDisposed())
                {
                    reportText.setText(render.replace("\n", Text.DELIMITER)); //$NON-NLS-1$
                }
            });
            if ("done".equals(state) || "failed".equals(state) || "cancelled".equals(state)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                runOnUi(() ->
                {
                    activeYaxunitJobId = null;
                    setYaxunitCancelEnabled(false);
                    setStatus(NLS1(Messages.TestSelection_JobEnded, state));
                });
                return;
            }
            sleep(POLL_INTERVAL_MS);
        }
    }

    /**
     * Cancels the YAxUnit background job currently being polled via the MCP
     * {@code cancel_job} tool (confirm=true — a human pressed a button, so no preview).
     * The button is disabled immediately and the tracked job id dropped so a second click
     * cannot double-cancel; the still-running poll loop observes the job become
     * {@code cancelled} and clears the rest.
     */
    private void cancelActiveYaxunitJob()
    {
        final String jobId = activeYaxunitJobId;
        if (jobId == null || jobId.isEmpty())
        {
            setStatus(Messages.TestSelection_NoActiveJob);
            return;
        }
        activeYaxunitJobId = null;
        setYaxunitCancelEnabled(false);
        setStatus(Messages.TestSelection_CancellingJob);
        new Thread(() ->
        {
            try
            {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("jobId", jobId); //$NON-NLS-1$
                params.put("confirm", "true"); //$NON-NLS-1$ //$NON-NLS-2$
                String resp = callTool(TOOL_CANCEL_JOB, params);
                runOnUi(() -> showReport(resp, Messages.TestSelection_Done));
            }
            catch (RuntimeException ex)
            {
                Activator.logError("YAXUnit cancellation failed", ex); //$NON-NLS-1$
                runOnUi(() -> setStatus(ex.getMessage()));
            }
        }).start();
    }

    private void setYaxunitCancelEnabled(boolean enabled)
    {
        if (yaxunitCancelButton != null && !yaxunitCancelButton.isDisposed())
        {
            yaxunitCancelButton.setEnabled(enabled);
        }
    }

    private void runSelectedFeatures()
    {
        List<FeatureRow> checked = checkedFeatures();
        if (checked.isEmpty())
        {
            setStatus(Messages.TestSelection_NoFeaturesSelected);
            return;
        }
        String project = bddProjectCombo.getText().trim();
        String config = bddConfigCombo.getText().trim();
        if (project.isEmpty() || config.isEmpty())
        {
            setStatus(Messages.TestSelection_EnterBddProjectArgs);
            return;
        }

        Map<String, String> base = new LinkedHashMap<>();
        base.put("project", project); //$NON-NLS-1$
        base.put("launchConfigurationName", config); //$NON-NLS-1$

        setBusyBdd(true);
        setStatus(NLS1(Messages.TestSelection_StatusRunningBdd, Integer.valueOf(checked.size())));
        new Thread(() ->
        {
            try
            {
                Map<String, String> params =
                    TestSelectionSupport.buildBddRunParams(base, checked.get(0).path);
                String runResp = callTool(TOOL_RUN_FEATURE, params);
                String launchId = TestSelectionSupport.extractLaunchId(runResp);
                if (launchId == null)
                {
                    runOnUi(() -> showReport(runResp, Messages.TestSelection_BddLaunchFailed));
                    return;
                }
                runOnUi(() -> setStatus(Messages.TestSelection_StatusWaitingBdd));
                pollBddLaunch(launchId);
            }
            catch (RuntimeException ex)
            {
                Activator.logError("Vanessa BDD run failed", ex); //$NON-NLS-1$
                runOnUi(() -> setStatus(ex.getMessage()));
            }
            finally
            {
                runOnUi(() -> setBusyBdd(false));
            }
        }).start();
    }

    private void pollBddLaunch(String launchId)
    {
        while (!Thread.currentThread().isInterrupted())
        {
            Map<String, String> statusParams = new LinkedHashMap<>();
            statusParams.put("launchId", launchId); //$NON-NLS-1$
            String statusJson = callTool(TOOL_EXEC_STATUS, statusParams);
            String state = TestSelectionSupport.extractExecutionStatus(statusJson);
            if ("passed".equals(state) || "failed".equals(state)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Map<String, String> reportParams = new LinkedHashMap<>();
                reportParams.put("launchId", launchId); //$NON-NLS-1$
                String reportJson = callTool(TOOL_TEST_REPORT, reportParams);
                String report = TestSelectionSupport.extractReport(reportJson);
                String body = report != null ? report : reportJson;
                runOnUi(() -> showReport(body, NLS1(Messages.TestSelection_BddEnded, state)));
                return;
            }
            runOnUi(() -> setStatus(NLS1(Messages.TestSelection_StatusBddPolling, state)));
            sleep(POLL_INTERVAL_MS);
        }
    }

    // ------------------------------------------------------------- plumbing

    private List<FeatureRow> checkedFeatures()
    {
        List<FeatureRow> result = new ArrayList<>();
        for (Object o : featureViewer.getCheckedElements())
        {
            if (o instanceof FeatureRow row)
            {
                result.add(row);
            }
        }
        return result;
    }

    private String callTool(String name, Map<String, String> params)
    {
        IMcpTool tool = McpToolRegistry.getInstance().getTool(name);
        if (tool == null)
        {
            return "Error: tool '" + name + "' is not registered."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return tool.execute(params);
    }

    private void showReport(String markdown, String status)
    {
        if (reportText != null && !reportText.isDisposed())
        {
            reportText.setText(markdown.replace("\n", Text.DELIMITER)); //$NON-NLS-1$
        }
        setStatus(status);
    }

    private void setStatus(String text)
    {
        if (statusLabel != null && !statusLabel.isDisposed())
        {
            statusLabel.setText(text == null ? "" : text); //$NON-NLS-1$
            statusLabel.getParent().layout();
        }
    }

    private void setBusyYaxunit(boolean busy)
    {
        if (yaxunitRunButton != null && !yaxunitRunButton.isDisposed())
        {
            yaxunitRunButton.setEnabled(!busy);
        }
    }

    private void setBusyBdd(boolean busy)
    {
        if (featureRunButton != null && !featureRunButton.isDisposed())
        {
            featureRunButton.setEnabled(!busy);
        }
    }

    private void runOnUi(Runnable runnable)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return;
        }
        display.asyncExec(() ->
        {
            if (!reportText.isDisposed())
            {
                runnable.run();
            }
        });
    }

    private static void sleep(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static String NLS1(String message, Object arg)
    {
        return org.eclipse.osgi.util.NLS.bind(message, new Object[] {arg});
    }

    private static String NLS2(String message, Object arg1, Object arg2)
    {
        return org.eclipse.osgi.util.NLS.bind(message, new Object[] {arg1, arg2});
    }

    private void addSuiteColumn(String header, int width,
        java.util.function.Function<Object, String> textFn)
    {
        TreeViewerColumn column = new TreeViewerColumn(suiteViewer, SWT.NONE);
        column.getColumn().setText(header);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return textFn.apply(element);
            }
        });
    }

    // ------------------------------------------------------- yaxunit row text

    private static String suiteOrTestLabel(Object element)
    {
        if (element instanceof TestSelectionSupport.SuiteRow)
        {
            return ((TestSelectionSupport.SuiteRow) element).modulePath;
        }
        if (element instanceof TestSelectionSupport.TestRow)
        {
            return ((TestSelectionSupport.TestRow) element).name;
        }
        return ""; //$NON-NLS-1$
    }

    private static String suiteOrTestType(Object element)
    {
        if (element instanceof TestSelectionSupport.SuiteRow)
        {
            TestSelectionSupport.SuiteRow row = (TestSelectionSupport.SuiteRow) element;
            return row.parentName.isEmpty() ? row.moduleType //$NON-NLS-1$
                : row.moduleType + " (" + row.parentName + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ""; //$NON-NLS-1$
    }

    private void addFeatureColumn(String header, int width,
        java.util.function.Function<FeatureRow, String> textFn)
    {
        TableViewerColumn column = new TableViewerColumn(featureViewer, SWT.NONE);
        column.getColumn().setText(header);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof FeatureRow row ? textFn.apply(row) : ""; //$NON-NLS-1$
            }
        });
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void setFocus()
    {
        if (projectCombo != null && !projectCombo.isDisposed())
        {
            projectCombo.setFocus();
        }
    }
}
