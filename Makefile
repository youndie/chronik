# One gate, and CI runs exactly this target.
#
# A local check set that differs from the CI one turns "green here, red there" into the normal
# state of affairs, and then neither is read. So: whatever is not in `make check` is not a gate,
# and whatever is in it runs the same way in both places.

PY ?= python3

.PHONY: check gate report fix stand help

help:
	@echo "make check   - the gate: blocking checks, exactly what CI runs"
	@echo "make stand   - the handover stand: kills a worker holding a timer (needs docker)"
	@echo "make report  - non-blocking reports: BDD coverage, code anchors"
	@echo "make fix     - regenerate the backlog index, fill in missing coverage-map lines"

check: gate report

# Blocking. Any of these failing means the documentation is internally inconsistent, which is a
# defect in the documentation rather than a matter of opinion — or the code does not build and pass
# its tests, which is not a matter of opinion either.
#
# The code is in the same target as the documents on purpose. Two gates mean two things to
# remember, and the one that is not `make check` is the one that stops being run.
gate:
	$(PY) scripts/backlog_index.py --check
	$(PY) scripts/docs_check.py
	$(PY) scripts/coverage_map.py --check
	./gradlew check

# Non-blocking, on purpose. bdd_report counts scenarios, and demanding a percentage is meaningless
# while acceptance is done by hand. code_anchors goes stale because of a refactor in somebody
# else's repository rather than because of an edit here, and it cannot tell a live path from one
# quoted as obsolete. Both are read by a person.
#
# Two things about `--repos ..` on this repository, and both are honest results rather than
# misconfiguration:
#
#   * most anchors name files the backlog is about to create, so they are reported missing until
#     stage-1-core lands;
#   * the research cites petich, konekt, booblik and kompot by path, so those four have to sit
#     next to this checkout for their anchors to resolve. Locally that depends on where they
#     happen to live; the weekly CI job clones them explicitly instead of assuming.
report:
	$(PY) scripts/bdd_report.py
	$(PY) scripts/code_anchors.py --repos ..

fix:
	$(PY) scripts/backlog_index.py
	$(PY) scripts/coverage_map.py --fix

# NOT part of `make check`, and this is the one place that rule is bent — so the reason is here
# rather than assumed. The stand starts Postgres, builds a worker, runs three JVMs and kills one; it
# takes the better part of a minute and needs a working docker, which a contributor editing a
# document does not have to have.
#
# A check outside the gate rots unseen, so CI runs this target in a job of its own. If that job
# stops being green nobody has to notice by accident.
stand:
	./dev/check-handover.sh
