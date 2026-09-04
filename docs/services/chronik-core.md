---
id: chronik-core
title: chronik-core — примитив таймера
type: service
module: chronik-core
tech_stack: [Kotlin Multiplatform, kotlinx-coroutines]
owner: unassigned
depends_on:
  - chronik-postgres
publishes:
  - "io.github.youndie:chronik-core"
---

# chronik-core

> **Модуль существует наполовину.** Есть типы и контракты — единица времени, строка таймера,
> контракты хранилища и приёмника ([B-01](../backlog/B-01-semantics-before-code.md), закрыт).
> Нет ни воркера, ни реализации операций: это [B-03](../backlog/B-03-core-api.md) и
> [B-04](../backlog/B-04-lateness-metric.md). Слой `services/` не имеет поля `status`, поэтому
> оговорка живёт здесь, а документ — на ветке `docs/v1-specification`, пока фичи в драфте.

## 1. Ответственность

Три операции над таймером (`schedule`, `cancel`, `reschedule`), контракт хранилища, контракт
приёмника и воркер, который опрашивает «что пора» и отдаёт факт наружу. Про SQL не знает ничего.

Чего **не** делает — и это определение модуля, а не список недоделок:

- не исполняет чужой код. Наружу уходит факт «пора»; что с ним делать, решает получатель. Это
  главное отличие от `petich-scheduler`, который вызывает `ScheduledJobRunner.run` внутри себя;
- не открывает соединений и не владеет пулом — транзакция приходит снаружи (D2);
- не знает бизнес-типов: payload — уже сериализованная строка, как `OutboxRecord` у соседа;
- не имеет календаря и таймзон (D5), не догоняет пропущенные периоды (D6).

## 2. Контракты

* **Публичный API:** `chronik-core/src/commonMain/kotlin/` — три операции, `TimerSink`, `TimerStore`
* **Часы — параметр**, а не `System.currentTimeMillis()` внутри. Причина та же, по которой это
  сделано в `petich/petich-core/src/commonMain/kotlin/Petich.kt` (`PetichClock`): `commonMain` не
  видит `java.*`, а тест обязан двигать время, а не спать на настоящих часах.

## 2a. Якоря

| Файл | Что там | Есть? |
|---|---|---|
| `chronik-core/src/commonMain/kotlin/EpochSeconds.kt` | `EpochSeconds` и `ChronikClock` | да |
| `chronik-core/src/commonMain/kotlin/Timer.kt` | строка таймера, состояния, `isClaimableAt`, `latenessAt` | да |
| `chronik-core/src/commonMain/kotlin/TimerStore.kt` | `TimerStore` и `TransactionalTimerStore` | да |
| `chronik-core/src/commonMain/kotlin/TimerSink.kt` | `TimerSink`, `FiredTimer` | да |
| `chronik-core/src/commonTest/kotlin/TimerBoundaryTest.kt` | граница срока и аренды, с проверенным положительным контролем | да |
| `chronik-core/src/commonMain/kotlin/` | три операции и воркер | [B-03](../backlog/B-03-core-api.md) |
| `chronik-core/src/commonTest/kotlin/` | оракул на фейковых часах | [B-02](../backlog/B-02-property-oracle.md) |

Образцы, по которым это делается, читаются здесь:

| Репозиторий | Код | Что взято |
|---|---|---|
| petich | `petich/petich-outbox-core/src/commonMain/kotlin/OutboxRelayWorker.kt` | форма воркера: `tick()` отдельно от `start(scope)`, провал одного не топит батч |
| petich | `petich/petich-core/src/commonMain/kotlin/SuspendedPetichSweeper.kt` | опциональное расширение хранилища вместо метода в базовом интерфейсе |
| petich | `petich/petich-scheduler/src/commonMain/kotlin/SchedulerWorker.kt` | чего **не** повторять: исполнение задачи внутри и допущенный двойной запуск |

## 3. Как это устроено

**`tick()` отдельно от `start(scope)`.** Один проход вызывается из теста или админского эндпоинта
без запуска корутины и без ожидания интервала. Для тестов это решающее: доставка проверяется
вызовом прохода и сдвигом часов, а не сном на настоящем времени и надеждой, что воркер проснулся
нужное число раз. На загруженной машине эта надежда не сбывается.

**Отказ вместо предупреждения.** Планировщик не собирается на хранилище, которое не умеет писать в
чужую транзакцию. Не лог, не счётчик — отказ на сборке объекта, по образцу
`PetichEngineConfig(requireOutbox = true)`. Причина в риске 1 ресёрча: провал этого шва невидим со
всех сторон, кроме одной, на которую никто не смотрит.

**Опоздание — значение в API, а не строка лога** ([B-04](../backlog/B-04-lateness-metric.md)).
Логи агрегируются по-разному в каждом приложении; метрика собирается одинаково.

## 4. Зависимости

| Вид | Имя | Зачем |
|---|---|---|
| Библиотека | kotlinx-coroutines | воркер и приостановка |
| Модуль | [chronik-postgres](chronik-postgres.md) | реализация хранилища; ядро зависит от неё только в тестах |

## 7. Конфигурация

Параметры конструктора воркера, а не файл: интервал опроса, размер батча, срок аренды, потолок
попыток доставки. Значения по умолчанию — в исходнике, копию сюда не переносить.

## 8. Грабли

Пусто: кода нет. Раздел заведён намеренно и заполняется по мере находок — удалять его не надо.
