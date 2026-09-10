# list_yaxunit_tests

Enumerates YAXUnit test suites and the tests within each suite for an EDT project.

## Why

A YAXUnit "test suite" is a BSL module whose methods carry the `&Тест` / `&Test`
pragma (the parameterized `&ПараметрическийТест` / `&ParametrizedTest` counts too).
There is no single metadata/UI listing that tells you which modules are test suites
and which methods are tests — this tool walks the BSL AST to answer that, so a client
(or the in-EDT Test Selection view) can present suites with their tests and let the
user run at either granularity.

## Parameters

- `projectName` (required) — EDT project whose test suites to enumerate.
- `limit` — maximum number of suites to return (default 200, max 1000).

## Response

JSON envelope:

```json
{
  "project": "afm",
  "count": 2,
  "testCount": 3,
  "suites": [
    { "modulePath": "CommonModules/OZON_Тесты/Module.bsl",
      "moduleType": "Module", "parentName": "OZON_Тесты",
      "tests": [ { "name": "ТестСправочника" }, { "name": "ТестДокумента" } ] },
    ...
  ]
}
```

Only modules with at least one test method appear as suites. A module whose BSL AST
cannot be loaded is skipped (not a failure).

## Running what you selected

Feed the result back into `run_yaxunit_tests` at the granularity you want:

- **A whole suite** — pass its `modulePath` in `modules`:
  `run_yaxunit_tests({launchConfigurationName: "...", modules: ["CommonModules/OZON_Тесты/Module.bsl"]})`.
- **Specific tests** — pass `Module.Method` names in `tests`:
  `run_yaxunit_tests({launchConfigurationName: "...", tests: ["CommonModules/OZON_Тесты/Module.bsl.ТестСправочника"]})`.

Because the `modules` and `tests` filters are AND-ed by YAXUnit, choose ONE family per
run: either a module list, or the explicit `Module.Method` list covering exactly the
checked tests (enumerating a checked suite's tests into the `tests` list).
