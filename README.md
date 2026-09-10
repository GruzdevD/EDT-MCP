# EDT MCP Server — форк `edt-mcp-vanessa`

MCP (Model Context Protocol) сервер-плагин для **1C:EDT**. Позволяет ИИ‑ассистентам (Claude, GitHub Copilot, Cursor и др.) напрямую работать с рабочей областью EDT: читать и менять метаданные и BSL‑код, запускать валидацию, управлять ИБ, запускать тесты и многое другое.

Этот репозиторий — **форк** от [DitriXNew/EDT-MCP](https://github.com/DitriXNew/EDT-MCP), расширенный под внутренние (корпоративные) сценарии: **BDD‑тестирование Vanessa Automation** и **git‑based самообновление плагина**.

> [!TIP]
> **Новым участникам разработки плагина.** Сначала прочитайте [CLAUDE.md](CLAUDE.md) — это «карта минёра»: жёсткие запреты и зоны «остановись и подумай» (транзакции BM, двуязычная ru/en модель, каскадные переименования и т.д.). Подробные «как правильно» — в скиллах `.claude/skills/`.

> [!IMPORTANT]
> **Совместимость с версиями EDT.** Поддерживаются **1C:EDT 2026.1 и 2026.2** из одной сборки. Плагин компилируется под таргет **2026.1** (самая старая из поддерживаемых — Eclipse 4.30 / Java 17), поэтому один артефакт резолвится на обеих версиях. Внутри корпоративного контура проверено на **2026.1** (проект afm, тонкий клиент).

---

## Что нового в `edt-mcp-vanessa` (отличия от апстрима)

### 1. BDD-инструменты Vanessa Automation

Набор MCP‑инструментов, который превращает плагин из «наблюдателя за EDT» ещё и в **запускатор и анализатор BDD/VA‑прогонов**, с **провижинингом VA «под ключ»**. Полный живой цикл: установил/провижинил (`vanessa_setup`, готовность `vanessa_doctor`) → запустил фичу (`vanessa_run_feature`) → отследил статус → получил структурированный отчёт (JUnit XML).

| Инструмент | Что делает |
|---|---|
| `vanessa_run_feature` | Запускает `.feature`‑файл Vanessa Automation через launch‑конфигурацию EDT (тонкий/толстый клиент, параметры `StartFeaturePlayer`). Принимает `project` (обязательно) и `feature` (абсолютный путь, по умолчанию — каталог фич из `env.sh` проекта). |
| `vanessa_get_execution_status` | Статус прогона по `launchId`: `running` / `passed` / `failed`. |
| `vanessa_get_test_report` | Структурированный отчёт (`verdict`, сюиты, тест‑кейсы, статусы, время) — по `launchId` или по готовому JUnit XML. |
| `vanessa_list_launches` | Список известных прогонов VA. |
| `vanessa_doctor` *(новое)* | Read‑only отчёт готовности проекта к VA: наличие `.vanessa/`, `env.sh`, `VAParams.json`, рантайма `.epf`, Allure. Ничего не качает. |
| `vanessa_setup` *(новое)* | Явный провижининг «под ключ»: создаёт `.vanessa/` и лениво докачивает `.epf`-рантайм (опц. Allure) как фоновое задание `get_job_status`; интерфейс для харнесса. |

**Как устроено:**
- Параметры проекта читаются из `<EDT-проект>/.vanessa/env.sh` (провижинится плагином), с фолбэком на `~/.1c-tools/vanessa/projects/<project>/env.sh` (ИБ, пользователь/пароль БД, порт MCP, launch‑конфиг EDT, каталог фич).
- **Провижининг «под ключ».** При активации плагин создаёт `.vanessa/` (env.sh, VAParams.json, `features/`, `out/`) всем проектам EDT; тяжёлый рантайм `vanessa-automation.epf` качается лениво при первом VA‑прогоне в общий кэш `~/.1c-tools/vanessa/va/<ver>/`. Готовность проекта показывает `vanessa_doctor`, явная установка — `vanessa_setup` (доступно харнессу).
- Запуск идёт через общий `LaunchTool` с `startupOption = StartFeaturePlayer;VAParams=<override>`.
- Терминальное состояние прогона определяется по **артефактам на диске** (status/логи/JUnit в `out/<project>/`), а не только по выходу процесса — так статус корректен для длинных UI‑прогонов.

> [!NOTE]
> **Известные тонкости afm.** У объекта `Справочник.Валюты` нет авторской формы списка → открывается стандартная форма, где **нет** кнопки‑элемента `ФормаСоздать` (в отличие от `Контрагенты`, у которого авторская форма). Для таких объектов надёжнее программные шаги VA (`я программно создаю элемент справочника …`), они исполняются в той же сессии клиента (под тем же пользователем БД). `vanessa_get_execution_status` может вернуть статус раньше физической перезаписи JUnit — достоверный сигнал завершения — свежий `junit.xml` на диске.

### 2. Git-based самообновление плагина

Два инструмента для обновления самого плагина без ручного копирования jar в `~/.p2/pool/plugins`:

| Инструмент | Что делает |
|---|---|
| `plugin_check_for_update` | Сверяет доступную сборку (ветка `update-site` личного репозитория) с установленной версией запущенного бандла. Отдаёт `availableVersion / installedVersion / updateAvailable / jar / source / branch`. |
| `plugin_update` | Копирует свежий jar в пул `~/.p2/pool/plugins` (снося старые) и **сам переписывает строку** в `bundles.info`. Возвращает `restartRequired: true`. |

**Почему git, а не p2‑HTTP:** на корпоративном GitLab приватный репозиторий аутентифицируется только через git smart‑HTTP (как push), анонимный HTTP не проходит, поэтому нативный «Check for Updates» не достаёт приватный репозиторий. Вместо этого готовый jar коммитится в ветку `update-site` репозитория плагина, а инструменты подтягивают её с git‑креденшелами проекта.

- Репозиторий по умолчанию: `https://gitlab.ozon.ru/dmigruzdev/ozon-edt-mcp.git`, ветка `update-site`.
- Для аутентификации используется git‑конфиг `~/.1c-tools/gitlab-config` (token‑helper), `GIT_TERMINAL_PROMPT=0`.
- `runtimeBundlesInfo()` ищет `bundles.info` по `osgi.configuration.area` → `osgi.install.area` → `Platform`, по односегментному пути `…/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` (НЕ разбивая имя каталога по точкам).

### 3. README на русском

Главный README переведён на русский и дополнен описанием форка (этот документ).

---

## Возможности

- 🔧 **Протокол MCP 2025‑11‑25** — транспорты Streamable HTTP и SSE
- 📊 **Информация о проектах** — список проектов и свойства конфигурации
- 🔴 **Отчёты об ошибках** — ошибки, предупреждения, сводки с фильтрами
- 📝 **Описания проверок** — документация проверок из markdown‑файлов
- 🔄 **Перевалидация проекта** — когда валидация «залипла»
- 🔖 **Закладки и задачи** — доступ к маркерам TODO/FIXME
- 💡 **Content assist** — типы, подсказки методов и документация платформы в любой позиции кода
- 🧪 **Проверка запросов** — синтаксис и семантика 1С‑запросов в контексте проекта (опц. режим СКД)
- 🧩 **Анализ BSL‑кода** — структура модулей, чтение/запись методов, поиск по коду, иерархия вызовов
- 🖼️ **Инспекция форм** — PNG‑скриншоты и YAML‑разметка из WYSIWYG‑редактора форм
- 🚀 **Управление приложениями** — ИБ, обновление базы, запуск в отладке, завершение клиентов
- 🎯 **Панель состояния** — статус сервера, имя инструмента, время выполнения, интерактивные кнопки
- ⚡ **Прерываемые операции** — отмена долгих операций и сигналы ИИ‑агенту
- 🏷️ **Метаданные‑теги** — группировка объектов, фильтр Навигатора, горячие клавиши (Ctrl+Alt+1‑0)
- 📁 **Метаданные‑группы** — произвольные папки в Навигаторе по коллекциям метаданных
- ✏️ **Рефакторинг метаданных** — создание/переименование/удаление объектов и элементов форм с каскадом по BSL, формам и метаданным
- 🛠️ **Управление инструментами** — включение/отключение по группам, пресеты, значения параметров по умолчанию
- 🧪 **Vanessa Automation / BDD** *(форк)* — запуск и анализ VA‑прогонов (см. выше)
- 🔃 **Самообновление плагина** *(форк)* — git‑based check/update (см. выше)

---

## Установка

### Из update-site (апстрим, публичный)

1. В EDT: **Help → Install New Software…**
2. Добавить адрес сайта: `https://ditrixnew.github.io/EDT-MCP/`
3. Выбрать **EDT MCP Server Feature**
4. Перезапустить EDT

### Из командной строки (Windows, «разовая» быстрая установка)

Закройте EDT и выполните (пример версии 2025.2.3):

```bash
set VER_EDT=2025.2.3+30
"\path\to\EDT\components\1c-edt-%VER_EDT%-x86_64\1cedt.exe" -nosplash ^
    -application org.eclipse.equinox.p2.director ^
    -repository https://ditrixnew.github.io/EDT-MCP/ ^
    -installIU com.ozon.edt.mcp.server.feature.feature.group ^
    -profileProperties org.eclipse.update.reconcile=true
```

### Корпоративная установка из своих сборок (Ozon)

Ручной деплой сборки в установку EDT:

1. Положить jar `com.ozon.edt.mcp.server_<версия>.jar` в `~/.p2/pool/plugins/`
2. Прописать в `bundles.info` строку вида
   `com.ozon.edt.mcp.server,<версия>,<абс. путь к jar>,4,false`
   (файл: `<установка EDT>/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`)
3. Перезапустить EDT.

Дальнейшие обновления удобнее делать самим плагином через `plugin_check_for_update` / `plugin_update` (см. раздел «Git-based самообновление»).

> [!TIP]
> **Плашка «доступно обновление из версии проекта, с которого форкнулись».**
> Если EDT предлагает обновление от **апстрима DitriX** (а не нашу сборку) — в EDT остался зарегистрированным апстримный update-site
> (`https://github.com/DitriXNew/EDT-MCP`, или в legacy‑реестре `platform.xml` — старая фича-версия вроде `2.7.1`).
> p2 честно видит там более новый номер и предлагает его. Чтобы «видеть нашу» версию:
> **Help → Install New Software… → Available Software Sites…** → удалить/отключить сайт `DitriXNew/EDT-MCP` (и при необходимости снять устаревшую запись из `platform.xml`).
> Тогда обновления будут приходить только из вашего механизма (git‑based self-update).

---

## Требуемый JVM‑флаг для скриншотов форм

Инструментам `get_form_screenshot` и `get_form_layout_snapshot` нужен запуск EDT с флагом:

```
-DnativeFormBufferedLayoutRender=true
```

**Без него** оба инструмента возвращают пусто (серый PNG / пустой список `elements`).

**Почему:** `NativeRenderService` читает `nativeFormBufferedLayoutRender` один раз при загрузке класса. Если флаг не задан на старте JVM, синглтон `HippoLayoutService` создаётся без оффскрин‑буфера, C++‑рендерер не возвращает пиксели в Java, и скриншот падает в SWT `Control.print()` (на Windows даёт серый прямоугольник). Выставить флаг рефлексией в рантайме нельзя — синглтон уже построен.

**Как добавить (постоянно):**
1. Закрыть EDT.
2. Открыть `1cedt.ini` (рядом с `1cedt.exe`).
3. После строки `-vmargs` добавить `-DnativeFormBufferedLayoutRender=true`.
4. Запустить EDT.

**Как добавить (разово, без изменения установки):**

```cmd
"<path-to-EDT>\1cedt.exe" -data "<workspace>" -vmargs -DnativeFormBufferedLayoutRender=true
```

Флаг полезен и для обычной работы в EDT — включает буферизованный нативный рендер.

---

## Настройка

**Window → Preferences → MCP Server**. Страница настроек имеет две вкладки.

> [!NOTE]
> **Язык интерфейса.** Страница настроек и диалоги тегов двуязычны (русский/английский) и следуют языку EDT (аргумент запуска `-nl` или локаль ОС: `-nl ru` — русский, `-nl en` — английский). Сама MCP‑поверхность инструментов (имена, описания, ошибки) остаётся на английском — это контракт для ИИ.

### Вкладка General

- **Server Port** — HTTP‑порт (по умолчанию 8765)
- **Check descriptions folder** — папка с markdown‑документацией проверок
- **Auto-start** — запускать сервер при старте EDT
- **Plain text mode (Cursor compatibility)** — возвращать результаты текстом вместо встроенных ресурсов (для ИИ без поддержки MCP‑ресурсов)
- **Enhance Navigator** — вклад плагина в дерево Навигатора (группы и их фильтр)
- **Show tags in Navigator** — показывать теги декорациями в Навигаторе
- **Tag decoration style** — как показывать теги (все суффиксом / только первый / счётчик)
- **Server control** — запуск/остановка/перезапуск MCP‑сервера из настроек

### Вкладка Tools

Управление тем, какие инструменты доступны ИИ (по группам). См. раздел «Управление инструментами».

---

## Панель состояния

Панель состояния MCP показывает статус в реальном времени:
- 🟢 **Зелёный** — сервер запущен, простаивает
- 🟡 **Жёлтый (мигающий)** — выполняется инструмент
- ⚪ **Серый** — сервер остановлен

Во время выполнения инструмента можно прервать вызов MCP и отправить агенту сигнал: **Cancel Operation**, **Retry**, **Continue in Background**, **Ask Expert**, **Send Custom Message…**. Вызов MCP прерывается, а сама операция EDT продолжается в фоне.

---

## Управление инструментами

Инструменты сгруппированы по 11 семантическим группам:

| Группа | Описание | Основные инструменты |
|---|---|---|
| **Core / Project** | Сервер, проекты, конфигурация, история, экспорт/импорт XML | `get_edt_version`, `get_server_status`, `list_projects`, `get_configuration_properties`, `clean_project`, `revalidate_objects`, `export/import_configuration_to_xml`, `create/delete_project`, `get_event_log`, `get_mcp_history` … |
| **Errors & Problems** | Ошибки, валидация, маркеры | `get_problem_summary`, `get_project_errors`, `get_markers`, `apply_quick_fix`, `validate_xdto_package` |
| **Code Intelligence** | Content assist, документация, метаданные, ссылки | `get_content_assist`, `get_platform_documentation`, `get_metadata_objects`, `get_metadata_details`, `list_subsystems`, `find_references`, `list_common_pictures` … |
| **Tags** | Теги метаданных | `get_tags`, `get_objects_by_tags` |
| **Applications & Testing** | ИБ, сборка внешних объектов, запуск, тесты, фоновые задания | `get_applications`, `update_database`, `launch`, `terminate_launch`, `run_yaxunit_tests`, `get_job_status`, `cancel_job`, `build_external_objects`, `set_infobase_credentials` … |
| **Debugging** | Точки останова, шаги, переменные, профилирование | `set_breakpoint`, `get_variables`, `set_variable`, `step`, `resume`, `evaluate_expression`, `debug_yaxunit_tests`, `start/stop_profiling` … |
| **BSL Code** | Чтение/запись модулей, структура, поиск, иерархия вызовов, формы | `read/write_module_source`, `get_module_structure`, `list_modules`, `search_in_code`, `go_to_definition`, `get_symbol_info`, `get_form_screenshot`, `validate_query` … |
| **Refactoring** | Создание, переименование, удаление, свойства метаданных и СКД | `rename_metadata_object`, `delete_metadata`, `create_metadata`, `modify_metadata`, `adopt_metadata_object` |
| **Translation** | Синхронизация переводов (LanguageTool) | `generate_translation_strings`, `translate_configuration`, `get_translation_project_info` |
| **Comparison** | Трёхстороннее сравнение конфигураций | `compare_configurations`, `get_comparison_node`, `merge_rules` |
| **Vanessa / BDD** *(форк)* | Запуск и анализ Vanessa Automation + провижининг | `vanessa_run_feature`, `vanessa_get_execution_status`, `vanessa_get_test_report`, `vanessa_list_launches`, `vanessa_doctor`, `vanessa_setup` |
| **Self-update** *(форк)* | Самообновление плагина | `plugin_check_for_update`, `plugin_update` |

Включение/отключение — во вкладке **Tools** (**Window → Preferences → MCP Server**). Отключённые инструменты исключаются из `tools/list`; при прямом вызове через `tools/call` сервер сообщит, что инструмент отключён.

**Пресеты:** All Tools (по умолчанию), Analysis Only (только чтение: Core, Errors, Code Intelligence, Tags), Code Review (анализ + чтение BSL, без `write_module_source`), Development (полная разработка без отладки).

**Значения параметров по умолчанию** (если клиент не передаёт явно): напр. лимиты `get_project_errors`/`get_metadata_objects` (100), `search_in_code` maxResults (100) и contextLines (2), `read_module_source` maxLines (500) и т.д.

---

## Подключение ИИ‑ассистентов

Базовый адрес сервера: `http://localhost:8765/mcp` (порт по умолчанию).

### VS Code / GitHub Copilot

`.vscode/mcp.json`:

```json
{
  "servers": {
    "EDT MCP Server": { "type": "sse", "url": "http://localhost:8765/mcp" }
  }
}
```

### Cursor IDE

> **Примечание:** Cursor не поддерживает MCP‑ресурсы — включите **Plain text mode** в настройках MCP.

```json
{
  "mcpServers": { "EDT MCP Server": { "url": "http://localhost:8765/mcp" } }
}
```

### Claude Code

В `.claude.json` (в Windows — `%USERPROFILE%\.claude.json`):

```json
"mcpServers": {
  "EDT MCP Server": { "type": "http", "url": "http://localhost:8765/mcp" }
}
```

### Claude Desktop

Принимает только stdio‑серверы (команду), поэтому HTTP‑конечную точку мостим через [`mcp-remote`](https://www.npmjs.com/package/mcp-remote):

```json
{
  "mcpServers": {
    "EDT MCP Server": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8765/mcp"]
    }
  }
}
```

> **Безопасность:** не выставляйте этот сервер в публичный интернет через туннель (ngrok/Cloudflare) — он может писать код в вашу конфигурацию и запускать отладчик.

### Cline (VSCode)

```json
{
  "mcpServers": {
    "EDTMCPServer": { "type": "streamableHttp", "url": "http://localhost:8765/mcp" }
  }
}
```

### Antigravity

```json
{
  "mcpServers": {
    "EDTMCPServer": { "serverUrl": "http://localhost:8765/mcp" }
  }
}
```

---

## Интеграция 1C:Workmate (in-process)

Когда 1C:Workmate (`com.e1c.edt.ai*` 1.0.5) запущен в той же JVM EDT, интеграция двусторонняя:
- `ask_workmate` запускает полный цикл диалога/инструментов Workmate в ограниченном фоновом задании и возвращает `jobId` (поллится через общий `get_job_status`). Инструмент **поставляется отключённым** — включает его владелец EDT в *Preferences → EDT MCP Server → Tools*.
- OSGi‑сервис `com.ozon.edt.mcp.server.bridge.IEdtMcpBridge` позволяет Workmate/JShell вызывать EDT‑MCP инструменты без импорта пакетов плагина.

---

## Сборка и тестирование

- **Сборка:** Maven/Tycho 4.0.5, JDK 17, таргет-платформа `mcp/targets/default/default.target` (EDT 2026.1). Всё внутри `mcp/`.
- Версия бандла `1.0.0.<qualifier-таймстамп>` — каждая сборка уникальна (необходимо для самообновления и p2).
- Полный набор инструментов самопроверки — в `tests/` (юнит, e2e, протокольная конформность) — по методологии четырёх уровней из `CLAUDE.md`.

> [!NOTE]
> **Корпоративная специфика сборки.** Из-за недоступности p2-HTTP к `edt.1c.ru` из песочницы полный Tycho-билд с разрешением таргета выполняется вне песочницы. Локальные build-only правки (только хост-ОС, `includeAllPlatforms=false`) не коммитятся. Kэш `~/.m2/repository/.cache/tycho` не удалять (ломает офлайн-резолюцию).

---

## Полный подъём с нуля (для коллег на opencode / macOS)

Пошаговый рецепт «как у меня» — поднять на чистой машине форк с нуля: собрать плагин, поставить в EDT, подключить MCP к opencode, проверить BDD-инструменты и Allure-отчёт. Проверено на **2026-09-06** (мак, EDT 2026.1).

### А. Кому что нужно (прочитайте первым)

Есть **два пути** — выбирайте по цели:

| Цель | Что нужно | Куда идти |
|---|---|---|
| **Пользоваться** плагином (MCP/BDD/Allure) | **только готовый jar**, EDT, opencode | **Быстрый путь** → п. 1 (jar) → сразу установка (п. 3) и далее |
| **Разрабатывать** плагин (править Java/расширения, юнит/e2e) | форк репы + JDK/Maven + Tycho | **Путь разработчика** → п. 0 предпосылки → п. 2 (форк) → п. 3 (сборка) → установка |

> [!NOTE]
> **Форк репозитория и сборка не нужны, чтобы просто установить и пользоваться.** Бандл — это один самодостаточный `com.ozon.edt.mcp.server_<ver>.jar`; остальное (попы/poms/`.vscode`/README) в установке не участвует. Ветка `update-site` репозитория несёт **только** готовые jar — по сути «склад бинарников». Возьмите jar оттуда (или из п. 1) — и вперёд.

### Быстрый путь: установка по готовому jar (без форка и сборки)

1. Скачать **install-zip** (jar + `INSTALL.md`) из ветки `update-site` репозитория:
   `https://gitlab.ozon.ru/dmigruzdev/ozon-edt-mcp/-/raw/update-site/install/edt-mcp-vanessa-install-1.0.0.202609060930.zip`
   (приватный репозиторий — нужен доступ к GitLab; внутри — только `plugins/*.jar` и `INSTALL.md`).
   Альтернативно — взять jar из `plugins/` ветки `update-site` или с любой машины, где плагин уже стоит (`~/.p2/pool/plugins/`).
2. Прогнать шаги **3 (установка в EDT)** и далее по этому руководству; разделы «Клонировать форк»/«Собрать плагин» — пропустить.

### 0. Предпосылки (путь разработчика)

| Компонент | Что нужно | Как проверить |
|---|---|---|
| JDK **17** | `brew install openjdk@17`, `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` | `java -version` → 17 |
| Maven 3.8+ | `brew install maven` | `mvn -v` |
| git + доступ к GitLab | token / ssh | `git ls-remote git@gitlab.ozon.ru:dmigruzdev/ozon-edt-mcp.git` |
| EDT **2026.1/2026.2** | установлена через 1CEStart | в `~/Library/Application Support/1C/1cedtstart/installations/` |

Для BDD-инструментов также нужен рабочий контур Vanessa Automation — **OneScript + vrunner** + сама **Vanessa Automation** + проект с `env.sh` и `.feature`-сценариями (см. [раздел 6](#6-окружение-vanessa-automation--onescript)) — и, для отчётов, [Allure commandline](#5-allure-cli-для-vanessa_open_allure_report).

### 1. Клонировать форк

```bash
mkdir -p ~/git && cd ~/git
git clone git@gitlab.ozon.ru:dmigruzdev/ozon-edt-mcp.git edt-mcp-vanessa
cd edt-mcp-vanessa
git checkout feature/vanessa-mcp-tools
```

> [!NOTE]
> **Форк vs апстрим.** Этот репозиторий — форк [DitriXNew/EDT-MCP](https://github.com/DitriXNew/EDT-MCP) с перебрендингом `com.ditrix.* → com.ozon.*`. Пулл-реквесты в апстрим не идут; история для публикации пересобирается под корпоративное правило авторов (`dmigruzdev@ozon.ru`, без `ditrixnew@gmail.com`). Если форкаетесь сами — не тяните upstream-историю целиком.

### 2. Собрать плагин (Tycho)

```bash
cd ~/git/edt-mcp-vanessa/mcp
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -Djava.io.tmpdir=$TMPDIR clean verify
```

- Таргет-платформа: `mcp/targets/default/default.target` (EDT 2026.1, Java 17). Один артефакт резолвится и на 2026.2.
- Результат: `mcp/repositories/com.ozon.edt.mcp.server.repository/target/repository/plugins/com.ozon.edt.mcp.server_1.0.0.<квалиф>.jar` (версия уникальна на каждую сборку — это ключ самóобновления).
- Юнит-тесты и часть e2e гоняются прямо в этом шаге (`BuiltInToolTestCoverageTest` — у каждого тула обязан быть `XxxToolTest`; `ToolContractConsistencyTest` — параметры lowerCamelCase).

> [!NOTE]
> **Корпоративная специфика разрешения таргета.** Из песочницы p2-HTTP до `edt.1c.ru` может не доходить → полный `verify` с резолвом таргета выполняется вне песочницы. Кэш `~/.m2/repository/.cache/tycho` не удалять.

### 3. Установить плагин в EDT (корпоративный деплой)

Штатный p2 «Install New Software» против приватного GitLab у нас **не работает** (аутентификация только через git smart-HTTP), поэтому деплой — прямой: скопировать jar в shared-pool и прописать его в `bundles.info`. Это байт-в-байт то, что делает `InstallBundleAction`.

1. **Закрыть EDT.**
2. Скопировать jar в общий p2-pool:
   ```bash
   cp mcp/repositories/com.ozon.edt.mcp.server.repository/target/repository/plugins/com.ozon.edt.mcp.server_*.jar \
      ~/.p2/pool/plugins/
   ```
3. Прописать бандл в конфигурации EDT (путь к вашей установке — `1CEStart`, версия 2026.1):
   ```bash
   BI="$HOME/Library/Application Support/1C/1cedtstart/installations/1C_EDT 2026.1/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
   # путь до jar — такой же относительный, как у соседних записей pool (от каталога configuration)
   R=$( cd "$HOME" && printf '../../../../../../../../../.p2/pool/plugins/com.ozon.edt.mcp.server_1.0.0.'*.jar )
   printf 'com.ozon.edt.mcp.server,1.0.0.%s,%s,4,false\n' "$QUAL" "$R" >> "$BI"
   ```
   Итоговая строка выглядит так:
   `com.ozon.edt.mcp.server,1.0.0.202609060930,../../../../../../../../../.p2/pool/plugins/com.ozon.edt.mcp.server_1.0.0.202609060930.jar,4,false`
4. **Перезапустить EDT** и дождаться, пока поднимется MCP-сервер (обычно ~90 c):
   ```bash
   until nc -z 127.0.0.1 8765; do sleep 5; done
   ```

> [!IMPORTANT]
> **Порт = 8765.** Новый (перебрендённый) бандл читает `com.ozon.edt.mcp.server.prefs` (дефолт 8765), а не legacy `8766`. Конфиг MCP-клиентов на 8766 надо перенацелить.

Дальнейшие обновления не требуют ручного копирования — есть тулы `plugin_check_for_update` / `plugin_update`.

### 4. Подключить MCP к opencode

opencode читает глобальный конфиг `~/.config/opencode/opencode.json`. Добавьте MCP-сервер на loopback-порт EDT:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "edt": {
      "type": "http",
      "url": "http://127.0.0.1:8765/mcp",
      "enabled": true
    }
  }
}
```

Проверка, что сервер живой и инструменты на месте:

```bash
curl -s http://127.0.0.1:8765/mcp            # должен ответить 2xx на /mcp
# tools/list должен включать vanessa_run_feature, va*инструменты, plugin_check_for_update...
```

> [!NOTE]
> **Другие клиенты.** Для VS Code/GitHub Copilot, Cursor, Claude Code и т.д. конфиги — в разделе «Подключение ИИ‑ассистентов» ниже. Везде один адрес: `http://127.0.0.1:8765/mcp`.

### 5. Allure CLI (для `vanessa_open_allure_report`)

Инструмент открытия отчёта сначала генерирует статический отчёт через Allure commandline. Установите CLI локально (без brew-формулы и без `sudo`):

```bash
ALLURE=~/.1c-tools/allure/allure-2.46.1
mkdir -p "$(dirname "$ALLURE")"
curl -L -o /tmp/allure.zip \
  https://github.com/allure-framework/allure2/releases/download/2.46.1/allure-2.46.1.zip
unzip -q /tmp/allure.zip -d ~/.1c-tools/allure
mv ~/.1c-tools/allure/allure-2.46.1 "$ALLURE" 2>/dev/null || true
"$ALLURE/bin/allure" --version   # → Allure 2.46.1
```

Плагин находит CLI по `~/.1c-tools/allure/allure-*/bin/allure` (или через параметр `allureBin` / PATH). Генерация идёт субпроцессом с явным `JAVA_HOME` плагина.

### 6. Окружение Vanessa Automation / OneScript

BDD-инструменты плагина не содержат саму Vanessa — они лишь запускают прогон через EDT. Поэтому нужно подготовить контур VA: **OneScript + vrunner** (движок прогона) + **Vanessa Automation** (библиотека фич/обработка) + каталог `.feature`-сценариев + `env.sh` проекта.

**OneScript (oscript/opm)** — рантайм, на котором живут скрипты 1С и пакетный менеджер `opm`:

```bash
# скачать стабильный дистрибутив OneScript с GitHub (oscript-library/onescript)
curl -L -o /tmp/onescript.zip \
  https://github.com/oscript-library/onescript/releases/download/1.9.1/onescript-1.9.1-macos.zip
unzip -q /tmp/onescript.zip -d ~/.local/onescript
export PATH="$PATH:$HOME/.local/onescript/bin"
oscript --version      # → OneScript 1.9.x
opm --version
```

**vrunner (vanessa-runner)** — CLI-обвязка над Vanessa (запуск, статус, JUnit/Allure-вывод). Ставится пакетом OneScript:

```bash
opm install vanessa-runner
vrunner --version      # путь в ~/.local/onescript/bin, автоконфиг через env.sh
```

> [!NOTE]
> Сверьтесь с актуальной инструкцией проекта [Vanessa Runner](https://github.com/vanessa-runner/vanessa-runner) — там же паттерны `VAParams` и запуска. (Фич-список и версии меняются.)

**Vanessa Automation** — собственно библиотека Gherkin-шагов + обработка. Скачивается с GitHub ([ivanovms/vanessa-automation](https://github.com/ivanovms/vanessa-automation)) и кладётся куда-нибудь рядом:

```bash
curl -L -o /tmp/va.zip https://github.com/ivanovms/vanessa-automation/archive/refs/heads/develop.zip
unzip -q /tmp/va.zip -d ~/Downloads/vanessa-automation
ls ~/Downloads/vanessa-automation/   # каталоги features/, epf/, tools/...
```

**Настройка проекта** (один раз на базу). MCP-тулы читают параметры из `~/.1c-tools/vanessa/projects/<project>/env.sh`: ИБ, пользователь/пароль БД, **порт MCP**, launch-конфиг EDT, каталог фич. Плюс локальный оверрайд `VAParams` с абсолютными путями вывода (`out/{BDD.log,BDDStatus.log,junit,allure}`). Весь прогон свёрнут у нас в `~/.1c-tools/vanessa/run.sh <project> [фича]` (вне git), а `.feature`-сценарии лежат в `~/.1c-tools/vanessa/features/<project>/`.

**Известные грабли (mac):**
- `--pathvanessa` должен указывать на **настоящий** `vanessa-automation.epf`, а не на симлинк — Vanessa ищет `locales/*.epf` рядом с файлом, и симлинк валится с «Файл не обнаружен locales/Messages.epf».
- Резолвить `--workspace` / `--root`, иначе статус считается не туда и `vrunner` врёт по результату.
- На mac **выключить VanessaExt** (`ИспользоватьКомпонентуVanessaExt=False` и связанные): в сборках VA нет мак-бинаря внешней компоненты → 1С всплывает «Установка внешней компоненты».
- Скриншоты: в Windows VA по умолчанию зовёт `nircmd`; на mac в `VAParams`/скрипте заменить на `/usr/sbin/screencapture -x `.

### 7. Smoke-проверка BDD-цикла (опционально)

1. В `~/.1c-tools/vanessa/projects/<project>/env.sh` настроены ИБ, пользователь/пароль, launch-конфиг, каталог фич, **порт MCP**.
2. `vanessa_run_feature` (project + feature) → `launchId`;
3. `vanessa_get_execution_status`, затем `vanessa_get_test_report` (JUnit);
4. `vanessa_open_allure_report` (`outDir` = `<project>/out/<project>` или `launchId`) → откроет вью «Allure Report» в EDT (или внешний браузер при `detached: true`).

Утилиты самопроверки — см. раздел «Сборка и тестирование» и методику уровней Tier-1…Tier-4. Перед внесением изменений в плагин прочитайте `CLAUDE.md` (транзакции BM, двуязычная ru/en модель, каскадные переименования) и скиллы `.claude/skills/`.

> [!TIP]
> **Быстрая итерация разработчика: хот-свап `.class` (и грабли!).** Вместо полной сборки можно распаковать установленный jar, перезаписать перекомпилированный `.class`, пере-зазиповать и вернуть в pool. **НО** это обновляет только классы — `plugin.xml`/`plugin.properties` из нового исходника в jar не попадают. Меняли расширения (view/command/menu) — обязательно перевкладывайте и `plugin.xml` с properties, иначе расширение «тихо» не появится (классический симптом: `PartInitException: Не удалось создать панель` на вью, которой нет в реестре). После любого swap нужен перезапуск EDT (Equinox читает всё при старте).

---

## Лицензия

Проект распространяется под [AGPL-3.0-or-later](LICENSE) (как и апстрим).
