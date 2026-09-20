import { DebugConfigTemplate } from '../types';

export const DEBUG_CONFIG_TEMPLATES: DebugConfigTemplate[] = [
  {
    id: 'eclipse-app',
    name: 'Eclipse Application (Запуск EDT с плагином)',
    type: 'org.eclipse.pde.ui.RuntimeWorkbench',
    description: 'Запускает новый экземпляр 1C:EDT (на базе Eclipse RCP) с установленным плагином в режиме отладки.',
    filename: 'EDT-MCP-Plugin-Runtime.launch',
    template: `<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<launchConfiguration type="org.eclipse.pde.ui.RuntimeWorkbench">
    <stringAttribute key="additional-args" value="-clearPersistedState"/>
    <booleanAttribute key="append.args" value="true"/>
    <booleanAttribute key="automaticAdd" value="true"/>
    <booleanAttribute key="automaticValidate" value="true"/>
    <stringAttribute key="bootstrap" value=""/>
    <stringAttribute key="checked" value="[NONE]"/>
    <booleanAttribute key="clearConfig" value="true"/>
    <booleanAttribute key="clearws" value="true"/>
    <booleanAttribute key="clearwslog" value="false"/>
    <stringAttribute key="configLocation" value="\${workspace_loc}/.metadata/.plugins/org.eclipse.pde.core/EDT-MCP-Runtime"/>
    <booleanAttribute key="default_auto_start" value="true"/>
    <intAttribute key="default_start_level" value="4"/>
    <setAttribute key="enabled_target_plugins">
        <setEntry value="com.google.gson*2.10.1.v20230304-1222@default:default"/>
        <setEntry value="org.eclipse.core.contenttype@default:default"/>
        <setEntry value="org.eclipse.core.jobs@default:default"/>
        <setEntry value="org.eclipse.core.runtime@default:true"/>
        <setEntry value="org.eclipse.equinox.app@default:default"/>
        <setEntry value="org.eclipse.equinox.common@default:true"/>
        <setEntry value="org.eclipse.equinox.preferences@default:default"/>
        <setEntry value="org.eclipse.equinox.registry@default:default"/>
        <setEntry value="org.eclipse.osgi@-1:true"/>
    </setAttribute>
    <stringAttribute key="location" value="\${workspace_loc}/../runtime-EDT-MCP"/>
    <booleanAttribute key="org.eclipse.jdt.launching.ATTR_ATTR_USE_ARGFILE" value="false"/>
    <booleanAttribute key="org.eclipse.jdt.launching.ATTR_SHOW_CODEDETAILS_IN_EXCEPTION_STACK_TRACE" value="true"/>
    <booleanAttribute key="org.eclipse.jdt.launching.ATTR_USE_START_ON_FIRST_THREAD" value="true"/>
    <stringAttribute key="org.eclipse.jdt.launching.JRE_CONTAINER" value="org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.launching.internal.standard.type.StandardVMType/JavaSE-17"/>
    <stringAttribute key="org.eclipse.jdt.launching.PROGRAM_ARGUMENTS" value="-os \${target.os} -ws \${target.ws} -arch \${target.arch} -nl \${target.nl} -consoleLog"/>
    <stringAttribute key="org.eclipse.jdt.launching.SOURCE_PATH_PROVIDER" value="org.eclipse.pde.ui.workbenchClasspathProvider"/>
    <stringAttribute key="org.eclipse.jdt.launching.VM_ARGUMENTS" value="-Xms512m -Xmx2048m -XX:+UseG1GC"/>
    <stringAttribute key="pde.version" value="3.3"/>
    <stringAttribute key="product" value="com.e1c.g5.v8.dt.product.product"/>
    <booleanAttribute key="show_selected_only" value="false"/>
    <stringAttribute key="stringAttribute" value=""/>
    <booleanAttribute key="useCustomFeatures" value="false"/>
    <booleanAttribute key="useDefaultConfig" value="true"/>
    <booleanAttribute key="useDefaultConfigArea" value="true"/>
    <booleanAttribute key="useProduct" value="true"/>
</launchConfiguration>`
  },
  {
    id: 'remote-java',
    name: 'Remote Java Application (Отладка работающего EDT)',
    type: 'org.eclipse.jdt.launching.remoteJavaApplication',
    description: 'Подключается по сокету (обычно порт 8000 или 5005) к уже запущенному процессу 1C:EDT для отладки плагина на лету.',
    filename: 'EDT-MCP-Remote-Debug.launch',
    template: `<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<launchConfiguration type="org.eclipse.jdt.launching.remoteJavaApplication">
    <booleanAttribute key="org.eclipse.jdt.launching.ALLOW_TERMINATE" value="false"/>
    <mapAttribute key="org.eclipse.jdt.launching.CONNECT_MAP">
        <mapEntry key="port" value="8000"/>
        <mapEntry key="hostname" value="localhost"/>
    </mapAttribute>
    <stringAttribute key="org.eclipse.jdt.launching.PROJECT_ATTR" value="ru.gruzdev.edt.mcp"/>
    <stringAttribute key="org.eclipse.jdt.launching.VM_CONNECTOR" value="org.eclipse.jdt.launching.socketAttachVMConnector"/>
</launchConfiguration>`
  },
  {
    id: 'junit-plugin',
    name: 'JUnit Plug-in Test (Модульные тесты EDT)',
    type: 'org.eclipse.pde.ui.JunitLaunchConfig',
    description: 'Запуск юнит- и интеграционных тестов плагина в контексте OSGi среды 1C:EDT.',
    filename: 'EDT-MCP-JUnit-Tests.launch',
    template: `<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<launchConfiguration type="org.eclipse.pde.ui.JunitLaunchConfig">
    <booleanAttribute key="append.args" value="true"/>
    <stringAttribute key="application" value="org.eclipse.pde.junit.runtime.coretestapplication"/>
    <booleanAttribute key="automaticAdd" value="true"/>
    <booleanAttribute key="automaticValidate" value="false"/>
    <stringAttribute key="bootstrap" value=""/>
    <stringAttribute key="checked" value="[NONE]"/>
    <booleanAttribute key="clearConfig" value="true"/>
    <booleanAttribute key="clearws" value="true"/>
    <booleanAttribute key="clearwslog" value="false"/>
    <stringAttribute key="configLocation" value="\${workspace_loc}/.metadata/.plugins/org.eclipse.pde.core/pde-junit"/>
    <booleanAttribute key="default_auto_start" value="true"/>
    <intAttribute key="default_start_level" value="4"/>
    <stringAttribute key="location" value="\${workspace_loc}/../junit-workspace"/>
    <booleanAttribute key="org.eclipse.debug.core.ATTR_FORCE_SYSTEM_CONSOLE_ENCODING" value="false"/>
    <booleanAttribute key="org.eclipse.jdt.launching.ATTR_ATTR_USE_ARGFILE" value="false"/>
    <booleanAttribute key="org.eclipse.jdt.launching.ATTR_SHOW_CODEDETAILS_IN_EXCEPTION_STACK_TRACE" value="true"/>
    <stringAttribute key="org.eclipse.jdt.launching.JRE_CONTAINER" value="org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.launching.internal.standard.type.StandardVMType/JavaSE-17"/>
    <stringAttribute key="org.eclipse.jdt.launching.MAIN_TYPE" value=""/>
    <stringAttribute key="org.eclipse.jdt.launching.PROGRAM_ARGUMENTS" value="-os \${target.os} -ws \${target.ws} -arch \${target.arch} -nl \${target.nl}"/>
    <stringAttribute key="org.eclipse.jdt.launching.PROJECT_ATTR" value="ru.gruzdev.edt.mcp.tests"/>
    <stringAttribute key="org.eclipse.jdt.launching.SOURCE_PATH_PROVIDER" value="org.eclipse.pde.ui.workbenchClasspathProvider"/>
    <stringAttribute key="org.eclipse.jdt.launching.VM_ARGUMENTS" value="-ea -Xmx1024m"/>
    <stringAttribute key="pde.version" value="3.3"/>
    <stringAttribute key="useCustomFeatures" value="false"/>
    <booleanAttribute key="useDefaultConfig" value="true"/>
    <booleanAttribute key="useDefaultConfigArea" value="true"/>
    <booleanAttribute key="useProduct" value="false"/>
</launchConfiguration>`
  }
];
