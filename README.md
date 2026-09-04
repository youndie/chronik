# chronik

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

### 🚧 Status

Nothing is implemented yet. What exists today is the specification and the reasoning behind it:

- **[docs/research/research-architecture.md](docs/research/research-architecture.md)** — the
  verified facts this design rests on, eight decisions with their rejected alternatives, and the
  open risks;
- **[backlog.md](backlog.md)** — the order of work and its acceptance criteria.

The behaviour documents live on the branch `docs/v1-specification` until the code they describe
exists. `main` describes what exists.

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
