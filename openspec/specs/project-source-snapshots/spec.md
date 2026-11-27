# project-source-snapshots Specification

## Purpose

Позволяет агенту без клона повторно читать изменения исходников на машине EDT и передавать проверенную версию следующей операции по идентификатору.

## Requirements

### Requirement: Полный снимок разрешённых исходников
Сервер SHALL выдавать `snapshotId`, время окончания хранения, состав проектов, ветку и базовый HEAD каждого репозитория. Манифест SHALL различать базу, индекс и диск и включать staged, unstaged, новые неигнорируемые файлы и удаления в разрешённой области. Неоднозначное переименование SHALL отражаться как удаление и добавление.

#### Scenario: Правки ещё не закоммичены
- **WHEN** изменённый `.mdo` есть только в индексе, а новый `.bsl` ещё не добавлен в Git
- **THEN** оба файла имеют записи с разными статусами и идентификаторами; агент может прочитать соответствующие изменения без локального Git.

#### Scenario: Индекс отличается от обеих сторон
- **WHEN** после индексации версия на диске возвращена к HEAD
- **THEN** манифест показывает обе дельты, хотя итоговый diff базы и диска пуст.

### Requirement: Проверяемость захвата
Сервер SHALL отличать согласованное состояние модели и диска от расхождения и неизвестного состояния. Чтение MUST NOT сохранять редактор или исправлять проект. Неполный либо нестабильный захват MUST NOT считаться пригодным для подтверждения запуска или коммита. При соблюдении остальных условий стабильный дисковый захват со статусом `verified` или `unknown` SHALL допускаться к коммиту. Снимок со статусом `diverged` MUST NOT допускаться к коммиту.

#### Scenario: Несохранённая модель
- **WHEN** на диске удалось получить стабильные байты, но EDT не подтвердил их соответствие модели
- **THEN** доступное дисковое представление явно помечено как непроверенное, даже при пустом diff.

#### Scenario: Обнаружено расхождение модели и диска
- **WHEN** EDT сообщает о расхождении модели с дисковым снимком
- **THEN** снимок имеет статус `diverged` и отклоняется при попытке коммита.

### Requirement: Неизменяемое чтение и сравнение
Сервер SHALL предоставлять страницы манифеста и diff по ID, указывать продолжение или явное ограничение выдачи и сравнивать два доступных снимка одной области. Обращение к истёкшему ID MUST NOT переключаться на живые файлы.

#### Scenario: Правка между страницами
- **WHEN** после первой страницы файл переименован или удалён, затем снято новое состояние
- **THEN** оставшиеся страницы прежнего снимка читаются из прежней версии, а сравнение двух снимков показывает изменение.

#### Scenario: Исчерпан лимит
- **WHEN** снимок истёк либо размер файла не допускает вывод полного diff
- **THEN** ответ явно сообщает недоступность или неполноту, не представляя её как отсутствие отличий.

### Requirement: Серверная граница чтения
Сервер MUST проверять доступ к проектам и снимку на каждом вызове, принимать только типизированные идентификаторы и ограниченные параметры страниц. Содержимое соседних проектов, внешних ссылок, служебных и неразрешённых файлов MUST NOT попадать в выдачу или историю запросов через этот инструмент.

#### Scenario: Недопустимый ресурс
- **WHEN** клиент предъявил ID другой области либо именованный ресурс указывает за её пределы
- **THEN** доступ отклонён без чтения содержимого внешнего ресурса и без раскрытия пути хоста.

### Requirement: Безопасное открытие источников на Windows
В первом релизе инструменты доступны только на выделенном Windows EDT с защищённым аутентифицированным входом и рабочими деревьями на локальных дисках. Сервер MUST отклонять захват, если нельзя подтвердить безопасное открытие источников в пределах разрешённого проекта. Он MUST NOT использовать менее строгий способ чтения в качестве запасного варианта.

#### Scenario: Подмена каталога при захвате на Windows
- **WHEN** каталог на пути к источнику является или становится symlink либо junction за пределы разрешённой области
- **THEN** сервер не следует за ссылкой, не читает внешний файл и не раскрывает путь хоста; если безопасное открытие источника невозможно, захват отклонён.

### Requirement: Retained build inputs for YAxUnit
`capture_project_snapshot` SHALL accept `forYaxunit: true` to retain the complete build inputs from the authorized disk scope. The response SHALL identify whether the snapshot can be built for YAxUnit. Capture MUST refuse an incomplete or unstable payload and MUST leave sources and editors untouched. The live EDT model status SHALL retain its inspection semantics.

#### Scenario: A complete disk snapshot has unknown model status
- **WHEN** a stable capture records all authorized build inputs, including unchanged sources and project descriptors, while the live model status is unknown
- **THEN** the snapshot is eligible for an isolated build and remains marked unknown for live-model inspection.

#### Scenario: A build input cannot be captured safely
- **WHEN** a required project descriptor or source is missing, linked, outside the allowed source types, or exceeds a limit
- **THEN** the build capture is refused and no runnable snapshot is issued.

### Requirement: Admission of a snapshot-bound YAxUnit run
`run_yaxunit_tests` SHALL accept `snapshotId` for report-producing Windows runs. The request MUST use the authenticated route, a retained build payload owned by the caller, and current execution grants for every selected project. The snapshot defines the base and extension projects. Omitting `snapshotId` SHALL preserve ordinary launch behavior.

#### Scenario: A live application or debug mode is requested
- **WHEN** the caller supplies a live application, launch configuration, debug mode, or live update options with `snapshotId`
- **THEN** the server rejects the combination before creating a workspace, opening an infobase, or terminating a client.

#### Scenario: The snapshot is unavailable to the caller
- **WHEN** an inspection-only, expired, or foreign snapshot is requested, or a project grant has been revoked
- **THEN** the server refuses the run without exposing another owner's snapshot.

### Requirement: Build and run in a temporary infobase
A bound job MUST create its own temporary workspace and file infobase using only the retained source payload and the pinned engine template. It MUST apply the captured configuration and extensions before starting a fresh thin client. It MUST release temporary resources on completion, failure, and cancellation.

#### Scenario: The working tree changes after capture
- **WHEN** a source file is edited, deleted, or renamed after the snapshot is captured
- **THEN** a bound run still consumes the retained version and does not read the changed source from the working tree.

#### Scenario: A dependency cannot be built
- **WHEN** an extension has an unsupported or missing dependency, an artifact fails to load, or the engine template fails its identity check
- **THEN** the server stops and reports the failed phase without running against a live infobase.

#### Scenario: The same snapshot is submitted twice
- **WHEN** the caller starts another bound job from the same snapshot
- **THEN** the new job uses a separate temporary infobase, client, and JUnit report.

### Requirement: Snapshot evidence in the YAxUnit report
The server SHALL associate the job ID, `snapshotId`, project input fingerprints, and effective test selectors with a fresh JUnit report that contains at least one executed test. Immediate and polled results MUST carry the same evidence. Access to bound job results MUST follow the snapshot ownership and grant rules.

#### Scenario: A genuine assertion fails
- **WHEN** a fresh report from the isolated run contains an assertion failure
- **THEN** the result preserves the failure and the snapshot evidence.

#### Scenario: No valid execution report exists
- **WHEN** the job is cancelled, starts no tests, or has no valid fresh JUnit report
- **THEN** the result identifies the outcome without claiming verified execution.

#### Scenario: A different caller polls the job
- **WHEN** a caller without current access presents a bound job ID
- **THEN** the server withholds the report and snapshot metadata.

### Requirement: Изолированный запуск по версии Git-дерева
Сервер SHALL принимать явный источник изолированного запуска — закреплённые входы плана либо дерево достижимого коммита — и собирать полный набор конфигурации, расширений и дескрипторов из этих версий с утверждённой средой. Сервер MUST NOT дополнять отсутствующие входы рабочим деревом или живой ИБ. Прежние способы запуска сохраняют свои условия допуска.

#### Scenario: Зелёный снимок и красный кандидат
- **WHEN** тест проходит в дисковом снимке только благодаря чужой правке или игнорируемому модулю, не входящему в выбранное дерево
- **THEN** запуск по кандидату воспроизводит поведение без этой правки или сообщает ошибку сборки; зелёный отчёт снимка не удостоверяет кандидата. Чужие файлы остаются на месте.

#### Scenario: Независимый прогон после коммита
- **WHEN** reviewer с правами на все входные проекты запускает те же тесты по подтверждённому commitSha после изменения WT
- **THEN** выполняется отдельная job в новой ИБ по treeId этого SHA с новыми результатами; не используются отчёт автора, живые исходники и временная ИБ предшествующей job.

### Requirement: Свидетельство состава и результата тестов
Немедленный ответ и результат опроса по jobId SHALL идентифицировать источник запуска, каждый проект и его входы, среду, эффективные селекторы, реально исполненные тесты и результаты свежего JUnit. Признать входы снимка равными кандидату сервер SHALL только по полному манифесту. Доступ к материалам требует действующих прав на все входные проекты.

#### Scenario: Ожидание и инвалидация
- **WHEN** запуск вернул Pending, а затем пользователь изменил выбранный модуль или дескриптор расширения
- **THEN** опрос того же jobId получает результат прежних входов. Для изменённой версии нужны новые входы и новый запуск. Результат без JUnit, без выполненных тестов, при отмене или ошибке сборки не подтверждает прохождение проверки.

#### Scenario: Расходятся фильтр или тестовая среда
- **WHEN** дерево совпало, но другая job использовала другой seed, версию toolchain, состав расширений или фильтр
- **THEN** различие видно из свидетельств; данные той job не выдаются как результат запрошенной проверки.
