import React from 'react';
import { Terminal, CheckCircle2, Cpu, Wrench, ShieldAlert, ExternalLink, Code } from 'lucide-react';

interface QuickStartTabProps {
  onNavigateTab: (tab: any) => void;
}

export const QuickStartTab: React.FC<QuickStartTabProps> = ({ onNavigateTab }) => {
  return (
    <div className="space-y-8 animate-fadeIn">
      {/* Hero Banner */}
      <div className="bg-gradient-to-r from-slate-900 via-indigo-950 to-slate-900 border border-indigo-900/40 rounded-2xl p-8 text-white shadow-xl relative overflow-hidden">
        <div className="absolute right-0 top-0 w-96 h-96 bg-indigo-500/10 rounded-full blur-3xl pointer-events-none"></div>
        <div className="max-w-3xl relative z-10">
          <div className="inline-flex items-center space-x-2 bg-indigo-500/20 border border-indigo-500/30 text-indigo-300 px-3 py-1 rounded-full text-xs font-semibold mb-4">
            <Code className="w-3.5 h-3.5" />
            <span>Model Context Protocol для 1C:EDT</span>
          </div>
          <h2 className="text-3xl font-bold tracking-tight mb-3">Разработка и отладка плагина EDT-MCP</h2>
          <p className="text-slate-300 text-sm leading-relaxed mb-6">
            <strong>EDT-MCP</strong> предоставляет MCP-сервер (Model Context Protocol) в виде плагина для 1C:EDT (на базе Eclipse RCP / OSGi), позволяя ИИ-ассистентам (Claude Desktop, Cursor, Cline) взаимодействовать с рабочей областью 1С, структурой конфигурации, сообщениями об ошибках и запросами.
          </p>
          <div className="flex flex-wrap gap-3">
            <button
              onClick={() => onNavigateTab('eclipse')}
              className="bg-indigo-600 hover:bg-indigo-500 text-white font-medium px-4 py-2.5 rounded-xl text-sm transition-all shadow-md flex items-center space-x-2"
            >
              <span>Настройка Eclipse & Target Platform</span>
            </button>
            <button
              onClick={() => onNavigateTab('debug')}
              className="bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 font-medium px-4 py-2.5 rounded-xl text-sm transition-all flex items-center space-x-2"
            >
              <BugIcon className="w-4 h-4 text-indigo-400" />
              <span>Генератор .launch файлов</span>
            </button>
          </div>
        </div>
      </div>

      {/* System Requirements */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm">
          <div className="w-10 h-10 bg-indigo-50 dark:bg-indigo-950/50 text-indigo-600 rounded-lg flex items-center justify-center mb-4">
            <Cpu className="w-5 h-5" />
          </div>
          <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100 mb-2">1. Системные требования</h3>
          <ul className="space-y-2 text-sm text-slate-600 dark:text-slate-400">
            <li className="flex items-center space-x-2">
              <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0" />
              <span><strong>Java 17</strong> (JDK 17 Eclipse Temurin / OpenJDK)</span>
            </li>
            <li className="flex items-center space-x-2">
              <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0" />
              <span><strong>1C:EDT</strong> версий 2026.1 / 2026.2</span>
            </li>
            <li className="flex items-center space-x-2">
              <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0" />
              <span><strong>Maven 3.8+</strong> (для сборки Tycho)</span>
            </li>
          </ul>
        </div>

        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm">
          <div className="w-10 h-10 bg-indigo-50 dark:bg-indigo-950/50 text-indigo-600 rounded-lg flex items-center justify-center mb-4">
            <Wrench className="w-5 h-5" />
          </div>
          <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100 mb-2">2. Клонирование репозитория</h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 mb-3">
            Загрузите исходный код репозитория GruzdevD/EDT-MCP:
          </p>
          <div className="bg-slate-900 text-slate-200 p-2.5 rounded-lg text-xs font-mono overflow-x-auto">
            git clone https://github.com/GruzdevD/EDT-MCP.git
          </div>
        </div>

        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm">
          <div className="w-10 h-10 bg-indigo-50 dark:bg-indigo-950/50 text-indigo-600 rounded-lg flex items-center justify-center mb-4">
            <ShieldAlert className="w-5 h-5" />
          </div>
          <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100 mb-2">3. Сборка Maven/Tycho</h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 mb-3">
            Соберите проект через Maven для проверки зависимостей целевой платформы:
          </p>
          <div className="bg-slate-900 text-slate-200 p-2.5 rounded-lg text-xs font-mono overflow-x-auto">
            mvn clean install
          </div>
        </div>
      </div>

      {/* Architecture & Workflow Overview */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm">
        <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100 mb-4">Архитектура EDT-MCP</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="p-4 bg-slate-50 dark:bg-slate-800/50 rounded-lg border border-slate-200 dark:border-slate-700">
            <h4 className="font-medium text-slate-900 dark:text-slate-100 mb-1">1. EDT Plugin (OSGi)</h4>
            <p className="text-xs text-slate-600 dark:text-slate-400">
              Внутренний плагин 1C:EDT, работающий в OSGi-контейнере Eclipse RCP. Получает доступ к Workspace API, моделям конфигурации, валидации запросов и ошибкам.
            </p>
          </div>
          <div className="p-4 bg-slate-50 dark:bg-slate-800/50 rounded-lg border border-slate-200 dark:border-slate-700">
            <h4 className="font-medium text-slate-900 dark:text-slate-100 mb-1">2. edt-mcp-proxy</h4>
            <p className="text-xs text-slate-600 dark:text-slate-400">
            Автономный прокси-маршрутизатор для управления несколькими запущенными экземплярами EDT и проброса MCP-протокола (SSE / HTTP).
            </p>
          </div>
          <div className="p-4 bg-slate-50 dark:bg-slate-800/50 rounded-lg border border-slate-200 dark:border-slate-700">
            <h4 className="font-medium text-slate-900 dark:text-slate-100 mb-1">3. AI Клиенты</h4>
            <p className="text-xs text-slate-600 dark:text-slate-400">
              Claude Desktop, Cursor, Cline и другие клиенты Model Context Protocol, обращающиеся к прокси и плагину за контекстом конфигурации 1С.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};
import { Bug as BugIcon } from 'lucide-react';
