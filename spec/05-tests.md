# Tier 5 — Test Plan

**Date:** 2026-10-09

---

## Status: OUTLINE

No tests exist; no toolchain exists to run them. This fixes the kinds of
check and which oracle each one answers for. Cases are written at each build
step, failing first, before the code that satisfies them.

---

Five kinds of check, kept apart because they fail for different reasons.

| Kind | Location | Needs | On failure |
|------|----------|-------|------------|
| Unit | `core/` beside the code | JDK | the code is wrong |
| Property | `core/`, generated inputs | JDK | the code is wrong |
| Simulation | `core/`, the simulator | JDK | the router is not good enough |
| Instrumented | `app/` | emulator or device | the app is wrong |
| Field trial | a real shop | a phone and a trolley | **tier 1 may be wrong** |

**A field-trial failure is not automatically a bug.** It may mean an
assumption in tier 1 §5 does not hold. Diagnose before changing code.

## 1. Rules

- **Tests come first and fail first.** A test that has never been seen to
  fail has not been shown to test anything.
- **Oracle tests are written from tier 3, not from the implementation.** Where
  practical, by an agent that has not read the code under test.
- **The implementer does not edit oracle tests.** A frozen oracle's test that
  fails is fixed in the implementation, or raised as a finding.

## 2. Oracle coverage

| Oracles | Kind | Shape of the test |
|---------|------|-------------------|
| O1, O2 | property | Any list, any history, any store: the presented entries are a permutation of the list, and identical in content across stores. |
| O3, O4 | instrumented | Act on entries in each section; Elsewhere count visible. |
| O5, O6 | unit + property | Only explicit statements populate Elsewhere; a tick clears it. |
| O7–O9 | property | Any sequence of ticks, unticks and additions within a trip preserves relative order of untouched entries. |
| O10 | instrumented | No store change mid-trip without a user action. |
| O11, O12 | unit | Empty history gives entry order; unplaced are contiguous. |
| O13 | unit | One trip, replayed. The smoke test for learning. |
| O14 | property | Generate a hidden total order and trips agreeing with it; every forced pair is presented in that order. |
| O15 | property | Permute within tie windows; presentations identical. |
| O16 | property | Add arbitrary history at another store; presentation unchanged. |
| O17 | unit + property | Old order, then K trips of a new one. |
| O18 | simulation | Disorder curve against the paper-order baseline and the frozen thresholds. |
| O19 | property | Same inputs twice, and after a shuffled rebuild: same output. |
| O20–O22 | unit + property | History only grows; rebuild and export/import round-trip to identical presentations. |
| O23 | build check | Inspect the built package's declared permissions. |
| O24 | instrumented | Tier 2 walkthrough with radios off. |

## 3. The reference simulation suite

To be specified at build step 4, before any router exists: the shops, the
noise settings, the seeds, the number of trips. Fixed seeds, so that O18 is a
repeatable measurement and not a matter of luck (and so that the suite itself
satisfies O19).

At least one scenario per noise source in tier 4 §6, one with no noise at
all, and one with everything on.

## 4. Fixtures

- **Synthetic histories**, generated. The bulk of the suite.
- **Real histories**, exported from the field trial once there is one. These
  are personal data and stay out of the repository (`/exports/` is ignored);
  anything committed as a fixture is anonymised first.

## 5. What cannot be automated

Whether the order is actually good in a real shop, whether the list can be
used one-handed, and whether people tick as they go. The field trial at build
step 9 answers these, and nothing earlier does.
