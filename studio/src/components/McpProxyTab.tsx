import React, { useState } from 'react';
import { Network, Server, Copy, Check, Terminal, Shield, ArrowRight } from 'lucide-react';

export const McpProxyTab: React.FC = () => {
  const [copied, setCopied] = useState(false);

  const mcpConfigJson = `{
  "mcpServers": {
    "edt-mcp": {
      "command": "node",
      "args": [
        "C:/path/to/edt-mcp-proxy/dist/index.js"
      ],
      "env": {
        "EDT_MCP_PORT": "8080",
        "EDT_WORKSPACE_PATH": "C:/Users/Username/1C/EDTWorkspace"
      }
    }
  }
}`;

  const handleCopy = () => {
    navigator.clipboard.writeText(mcpConfigJson);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="space-y-8 animate-fadeIn">
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm">
        <div className="flex items-center space-x-3 mb-4">
          <div className="bg-indigo-50 dark:bg-indigo-950/50 text-indigo-600 p-2.5 rounded-xl">
            <Network className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-slate-900 dark:text-slate-100">Настройка edt-mcp-proxy и AI клиентов</h2>
            <p className="text-sm text-slate-500 dark:text-slate-400">Связывание плагина 1C:EDT с Claude Desktop, Cursor и Cline через Model Context Protocol</p>
          </div>
        </div>

        <div className="space-y-6 mt-6">
          {/* Architecture flow */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-center">
            <div className="p-4 bg-slate-50 dark:bg-slate-800/40 rounded-xl border border-slate-200 dark:border-slate-700">
              <div className="font-semibold text-slate-900 dark:text-slate-100 text-sm mb-1">1. 1C:EDT + Плагин</div>
              <p className="text-xs text-slate-500">Запуск EDT с активированным плагином EDT-MCP (порт HTTP/SSE)</p>
            </div>
            <div className="p-4 bg-slate-50 dark:bg-slate-800/40 rounded-xl border border-slate-200 dark:border-slate-700">
              <div className="font-semibold text-slate-900 dark:text-slate-100 text-sm mb-1">2. edt-mcp-proxy</div>
              <p className="text-xs text-slate-500">Маршрутизатор запросов между клиентом и инстансом EDT</p>
            </div>
            <div className="p-4 bg-slate-50 dark:bg-slate-800/40 rounded-xl border border-slate-200 dark:border-slate-700">
              <div className="font-semibold text-slate-900 dark:text-slate-100 text-sm mb-1">3. Claude / Cursor</div>
              <p className="text-xs text-slate-500">AI-ассистент с прямым доступом к контексту конфигурации</p>
            </div>
          </div>

          {/* Configuration snippet */}
          <div className="space-y-3">
            <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100">Конфигурация для Claude Desktop / Cursor (`mcp.json`)</h3>
            <p className="text-sm text-slate-600 dark:text-slate-400">
              Добавьте следующий блок в файл конфигурации MCP вашего клиента (например, <code>claude_desktop_config.json</code>):
            </p>
            <div className="relative">
              <button
                onClick={handleCopy}
                className="absolute top-3 right-3 bg-slate-800 hover:bg-slate-700 text-slate-300 px-2.5 py-1.5 rounded text-xs flex items-center space-x-1 border border-slate-700 transition-colors"
              >
                {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                <span>{copied ? 'Скопировано' : 'Копировать JSON'}</span>
              </button>
              <pre className="bg-slate-950 text-slate-200 p-4 rounded-xl font-mono text-xs overflow-x-auto border border-slate-800">
                {mcpConfigJson}
              </pre>
            </div>
          </div>

          {/* Quick steps */}
          <div className="border-t border-slate-200 dark:border-slate-800 pt-6 space-y-3">
            <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100">Запуск и проверка</h3>
            <ul className="space-y-2 text-sm text-slate-600 dark:text-slate-400">
              <li className="flex items-start space-x-2">
                <span className="bg-indigo-600 text-white w-5 h-5 rounded-full flex items-center justify-center text-xs shrink-0 mt-0.5">1</span>
                <span>Убедитесь, что репозиторий <code>edt-mcp-proxy</code> склонирован и собран (<code>npm install && npm run build</code>).</span>
              </li>
              <li className="flex items-start space-x-2">
                <span className="bg-indigo-600 text-white w-5 h-5 rounded-full flex items-center justify-center text-xs shrink-0 mt-0.5">2</span>
                <span>Запустите 1C:EDT с отладочным плагином или через Eclipse Runtime Workbench.</span>
              </li>
              <li className="flex items-start space-x-2">
                <span className="bg-indigo-600 text-white w-5 h-5 rounded-full flex items-center justify-center text-xs shrink-0 mt-0.5">3</span>
                <span>Перезапустите Claude Desktop или Cursor — инструменты MCP (информация о проекте, ошибки, валидация запросов) станут доступны ИИ.</span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  );
};
