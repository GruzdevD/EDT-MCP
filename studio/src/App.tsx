import React, { useState } from 'react';
import { TabType } from './types';
import { Header } from './components/Header';
import { QuickStartTab } from './components/QuickStartTab';
import { EclipseSetupTab } from './components/EclipseSetupTab';
import { DebugConfiguratorTab } from './components/DebugConfiguratorTab';
import { McpProxyTab } from './components/McpProxyTab';
import { SpecterIntegrationTab } from './components/SpecterIntegrationTab';
import { AiAssistantTab } from './components/AiAssistantTab';

export default function App() {
  const [currentTab, setCurrentTab] = useState<TabType>('quickstart');

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100 flex flex-col font-sans selection:bg-indigo-500 selection:text-white">
      <Header currentTab={currentTab} setTab={setCurrentTab} />

      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {currentTab === 'quickstart' && <QuickStartTab onNavigateTab={setCurrentTab} />}
        {currentTab === 'eclipse' && <EclipseSetupTab />}
        {currentTab === 'debug' && <DebugConfiguratorTab />}
        {currentTab === 'proxy' && <McpProxyTab />}
        {currentTab === 'specter' && <SpecterIntegrationTab />}
        {currentTab === 'ai' && <AiAssistantTab />}
      </main>

      <footer className="border-t border-slate-200 dark:border-slate-800 py-6 mt-12 bg-white dark:bg-slate-900 text-center text-xs text-slate-500">
        <p>EDT-MCP Dev Studio & Eclipse Debugger — Интерактивная среда для разработчиков 1C:EDT</p>
      </footer>
    </div>
  );
}
