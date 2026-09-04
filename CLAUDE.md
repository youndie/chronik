# chronik — agent instructions

## How to start a session

Read in this order. Skipping the first step is how a task turns into "do the obvious thing", and
here the obvious thing is frequently wrong — three of this project's central decisions are the
opposite of the ones a neighbouring module already took.

1. **[docs/research/research-architecture.md](docs/research/research-architecture.md)** — verified
   facts, decisions D1–D8, risks, and the three places where the original brief turned out to be
   factually wrong. Every fact carries where it was verified; check there before believing a claim
   about petich, konekt or booblik.
2. **[backlog.md](backlog.md)** — the goal, the stages, the generated index. The items themselves
   are one file each in [docs/backlog/](docs/backlog/).
3. **The layer document the task belongs to** — `docs/features/` for behaviour, `docs/services/`
   for a module. While the code does not exist these live on the branch `docs/v1-specification`,
   not on `main`.

## The invariant

> `main` describes what exists. An open pull request describes what will be.

A document with `status: draft` on `main` is a defect: it means intent was recorded as fact. CI
enforces this on the default branch only (`docs_check.py --on-main`), because in a pull request
`draft` is the normal state.

Corollary for this repository right now: `main` carries research and backlog, and nothing else,
because that is all that can be verified today.

## Language

Code is English, documentation is Russian. This is set before the first line of code on purpose —
translating afterwards touches every file and drowns in the diff.

| What | Language |
|---|---|
| Comments, KDoc, test names, exception messages, Gradle task descriptions | English |
| Commit messages, PR titles and bodies, branch names | English, Conventional Commits |
| Root `README.md` | English — it is the shop window |
| `docs/`, `backlog.md`, `CONTRIBUTING.md` | Russian |

Decision anchors in the Russian documents are written in Latin (`D3`, not `Р3`), because English
comments in the code refer to them.

Check: `grep` for Cyrillic in `*.kt` / `*.kts` / `*.toml` / `*.yaml`. Cyrillic inside test data is
legitimate — compare octets against characters.

## Checks

```bash
make check
```

The gate is `backlog_index.py --check`, `docs_check.py` and `coverage_map.py --check`. The two
reports (`bdd_report.py`, `code_anchors.py`) are non-blocking on purpose and read by a person:
demanding a percentage of automated scenarios is meaningless while acceptance is manual, and an
anchor rots because somebody refactored a different repository.

After editing a backlog item run `python3 scripts/backlog_index.py` and commit both files.

## What this library refuses to become

Worth having in front of you before writing code, because every one of these is a plausible next
step that would dissolve the reason the library exists:

- it does **not** execute anybody's code — only the fact "it is time" leaves it;
- it does **not** open its own connection or own a pool — the transaction must be the caller's;
- it does **not** promise exactly-once — at-least-once with an idempotency key, deduplication is
  the receiver's job;
- it does **not** have a calendar or time zones in v1 — seconds, UTC;
- it does **not** catch up missed periods — three periods down means one firing, not three.

Each of these has a numbered decision in the research with the reason and the rejected alternative.
