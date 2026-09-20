import React, { useState } from 'react';
import { TabType } from '../types';
import { Terminal, Cpu, Bug, Network, Activity, Bot, ExternalLink, Github, Tag, CheckCircle2, AlertTriangle, Loader2, X } from 'lucide-react';

interface HeaderProps {
  currentTab: TabType;
  setTab: (tab: TabType) => void;
}

export const Header: React.FC<HeaderProps> = ({ currentTab, setTab }) => {
  const [isReleasing, setIsReleasing] = useState(false);
  const [releaseResult, setReleaseResult] = useState<{
    success?: boolean;
    releaseUrl?: string;
    message?: string;
    tagName?: string;
  } | null>(null);

  const tabs = [
    { id: 'quickstart' as TabType, label: 'Быстрый старт', icon: Terminal },
    { id: 'eclipse' as TabType, label: 'Eclipse & Target Platform', icon: Cpu },
    { id: 'debug' as TabType, label: 'Отладка (.launch)', icon: Bug },
    { id: 'proxy' as TabType, label: 'MCP Прокси', icon: Network },
    { id: 'specter' as TabType, label: 'Specter (Поллинг)', icon: Activity },
    { id: 'ai' as TabType, label: 'AI Ассистент', icon: Bot },
  ];

  const handleCreateRelease = async () => {
    setIsReleasing(true);
    setReleaseResult(null);
    try {
      const res = await fetch('/api/github/release', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          repo: 'GruzdevD/EDT-MCP',
          tag: 'v1.0.0',
          title: 'Release v1.0.0: EDT-MCP Dev Studio & Eclipse Debugger'
        })
      });
      const data = await res.json();
      if (res.ok && data.success) {
        setReleaseResult({
          success: true,
          releaseUrl: data.releaseUrl,
          tagName: data.tagName || 'v1.0.0',
          message: data.pushMessage || 'Релиз успешно создан на GitHub!'
        });
      } else {
        setReleaseResult({
          success: false,
          message: data.error || 'Не удалось выпустить релиз'
        });
      }
    } catch (err: any) {
      setReleaseResult({
        success: false,
        message: err.message || 'Ошибка сети при обращении к серверу'
      });
    } finally {
      setIsReleasing(false);
    }
  };

  return (
    <header className="bg-slate-900 text-slate-100 border-b border-slate-800 sticky top-0 z-50 shadow-md">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-16">
          <div className="flex items-center space-x-3">
            <div className="bg-indigo-600 p-2 rounded-lg text-white font-bold flex items-center justify-center shadow-inner">
              <Terminal className="w-6 h-6" />
            </div>
            <div>
              <h1 className="text-lg font-semibold tracking-tight">EDT-MCP Dev Studio</h1>
              <p className="text-xs text-slate-400">Среда разработки плагина 1C:EDT & Настройка отладки Eclipse</p>
            </div>
          </div>

          <div className="flex items-center space-x-2 sm:space-x-3">
            <button
              id="header-release-btn"
              onClick={handleCreateRelease}
              disabled={isReleasing}
              className="inline-flex items-center space-x-1.5 text-xs font-semibold bg-emerald-600 hover:bg-emerald-500 disabled:bg-slate-700 text-white px-3 py-1.5 rounded-md shadow-sm transition-all"
              title="Запушить изменения и опубликовать релиз v1.0.0 с использованием GH_TOKEN"
            >
              {isReleasing ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Tag className="w-3.5 h-3.5" />
              )}
              <span>{isReleasing ? 'Пуш и релиз...' : 'Выпустить релиз v1.0.0'}</span>
            </button>

            <a
              id="header-github-repo-link"
              href="https://github.com/GruzdevD/EDT-MCP"
              target="_blank"
              rel="noopener noreferrer"
              className="hidden sm:inline-flex items-center space-x-1.5 text-xs font-medium bg-slate-800 hover:bg-slate-700 text-slate-200 px-3 py-1.5 rounded-md border border-slate-700 transition-colors"
            >
              <Github className="w-4 h-4" />
              <span>Репозиторий EDT-MCP</span>
              <ExternalLink className="w-3 h-3 text-slate-400" />
            </a>
          </div>
        </div>

        {/* Release notification banner */}
        {releaseResult && (
          <div
            id="release-result-banner"
            className={`my-2 p-3 rounded-lg border text-xs flex items-start justify-between ${
              releaseResult.success
                ? 'bg-emerald-950/80 border-emerald-700 text-emerald-200'
                : 'bg-amber-950/80 border-amber-700 text-amber-200'
            }`}
          >
            <div className="flex items-start space-x-2">
              {releaseResult.success ? (
                <CheckCircle2 className="w-4 h-4 text-emerald-400 mt-0.5 shrink-0" />
              ) : (
                <AlertTriangle className="w-4 h-4 text-amber-400 mt-0.5 shrink-0" />
              )}
              <div>
                <p className="font-medium">{releaseResult.message}</p>
                {releaseResult.releaseUrl && (
                  <a
                    href={releaseResult.releaseUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center space-x-1 text-emerald-300 underline font-semibold mt-1 hover:text-emerald-100"
                  >
                    <span>Открыть релиз {releaseResult.tagName} на GitHub</span>
                    <ExternalLink className="w-3 h-3" />
                  </a>
                )}
              </div>
            </div>
            <button
              onClick={() => setReleaseResult(null)}
              className="text-slate-400 hover:text-white ml-2 p-1"
              aria-label="Закрыть"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        {/* Navigation Tabs */}
        <div className="flex space-x-1 overflow-x-auto pb-2 scrollbar-none">
          {tabs.map((tab) => {
            const Icon = tab.icon;
            const isActive = currentTab === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => setTab(tab.id)}
                className={`flex items-center space-x-2 px-4 py-2 rounded-lg text-sm font-medium transition-all whitespace-nowrap ${
                  isActive
                    ? 'bg-indigo-600 text-white shadow-sm'
                    : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
                }`}
              >
                <Icon className="w-4 h-4" />
                <span>{tab.label}</span>
              </button>
            );
          })}
        </div>
      </div>
    </header>
  );
};
