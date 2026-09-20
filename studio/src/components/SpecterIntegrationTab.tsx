import React, { useState, useEffect } from 'react';
import { Play, Activity, FileText, List, CheckCircle2, XCircle, Loader2, RefreshCw, Copy, Check } from 'lucide-react';

export const SpecterIntegrationTab: React.FC = () => {
  const [activeSubTab, setActiveSubTab] = useState<'simulator' | 'specs'>('simulator');
  const [isRunning, setIsRunning] = useState(false);
  const [runId, setRunId] = useState<string | null>(null);
  const [pollStatus, setPollStatus] = useState<any>(null);
  const [copiedSchema, setCopiedSchema] = useState<string | null>(null);

  // Simulation state
  const [simStep, setSimStep] = useState(0);

  const simulationSteps = [
    { status: 'running', progress: { total: 12, completed: 3, passed: 3, failed: 0 }, duration: 3.2, step: 'Инициализация контекста 1С и воркеров Specter' },
    { status: 'running', progress: { total: 12, completed: 7, passed: 6, failed: 1 }, duration: 8.5, step: 'Выполнение теста ТестПроведениеРеализации (модуль СП_Тесты_Продажи)' },
    { status: 'running', progress: { total: 12, completed: 11, passed: 10, failed: 1 }, duration: 16.0, step: 'Запуск интеграционных проверок по кэшу Smart Retry' },
    { status: 'failed', progress: { total: 12, completed: 12, passed: 11, failed: 1 }, duration: 22.4, step: 'Прогон завершен с ошибками в 1 тесте' }
  ];

  const handleStartRun = () => {
    const newRunId = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';
    setRunId(newRunId);
    setIsRunning(true);
    setSimStep(0);
    setPollStatus(simulationSteps[0]);
  };

  useEffect(() => {
    let timer: any;
    if (isRunning && simStep < simulationSteps.length - 1) {
      timer = setTimeout(() => {
        const nextStep = simStep + 1;
        setSimStep(nextStep);
        setPollStatus(simulationSteps[nextStep]);
        if (nextStep === simulationSteps.length - 1) {
          setIsRunning(false);
        }
      }, 3000);
    }
    return () => clearTimeout(timer);
  }, [isRunning, simStep]);

  const handleCopy = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopiedSchema(key);
    setTimeout(() => setCopiedSchema(null), 2000);
  };

  const toolsList = [
    {
      name: '1. specter_run_tests',
      desc: 'Асинхронный запуск тестов в фоновом режиме через specter-cli.py.',
      schema: `{
  "type": "object",
  "properties": {
    "module": { "type": "string", "description": "Имя общего модуля тестов" },
    "test": { "type": "string", "description": "Конкретный тестовый метод" },
    "workers": { "type": "integer", "default": 1 },
    "retryFailed": { "type": "boolean", "default": false }
  }
}`,
      response: `{
  "runId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "started",
  "message": "Тестовый прогон успешно запущен в фоновом режиме"
}`
    },
    {
      name: '2. specter_poll_status ⭐ (Поллинг)',
      desc: 'Проверка статуса и прогресса выполнения тестового прогона по runId без блокирования.',
      schema: `{
  "type": "object",
  "required": ["runId"],
  "properties": {
    "runId": { "type": "string", "description": "Уникальный идентификатор сессии" }
  }
}`,
      response: `{
  "runId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "running", 
  "progress": { "total": 12, "completed": 8, "passed": 7, "failed": 1 },
  "durationSeconds": 14.5,
  "currentStep": "Проверка создания документа РеализацияТоваров"
}`
    },
    {
      name: '3. specter_get_results',
      desc: 'Получение подробного отчета по завершенному прогону с трассировкой и ошибками.',
      schema: `{
  "type": "object",
  "required": ["runId"],
  "properties": {
    "runId": { "type": "string", "description": "Идентификатор сессии прогона" }
  }
}`,
      response: `{
  "runId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "failed",
  "summary": { "tests": 12, "failures": 1, "errors": 0, "time": 22.4 },
  "failedTests": [{ "module": "СП_Тесты_Продажи", "test": "ТестПроведениеРеализации", "error": "ASSERT_FAILED..." }]
}`
    },
    {
      name: '4. specter_list_tests',
      desc: 'Сканирование воркспейса EDT и получение списка доступных тестов.',
      schema: `{
  "type": "object",
  "properties": {
    "module": { "type": "string", "description": "Фильтр по модулю" }
  }
}`,
      response: `{
  "modules": [
    { "name": "СП_Тесты_Контрагенты", "tests": ["ТестСозданиеКонтрагента"] }
  ]
}`
    }
  ];

  return (
    <div className="space-y-8 animate-fadeIn">
      {/* Header card */}
      <div className="bg-gradient-to-r from-indigo-900 via-slate-900 to-indigo-950 border border-indigo-900/40 rounded-2xl p-6 text-white shadow-lg">
        <div className="max-w-3xl">
          <div className="inline-flex items-center space-x-2 bg-indigo-500/20 border border-indigo-500/30 text-indigo-300 px-3 py-1 rounded-full text-xs font-semibold mb-3">
            <Activity className="w-3.5 h-3.5" />
            <span>Интеграция Specter E2E Test Framework</span>
          </div>
          <h2 className="text-2xl font-bold tracking-tight mb-2">EDT-MCP + Specter Polling Specification</h2>
          <p className="text-slate-300 text-sm leading-relaxed">
            Добавлена важнейшая возможность поллинга (проверки статуса в реальном времени) запущенных E2E-тестов. LLM и агенты в Cursor / Claude могут асинхронно опрашивать прогресс через <code>specter_poll_status</code> без блокирования соединения.
          </p>
        </div>

        <div className="flex space-x-3 mt-6">
          <button
            onClick={() => setActiveSubTab('simulator')}
            className={`px-4 py-2 rounded-xl text-xs font-medium transition-all flex items-center space-x-2 ${
              activeSubTab === 'simulator' ? 'bg-indigo-600 text-white shadow' : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
            }`}
          >
            <Play className="w-4 h-4" />
            <span>Интерактивный симулятор поллинга</span>
          </button>
          <button
            onClick={() => setActiveSubTab('specs')}
            className={`px-4 py-2 rounded-xl text-xs font-medium transition-all flex items-center space-x-2 ${
              activeSubTab === 'specs' ? 'bg-indigo-600 text-white shadow' : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
            }`}
          >
            <FileText className="w-4 h-4" />
            <span>Спецификация 4 MCP инструментов</span>
          </button>
        </div>
      </div>

      {activeSubTab === 'simulator' ? (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Controls & Runner */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm space-y-6 lg:col-span-1">
            <h3 className="font-semibold text-slate-900 dark:text-slate-100 flex items-center space-x-2">
              <Play className="w-4 h-4 text-indigo-600" />
              <span>Запуск теста (specter_run_tests)</span>
            </h3>
            <p className="text-xs text-slate-500">
              Нажмите кнопку ниже для имитации асинхронного вызова <code>specter_run_tests</code> с воркерами.
            </p>
            <div className="space-y-3">
              <div className="bg-slate-50 dark:bg-slate-800/50 p-3 rounded-lg border border-slate-200 dark:border-slate-700 text-xs space-y-1">
                <div><strong>module:</strong> СП_Тесты_Продажи</div>
                <div><strong>workers:</strong> 4</div>
                <div><strong>retryFailed:</strong> true</div>
              </div>
              <button
                onClick={handleStartRun}
                disabled={isRunning}
                className="w-full bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white font-medium py-2.5 px-4 rounded-xl text-sm transition-colors flex items-center justify-center space-x-2 shadow-sm"
              >
                {isRunning ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
                <span>{isRunning ? 'Тесты выполняются...' : 'Запустить прогон (Run)'}</span>
              </button>
            </div>

            {runId && (
              <div className="pt-4 border-t border-slate-200 dark:border-slate-800 text-xs space-y-1.5">
                <span className="text-slate-400">Активный Session ID:</span>
                <div className="font-mono bg-slate-900 text-indigo-300 p-2 rounded truncate">{runId}</div>
              </div>
            )}
          </div>

          {/* Real-time Polling Status Monitor */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm space-y-6 lg:col-span-2">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold text-slate-900 dark:text-slate-100 flex items-center space-x-2">
                <Activity className="w-4 h-4 text-emerald-500" />
                <span>Поллинг статуса (specter_poll_status)</span>
              </h3>
              {isRunning && (
                <div className="flex items-center space-x-2 text-xs text-indigo-500 bg-indigo-50 dark:bg-indigo-950/50 px-2.5 py-1 rounded-full">
                  <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                  <span>Опрос каждые 3 сек...</span>
                </div>
              )}
            </div>

            {pollStatus ? (
              <div className="space-y-6">
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
                  <div className="bg-slate-50 dark:bg-slate-800/40 p-4 rounded-xl border border-slate-200 dark:border-slate-700">
                    <div className="text-xs text-slate-500 mb-1">Статус прогона</div>
                    <div className={`font-bold text-sm uppercase ${pollStatus.status === 'running' ? 'text-amber-500 animate-pulse' : pollStatus.status === 'failed' ? 'text-red-500' : 'text-emerald-500'}`}>
                      {pollStatus.status}
                    </div>
                  </div>
                  <div className="bg-slate-50 dark:bg-slate-800/40 p-4 rounded-xl border border-slate-200 dark:border-slate-700">
                    <div className="text-xs text-slate-500 mb-1">Прогресс</div>
                    <div className="font-bold text-slate-900 dark:text-slate-100 text-sm">
                      {pollStatus.progress.completed} / {pollStatus.progress.total}
                    </div>
                  </div>
                  <div className="bg-slate-50 dark:bg-slate-800/40 p-4 rounded-xl border border-slate-200 dark:border-slate-700">
                    <div className="text-xs text-slate-500 mb-1">Успешно / Ошибок</div>
                    <div className="font-bold text-sm">
                      <span className="text-emerald-600">{pollStatus.progress.passed}</span> / <span className="text-red-500">{pollStatus.progress.failed}</span>
                    </div>
                  </div>
                  <div className="bg-slate-50 dark:bg-slate-800/40 p-4 rounded-xl border border-slate-200 dark:border-slate-700">
                    <div className="text-xs text-slate-500 mb-1">Время (сек)</div>
                    <div className="font-bold text-slate-900 dark:text-slate-100 text-sm">{pollStatus.duration}с</div>
                  </div>
                </div>

                {/* Current step banner */}
                <div className="bg-slate-900 text-slate-200 p-4 rounded-xl font-mono text-xs space-y-2 border border-slate-800">
                  <div className="text-slate-400 text-[10px] uppercase tracking-wider">Текущий шаг выполнения:</div>
                  <div className="text-indigo-300 font-semibold">{pollStatus.currentStep}</div>
                </div>

                {/* JSON response representation */}
                <div className="space-y-2">
                  <div className="text-xs text-slate-500 font-medium">JSON ответ от specter_poll_status:</div>
                  <pre className="bg-slate-950 text-slate-200 p-3 rounded-xl font-mono text-xs overflow-x-auto border border-slate-800">
                    {JSON.stringify({
                      runId,
                      status: pollStatus.status,
                      progress: pollStatus.progress,
                      durationSeconds: pollStatus.duration,
                      currentStep: pollStatus.currentStep
                    }, null, 2)}
                  </pre>
                </div>
              </div>
            ) : (
              <div className="text-center py-16 text-slate-400 text-sm border border-dashed border-slate-200 dark:border-slate-800 rounded-xl">
                Нажмите «Запустить прогон», чтобы увидеть работу поллинга в реальном времени.
              </div>
            )}
          </div>
        </div>
      ) : (
        <div className="space-y-6">
          {toolsList.map((tool, idx) => (
            <div key={idx} className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="font-bold text-slate-900 dark:text-slate-100 text-base">{tool.name}</h3>
                <button
                  onClick={() => handleCopy(tool.schema + '\n\n' + tool.response, `tool-${idx}`)}
                  className="bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-300 px-3 py-1.5 rounded-md text-xs font-medium flex items-center space-x-1.5 transition-colors"
                >
                  {copiedSchema === `tool-${idx}` ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
                  <span>{copiedSchema === `tool-${idx}` ? 'Скопировано' : 'Копировать спецификацию'}</span>
                </button>
              </div>
              <p className="text-sm text-slate-600 dark:text-slate-400">{tool.desc}</p>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <div className="text-xs font-semibold text-slate-500 mb-1">Input Schema:</div>
                  <pre className="bg-slate-950 text-slate-200 p-3 rounded-lg font-mono text-xs overflow-x-auto border border-slate-800">{tool.schema}</pre>
                </div>
                <div>
                  <div className="text-xs font-semibold text-slate-500 mb-1">Response:</div>
                  <pre className="bg-slate-950 text-slate-200 p-3 rounded-lg font-mono text-xs overflow-x-auto border border-slate-800">{tool.response}</pre>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
