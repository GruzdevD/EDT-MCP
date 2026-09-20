import React, { useState } from 'react';
import { DEBUG_CONFIG_TEMPLATES } from '../data/debugTemplates';
import { Bug, Copy, Check, Download, FileCode, Sliders } from 'lucide-react';

export const DebugConfiguratorTab: React.FC = () => {
  const [selectedConfigId, setSelectedConfigId] = useState(DEBUG_CONFIG_TEMPLATES[0].id);
  const [copied, setCopied] = useState(false);

  const currentConfig = DEBUG_CONFIG_TEMPLATES.find((c) => c.id === selectedConfigId) || DEBUG_CONFIG_TEMPLATES[0];

  const handleCopy = () => {
    navigator.clipboard.writeText(currentConfig.template);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownload = () => {
    const element = document.createElement('a');
    const file = new Blob([currentConfig.template], { type: 'text/xml' });
    element.href = URL.createObjectURL(file);
    element.download = currentConfig.filename;
    document.body.appendChild(element);
    element.click();
    document.body.removeChild(element);
  };

  return (
    <div className="space-y-8 animate-fadeIn">
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm">
        <div className="flex items-center space-x-3 mb-4">
          <div className="bg-indigo-50 dark:bg-indigo-950/50 text-indigo-600 p-2.5 rounded-xl">
            <Bug className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-slate-900 dark:text-slate-100">Генератор конфигураций отладки Eclipse (.launch)</h2>
            <p className="text-sm text-slate-500 dark:text-slate-400">Создавайте и скачивайте готовые файлы конфигурации для отладки плагина EDT-MCP</p>
          </div>
        </div>

        {/* Config selector pills */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 my-6">
          {DEBUG_CONFIG_TEMPLATES.map((config) => {
            const isSelected = config.id === selectedConfigId;
            return (
              <button
                key={config.id}
                onClick={() => setSelectedConfigId(config.id)}
                className={`text-left p-4 rounded-xl border transition-all ${
                  isSelected
                    ? 'border-indigo-600 bg-indigo-50/50 dark:bg-indigo-950/30 ring-2 ring-indigo-500/20'
                    : 'border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-800/50 hover:border-slate-300'
                }`}
              >
                <div className="font-semibold text-sm text-slate-900 dark:text-slate-100 mb-1">{config.name}</div>
                <div className="text-xs text-slate-500 dark:text-slate-400 line-clamp-2">{config.description}</div>
              </button>
            );
          })}
        </div>

        {/* Selected Config Details */}
        <div className="space-y-4">
          <div className="flex items-center justify-between bg-slate-100 dark:bg-slate-800/70 p-3 rounded-lg border border-slate-200 dark:border-slate-700">
            <div className="flex items-center space-x-2 text-sm font-medium text-slate-800 dark:text-slate-200">
              <FileCode className="w-4 h-4 text-indigo-500" />
              <span>Имя файла: <code>{currentConfig.filename}</code></span>
            </div>
            <div className="flex items-center space-x-2">
              <button
                onClick={handleCopy}
                className="bg-white dark:bg-slate-900 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200 px-3 py-1.5 rounded-md text-xs font-medium border border-slate-300 dark:border-slate-700 flex items-center space-x-1.5 transition-colors shadow-sm"
              >
                {copied ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
                <span>{copied ? 'Скопировано' : 'Копировать XML'}</span>
              </button>
              <button
                onClick={handleDownload}
                className="bg-indigo-600 hover:bg-indigo-500 text-white px-3 py-1.5 rounded-md text-xs font-medium flex items-center space-x-1.5 transition-colors shadow-sm"
              >
                <Download className="w-3.5 h-3.5" />
                <span>Скачать .launch</span>
              </button>
            </div>
          </div>

          {/* XML Code preview */}
          <div className="relative">
            <div className="absolute top-3 right-3 text-xs text-slate-400 font-mono bg-slate-800/80 px-2 py-1 rounded">
              XML Launch Config
            </div>
            <pre className="bg-slate-950 text-slate-200 p-4 rounded-xl font-mono text-xs overflow-x-auto max-h-96 border border-slate-800">
              {currentConfig.template}
            </pre>
          </div>

          {/* Instructions for Eclipse */}
          <div className="bg-indigo-50/50 dark:bg-indigo-950/20 border border-indigo-200 dark:border-indigo-900/50 rounded-xl p-4 text-sm text-slate-700 dark:text-slate-300 space-y-2">
            <h4 className="font-semibold text-indigo-900 dark:text-indigo-300">Как использовать в Eclipse:</h4>
            <ol className="list-decimal list-inside space-y-1 text-xs text-slate-600 dark:text-slate-400">
              <li>Скачайте сгенерированный файл или скопируйте его содержимое.</li>
              <li>Поместите файл в корень вашего workspace Eclipse в папку <code>.metadata/.plugins/org.eclipse.debug.core/.launches/</code> или сохраните в проект.</li>
              <li>В Eclipse откройте меню <strong>Run → Debug Configurations...</strong>, нажмите правой кнопкой мыши по типу конфигурации и выберите <strong>Import...</strong> или файл появится автоматически.</li>
              <li>Запустите отладку для тестирования плагина в изолированной сессии 1C:EDT.</li>
            </ol>
          </div>
        </div>
      </div>
    </div>
  );
};
