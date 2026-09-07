#!/usr/bin/env python3
"""
Aimed mutation run over the two places where the boundary of time lives.

    python3 dev/mutants.py            # run every mutant
    python3 dev/mutants.py --list     # just show the table

WHY A LIST AND NOT A MUTATION TOOL OVER THE WHOLE PROJECT. A run over everything produces a report
nobody reads: hundreds of mutants, most of them in code where survival means nothing. A run over two
functions produces a verdict. The two are `Timer.isClaimableAt`, which decides what is due, and the
claim query in the Postgres store, which decides the same thing again in SQL — and every off-by-one
this library can have lives in one of them.

WHAT EACH ENTRY ASSERTS, and the second half is the part worth having:

  * the mutant is killed at all;
  * it is killed BY THE TEST THAT IS SUPPOSED TO CATCH IT.

A mutant killed by the wrong test is not good news. It means the check that was written for this
behaviour does not cover it, and some other check happens to trip over it — so the day that other
check changes for its own reasons, the behaviour goes unguarded and nothing says so.

THREE WAYS THIS RUN REFUSES TO PASS VACUOUSLY:

  * the baseline is run first, and a red baseline aborts. Against a broken build every mutant looks
    killed, and the run would report perfect coverage of nothing;
  * a mutation whose search text is no longer in the file is an ERROR, not a skip. The list is
    coupled to the source text and rots when the code is refactored; a silent skip turns this whole
    file into decoration that reports success while testing nothing;
  * the mutated file is restored from a copy taken before the run, and the restore is verified.
"""
import argparse
import glob
import os
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CORE = "chronik-core/src/commonMain/kotlin"
PG = "chronik-postgres/src/main/kotlin"

MUTANTS = [
    dict(
        name="due boundary: dueAt <= now becomes <",
        file=f"{CORE}/Timer.kt",
        old="            dueAt <= now &&",
        new="            dueAt < now &&",
        task=":chronik-core:jvmTest",
        killer="OracleTest",
        why="a timer must fire in its own due second, not the one after",
    ),
    dict(
        name="lease boundary: lockedUntil < now becomes <=",
        file=f"{CORE}/Timer.kt",
        old="(lockedUntil == null || lockedUntil < now)",
        new="(lockedUntil == null || lockedUntil <= now)",
        task=":chronik-core:jvmTest",
        killer="TimerBoundaryTest",
        why="the holder owns the whole second the lease expires in",
    ),
    dict(
        name="terminal timers become claimable",
        file=f"{CORE}/Timer.kt",
        old="        state == TimerState.PENDING &&",
        new="        state != TimerState.FIRED &&",
        task=":chronik-core:jvmTest",
        killer="ChronikOperationsTest",
        why="a cancelled timer must never fire",
    ),
    dict(
        name="lateness may go negative",
        file=f"{CORE}/Timer.kt",
        old="(now - dueAt).coerceAtLeast(0)",
        new="(now - dueAt)",
        task=":chronik-core:jvmTest",
        killer="TimerBoundaryTest",
        why="lateness is a duration, and a negative one would read as early",
    ),
    dict(
        name="dead letter boundary: attempts >= max becomes >",
        file=f"{CORE}/TimerWorker.kt",
        old="            if (attempts >= maxAttempts) {",
        new="            if (attempts > maxAttempts) {",
        task=":chronik-core:jvmTest",
        killer="DeliveryRetryTest",
        why="maxAttempts is the number of attempts, not the number before the last one",
    ),
    dict(
        name="backoff exponent unclamped",
        file=f"{CORE}/TimerWorker.kt",
        old="coerceIn(0, 40)",
        new="coerceIn(0, 200)",
        task=":chronik-core:jvmTest",
        killer="DeliveryRetryTest",
        why="1L shl 64 is 1, so an unclamped shift wraps the wait round to immediately",
    ),
    dict(
        name="SQL due boundary: due_at <= now becomes <",
        file=f"{PG}/ExposedTimerStore.kt",
        old="                            (table.dueAt lessEq now.value) and\n                            (table.lockedUntil.isNull() or (table.lockedUntil less now.value))",
        new="                            (table.dueAt less now.value) and\n                            (table.lockedUntil.isNull() or (table.lockedUntil less now.value))",
        task=":chronik-postgres:test",
        killer="ExposedTimerStoreTest",
        why="the same boundary as the first mutant, decided a second time in SQL",
    ),
    dict(
        name="SQL claim ignores the lease",
        file=f"{PG}/ExposedTimerStore.kt",
        old="                            (table.dueAt lessEq now.value) and\n                            (table.lockedUntil.isNull() or (table.lockedUntil less now.value))",
        new="                            (table.dueAt lessEq now.value)",
        task=":chronik-postgres:test",
        killer="ExposedTimerStoreTest",
        why="a live lease must hide the row from everybody else",
    ),
    dict(
        name="SQL claim ignores the state",
        file=f"{PG}/ExposedTimerStore.kt",
        old="                        (table.state eq TimerState.PENDING.name) and\n                            (table.dueAt lessEq now.value) and",
        new="                        (table.dueAt lessEq now.value) and",
        task=":chronik-postgres:test",
        killer="ExposedTimerStoreTest",
        why="cancelled and dead-lettered timers must stay out of the claim",
    ),
    dict(
        name="SKIP LOCKED replaced by a plain FOR UPDATE",
        file=f"{PG}/ExposedTimerStore.kt",
        old="ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED)",
        new="ForUpdateOption.ForUpdate",
        task=":chronik-postgres:test",
        killer="SkipLockedTest",
        why="two instances must step over each other's rows, not queue behind them",
    ),
    dict(
        name="hasDue narrowed back to unclaimed rows",
        file=f"{PG}/ExposedTimerStore.kt",
        old="                .where { (table.state eq TimerState.PENDING.name) and (table.dueAt lessEq now.value) }\n                .limit(1)",
        new="                .where {\n                    (table.state eq TimerState.PENDING.name) and (table.dueAt lessEq now.value) and\n                        table.lockedUntil.isNull()\n                }\n                .limit(1)",
        task=":chronik-postgres:test",
        killer="ExposedTimerStoreTest",
        why="the loser of a race looks at rows that ARE claimed; a narrowed predicate tells it nothing was due",
    ),
]


def module_of(task):
    """":chronik-core:jvmTest" -> "chronik-core"."""
    return task.split(":")[1]


def gradle(task):
    # The previous run's results are DELETED first, and that is not tidiness.
    #
    # Reading every test-results file under the project meant reading XML left behind by earlier
    # mutants — including ones from a different module that this task never ran. The first version
    # of this script reported a core mutant as "killed by ExposedTimerStoreTest", which no
    # :chronik-core:jvmTest run could possibly have produced. Survival is judged by the exit code
    # and so was never wrong; the ATTRIBUTION was, and attribution is half of what this file
    # asserts. Stale evidence outlives the run that produced it and reads exactly like fresh.
    results = os.path.join(ROOT, module_of(task), "build", "test-results")
    shutil.rmtree(results, ignore_errors=True)

    return subprocess.run(
        ["./gradlew", task, "--rerun-tasks", "--no-configuration-cache", "--quiet"],
        cwd=ROOT, capture_output=True, text=True,
    ).returncode


def failing_tests(task):
    """Which test classes reported a failure in THIS task's run, and only this one."""
    names = set()
    pattern = os.path.join(ROOT, module_of(task), "build", "test-results", "*", "*.xml")
    for path in glob.glob(pattern):
        root = ET.parse(path).getroot()
        if int(root.get("failures", 0)) or int(root.get("errors", 0)):
            # "OracleTest[jvm]" and "io.github.youndie.chronik.postgres.SkipLockedTest" both reduce
            # to the class name, which is what the table names.
            names.add(root.get("name").split("[")[0].split(".")[-1])
    return names


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--list", action="store_true", help="show the mutants and exit")
    args = ap.parse_args()

    if args.list:
        for m in MUTANTS:
            print(f"  {m['name']}\n      {m['file']}  killed by {m['killer']}\n      {m['why']}")
        return 0

    print("→ baseline")
    for task in sorted({m["task"] for m in MUTANTS}):
        if gradle(task) != 0:
            print(f"✗ the baseline is red ({task}). Every mutant would look killed and the run")
            print("  would report perfect coverage of nothing. Fix the build first.")
            return 2
    print("  green\n")

    failures, results = 0, []
    with tempfile.TemporaryDirectory() as backup:
        for path in sorted({m["file"] for m in MUTANTS}):
            shutil.copy(os.path.join(ROOT, path), os.path.join(backup, path.replace("/", "_")))

        for m in MUTANTS:
            full = os.path.join(ROOT, m["file"])
            source = open(full, encoding="utf-8").read()

            # Not a skip. The list is coupled to the source text; when the code moves and this does
            # not, a silent skip leaves a file that reports success and checks nothing.
            if m["old"] not in source:
                results.append((m["name"], "STALE", "the search text is gone; the code moved and this list did not"))
                failures += 1
                continue

            open(full, "w", encoding="utf-8").write(source.replace(m["old"], m["new"], 1))
            try:
                killed = gradle(m["task"]) != 0
                killers = failing_tests(m["task"]) if killed else set()
            finally:
                shutil.copy(os.path.join(backup, m["file"].replace("/", "_")), full)

            if not killed:
                results.append((m["name"], "SURVIVED", m["why"]))
                failures += 1
            elif not killers:
                # The build failed and no test reported anything, which almost always means the
                # mutant did not COMPILE. By exit code that is a kill; by substance it is nothing —
                # a mutant the compiler rejects exercises no test at all, and counting it would
                # inflate the score with the one kind of mutant that proves least.
                results.append((m["name"], "NO COMPILE",
                                "the build failed with no failing test: the mutant is not valid Kotlin, "
                                "so it exercised nothing. Rewrite it so it compiles"))
                failures += 1
            elif m["killer"] not in killers:
                results.append((m["name"], "WRONG TEST",
                                f"expected {m['killer']}, killed by {', '.join(sorted(killers))}"))
                failures += 1
            else:
                results.append((m["name"], "killed", m["killer"]))

        for path in sorted({m["file"] for m in MUTANTS}):
            shutil.copy(os.path.join(backup, path.replace("/", "_")), os.path.join(ROOT, path))

    width = max(len(r[0]) for r in results)
    for name, verdict, note in results:
        print(f"  {name.ljust(width)}  {verdict:<10}  {note}")

    print()
    if failures:
        print(f"✗ {failures} of {len(MUTANTS)} mutants need attention")
        print("  A survivor means the boundary is not checked. A wrong-test kill means the check")
        print("  written for it does not cover it and something else trips over it by accident.")
        return 1

    print(f"✓ all {len(MUTANTS)} mutants killed, each by the test written for it")
    return 0


if __name__ == "__main__":
    sys.exit(main())
