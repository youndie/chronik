# chronik

[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![chronik-core](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/chronik-core?name=snapshots&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/chronik-core)
[![chronik-postgres](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/chronik-postgres?name=snapshots&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/chronik-postgres)
[![chronik-conformance](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/chronik-conformance?name=snapshots&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/chronik-conformance)
[![license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

**a durable timer for Kotlin, and nothing else** — `schedule(id, at, payload)`, and at `at` (or
later, but never earlier and never lost) the fact "it is time" leaves the library

> ⏱ one row in your transaction → one event you cannot lose

Built around a single question: what happens to the timer if the process dies before it fires.

### 🤔 What it solves

A state change that has to be undone in three days, a confirmation that must expire whether or not
anybody comes back, a plan that ends on a date. Written the obvious way, each becomes a row with a
timestamp and a job that scans for it — and the scan is written again in every service, subtly
differently, and none of them survives being run twice.

The awkward part is not the scanning. It is that the timer and the state change are two writes. If
the state is committed and the timer is not, nothing looks wrong: the operation succeeded, the row
is correct, and only the thing nobody is watching for never happens.

chronik writes the timer **in the caller's transaction**, so the two commit together or not at all.

### 📦 Installation

```kotlin
repositories {
    maven("https://reposilite.kotlin.website/snapshots")
}

dependencies {
    implementation("io.github.youndie:chronik-core:0.1.0.4")
    implementation("io.github.youndie:chronik-postgres:0.1.0.4")
}
```

`chronik-postgres` ships no driver, no connection pool and no DDL: it takes an Exposed `Database`
you hand it, and the table describes itself — indexes included — so a schema generator produces
something that matches what the queries actually filter on.

### 📖 Where the reasoning is

- **[docs/research/research-architecture.md](docs/research/research-architecture.md)** — the
  verified facts this design rests on, ten decisions with their rejected alternatives, and the
  open risks;
- **[docs/benchmarking.md](docs/benchmarking.md)** — the numbers, with what was measured and on
  what;
- **[backlog.md](backlog.md)** — every item, closed with what it actually cost.

### 🚫 What it does not do

Every one of these is a deliberate refusal with a numbered decision behind it, not a gap:

- **it does not run your code** — only the fact "it is time" leaves the library; what to do with it
  is the receiver's decision;
- **it does not own a connection or a pool** — it writes through the transaction you hand it, which
  is the entire reason it exists rather than a limitation;
- **it does not promise exactly-once** — delivery is at-least-once with an idempotency key in the
  payload, and deduplication belongs to the receiver;
- **it does not have a calendar** — seconds, UTC, no time zones and no "the same day next month"
  in v1;
- **it does not catch up missed periods** — three periods down means one firing, not three, because
  for side-effecting work catching up is more dangerous than skipping;
- **it does not retry your business logic** — the retries here deliver the event; a failed
  operation is somebody else's problem, on purpose.

### 📐 Precision

Seconds. Not milliseconds, and this is written down rather than implied: millisecond precision
needs a different wake-up mechanism than polling, and a promise that polling cannot keep is worse
than no promise.

### 📊 The number

Lateness — how long after its due time a timer actually fired — is a supported outcome rather than
an error, and it is the number this project publishes. A timer whose moment passed while the
process was down fires on start-up, and the lateness is recorded. Delivery latency on its own
cannot tell "fired on time" from "picked up forty minutes late".

No figure is quoted here yet, because none has been measured.
