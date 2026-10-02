---
id: petich-chronik
title: petich-chronik — мост между сагой и таймером
type: service
repo_url: https://github.com/youndie/petich
module: petich-chronik
tech_stack: [Kotlin Multiplatform]
owner: unassigned
depends_on:
  - chronik-core
publishes:
  - "io.github.youndie.petich:petich-chronik"
---

# petich-chronik

> **Модуль живёт не в этом репозитории.** Он заведён в petich по
> [B-10](../backlog/B-10-petich-bridge.md) (youndie/petich#18, 04.09.2026) — после
> [B-11](../backlog/B-11-scheduler-boundary.md), то есть после того, как решена судьба уже
> существующего `petich-scheduler`. Пути в petich ниже — адреса на коммит, на котором они прочитаны.
>
> Документ лежит здесь потому, что поведение моста задаёт chronik, а не саговый движок; поле
> `repo_url` называет репозиторий, которому мост принадлежит.

## 1. Ответственность

Три места, и все три — отдельным модулем, чтобы движок саг без него не тяжелел:

1. **шаг `awaitUntil(at)`** — сага ставит таймер и паузится; срабатывание приходит как тот самый
   «later request», который её будит. Механизм возобновления не меняется;
2. **срок на шаге, ждущем человека** — не только откат, но и произвольное событие в момент срока;
3. **компенсация таймера — это `cancel`**, и она встаёт в обратный порядок как обычный шаг.

## 2. Что здесь НЕ является новым, вопреки первой формулировке

Активное истечение у саг уже есть. `SuspendedPetichSweeper` — фоновый воркер: опрашивает
`findExpired` раз в 30 секунд по умолчанию и вызывает компенсацию сам, никого не дожидаясь. Считать
мост «превращением ожидания в активное истечение» — фактически неверно.

Что мост даёт на самом деле, три вещи поменьше:

- **срок на шаге, а не на саге целиком.** Сейчас `suspendedUntilEpochMs` — одно поле саги;
- **произвольное событие в момент срока, а не только откат.** Подметальщик умеет ровно одно —
  компенсировать;
- **точность, не привязанную к периоду опроса подметальщика.**

Каждое из трёх стоит своей цены; «активность» — не стоит, потому что уже оплачена.

## 2a. Якоря

| Файл | Что там |
|---|---|
| `youndie/petich@bfff0b6!/petich-chronik/src/commonMain/kotlin/` | шаг `awaitUntil`, отмена как компенсация |
| `chronik/chronik-core/src/commonMain/kotlin/TimerSink.kt` | `TimerSink`, который мост реализует (`SagaTimerSink`) |
| `chronik/chronik-core/src/commonMain/kotlin/TimerStore.kt` | `TransactionalTimerStore`, через который мост пишет и отменяет таймер в транзакции саги |

Что читать прежде, чем писать:

| Репозиторий | Код | Зачем |
|---|---|---|
| petich | `youndie/petich@34dd4d7!/petich-core/src/commonMain/kotlin/SuspendedPetichSweeper.kt` | существующее активное истечение — то, с чем мост не должен конфликтовать |
| petich | `youndie/petich@34dd4d7!/petich-core/src/commonMain/kotlin/Petich.kt` | `InterceptorResult.Suspend`, `ttl`, возобновление |
| petich | `youndie/petich@34dd4d7!/petich-scheduler/src/commonMain/kotlin/SchedulerWorker.kt` | модуль, чью роль мост частично перекрывает ([B-11](../backlog/B-11-scheduler-boundary.md)) |

## 4. Зависимости

| Вид | Имя | Зачем |
|---|---|---|
| Модуль | [chronik-core](chronik-core.md) | сам таймер |
| Внешнее | petich-core | сага, шаги, компенсация |

## 8. Грабли

**Побочная находка, стоящая отдельного пункта.** У эталонного потребителя таблица `scheduled_jobs`
заведена миграцией (`youndie/konekt@0ac4dd7!/shared/db/src/main/resources/db/migration/V1__petich_storage.sql`) и
живёт в схеме, а планирует в неё никто: модуль подтянут по зависимости, в сборке сервера не
объявлен, и в схемном тесте фигурирует только его таблица. Пустая таблица, которую поддерживают
миграции ради модуля, которым не пользуются. Закрывается независимо от исхода
[B-11](../backlog/B-11-scheduler-boundary.md).
