import React, { useState } from 'react';
import { Cpu, CheckCircle2, Copy, Check, FileText, Settings, Layers } from 'lucide-react';

export const EclipseSetupTab: React.FC = () => {
  const [copied, setCopied] = useState(false);

  const targetPlatformSnippet = `<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<?pde version="3.8"?>
<target name="EDT-2026.2-Target" sequenceNumber="1">
    <locations>
        <location includeAllPlatforms="false" includeConfigurePhase="true" includeMode="planner" includeSource="true" type="InstallableUnit">
            <unit id="com.e1c.g5.v8.dt.product" version="0.0.0"/>
            <repository location="file:///C:/Program Files/1C/1CE/components/1c-enterprise-edt/plugins/..."/>
        </location>
    </locations>
</target>`;

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="space-y-8 animate-fadeIn">
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm">
        <div className="flex items-center space-x-3 mb-4">
          <div className="bg-indigo-50 dark:bg-indigo-950/50 text-indigo-600 p-2.5 rounded-xl">
            <Cpu className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-slate-900 dark:text-slate-100">Настройка Eclipse IDE & Target Platform</h2>
            <p className="text-sm text-slate-500 dark:text-slate-400">Пошаговый процесс подготовки среды для разработки плагина EDT-MCP</p>
          </div>
        </div>

        <div className="space-y-6 mt-6">
          {/* Step 1 */}
          <div className="border-l-2 border-indigo-600 pl-4 space-y-2">
            <h3 className="font-semibold text-slate-900 dark:text-slate-100 flex items-center space-x-2">
              <span className="w-6 h-6 rounded-full bg-indigo-600 text-white text-xs flex items-center justify-center font-bold">1</span>
              <span>Установка Eclipse IDE for RCP and RAP Developers</span>
            </h3>
            <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              Для разработки плагинов под 1C:EDT (которые базируются на платформе Eclipse 2024-2026 годов) необходима специальная версия Eclipse IDE, включающая PDE (Plug-in Development Environment), инструментарий OSGi и поддержку Tycho. Рекомендуется использовать Eclipse 2024-12 или новее с установленной Java 17.
            </p>
          </div>

          {/* Step 2 */}
          <div className="border-l-2 border-indigo-600 pl-4 space-y-2">
            <h3 className="font-semibold text-slate-900 dark:text-slate-100 flex items-center space-x-2">
              <span className="w-6 h-6 rounded-full bg-indigo-600 text-white text-xs flex items-center justify-center font-bold">2</span>
              <span>Импорт проектов EDT-MCP в Eclipse Workspace</span>
            </h3>
            <ul className="text-sm text-slate-600 dark:text-slate-400 space-y-1 list-disc list-inside">
              <li>Откройте Eclipse и выберите рабочую директорию (Workspace).</li>
              <li>Перейдите в <strong>File → Import → Maven → Existing Maven Projects</strong>.</li>
              <li>Укажите корневую папку клонированного репозитория <code>EDT-MCP</code>.</li>
              <li>Дождитесь завершения загрузки зависимостей и разрешения артефактов Tycho.</li>
            </ul>
          </div>

          {/* Step 3 */}
          <div className="border-l-2 border-indigo-600 pl-4 space-y-3">
            <h3 className="font-semibold text-slate-900 dark:text-slate-100 flex items-center space-x-2">
              <span className="w-6 h-6 rounded-full bg-indigo-600 text-white text-xs flex items-center justify-center font-bold">3</span>
              <span>Настройка целевой платформы (Target Platform)</span>
            </h3>
            <p className="text-sm text-slate-600 dark:text-slate-400">
              Плагин зависит от внутренних библиотек и платформенных модулей 1C:EDT (компоненты 2026.1 / 2026.2). Вам необходимо указать Eclipse путь к установленной 1C:EDT в качестве Target Platform:
            </p>
            <div className="bg-slate-900 text-slate-200 rounded-lg p-4 relative font-mono text-xs overflow-x-auto">
              <button
                onClick={() => handleCopy(targetPlatformSnippet)}
                className="absolute top-3 right-3 bg-slate-800 hover:bg-slate-700 text-slate-300 px-2.5 py-1.5 rounded text-xs flex items-center space-x-1 border border-slate-700 transition-colors"
              >
                {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                <span>{copied ? 'Скопировано' : 'Копировать'}</span>
              </button>
              <pre>{targetPlatformSnippet}</pre>
            </div>
            <p className="text-xs text-slate-500">
              Откройте файл <code>*.target</code> в корне проекта или создайте новый через PDE Wizard, указав локальную папку установки EDT (например, через 1C:Enterprise Installer или распакованные компоненты).
            </p>
          </div>

          {/* Step 4 */}
          <div className="border-l-2 border-indigo-600 pl-4 space-y-2">
            <h3 className="font-semibold text-slate-900 dark:text-slate-100 flex items-center space-x-2">
              <span className="w-6 h-6 rounded-full bg-indigo-600 text-white text-xs flex items-center justify-center font-bold">4</span>
              <span>Проверка компиляции и кодировки (UTF-8)</span>
            </h3>
            <p className="text-sm text-slate-600 dark:text-slate-400">
              Убедитесь, что для проектов установлена кодировка файлов <strong>UTF-8</strong> (Project Properties → Resource → Text file encoding). Выполните пробную сборку через Maven в терминале: <code>mvn clean verify</code>.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};
