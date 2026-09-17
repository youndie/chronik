---
id: B-18
title: "Релиз 0.2.0: нативные варианты доезжают до потребителя"
status: done
priority: P1
size: S
stage: stage-4-consumers
---

# B-18 — Выпуск 0.2.0

`linuxX64` у ядра и корпуса ([B-16](B-16-linux-native-target.md)) и хранилище на sqlx4k
([B-17](B-17-sqlx4k-sqlite-store.md)) влиты в `main` и **не выпущены**: на Central по-прежнему
единственный релиз `0.1.0`, у которого пять вариантов и все jvm.

```
$ curl -o /dev/null -w "%{http_code}" .../chronik-core-linuxx64/0.1.0/chronik-core-linuxx64-0.1.0.klib
404
```

Пока это так, работа остаётся внутри репозитория: **влитое не значит выпущенное**.

- **Кто об этом просит и почему это не внутреннее дело.** petich переносится на Kotlin/Native, и
  единственный его модуль, который не может объявить нативный таргет, — мост `petich-chronik`:
  модуль не объявляет таргет, для которого у зависимости нет варианта. Запрос заведён снаружи:
  [youndie/chronik#21](https://github.com/youndie/chronik/issues/21).
- **0.2.0, а не 0.1.1.** Появились новый таргет и новый модуль — меняется то, что и кому
  резолвится. Патч-номер это спрятал бы.
- **Голова версии поднята до выпуска**, иначе снапшоты продолжали бы выходить как `0.1.0.N` —
  номера, которые сортируются ниже выпущенного `0.2.0`, но несут более новый код.
- **Не покрывает:** саму кнопку. `central.yaml` в youndie/sborka загружает бандл **staged**;
  выпускает его человек в портале, и это осознанное решение владельца — версию на Central нельзя
  ни переписать, ни забрать.

- AC: `io.github.youndie.chronik:chronik-core-linuxx64:0.2.0` отдаёт 200 на repo1.maven.org, и
  сторонний билд на `linuxX64` резолвит `chronik-core:0.2.0`.
- Anchors: `gradle.properties`, `.github/workflows/publish-snapshot.yaml`

## Закрыт 17.09.2026

`chronik 0.2.0` на Maven Central, с нативными вариантами:

```
$ curl -o /dev/null -w "%{http_code}" .../chronik-core-linuxx64/0.2.0/chronik-core-linuxx64-0.2.0.klib
200
$ curl -s .../chronik-core/maven-metadata.xml | grep version
<version>0.1.0</version>
<version>0.2.0</version>
```

Загрузил `central.yaml` в youndie/sborka (бандл ложится **staged**), выпустил владелец кнопкой в
портале — как этот воркфлоу и задуман.

**Приёмка пришла снаружи, и это сильнее собственного прогона.** В тот же день petich объявил
`linuxX64()` у моста: его сьюта зелёная на обоих таргетах (8 тестов на jvm, 4 на native), а следом
вышел petich 0.2.0 — в этом порядке, чтобы ни один релиз не увёз модуль без варианта, который есть
у соседей. [youndie/chronik#21](https://github.com/youndie/chronik/issues/21) закрыт.

Релиз оформлен тегом и заметками: https://github.com/youndie/chronik/releases/tag/v0.2.0 — первый
в этом репозитории; до него ни тегов, ни релизов не было, хотя `central.yaml` сверяет дерево с
тегом `v<версия>`, если тот есть.
