# Tier 5 — Test Plan

**Date:** 2026-10-09
**Last updated:** 2026-11-15

---

## Status: IN FORCE

The kinds of check and which oracle each answers for. Oracle tests live in
`core/src/test/kotlin/dev/optrolley/oracles/`, the simulator in `…/sim/`,
app oracles in `app/src/test/`. Run them all with
`docker compose run --rm build ./gradlew :core:test :app:testDebugUnitTest`.

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
| O1, O2 | property | Any list, any actions, any store: the presented entries are a permutation of the list, and identical in content across stores. |
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
| O19 | property | Same inputs twice, and after a shuffled rebuild: same output. Also with the journal emptied and with a catalogue that answers anything. |
| O20–O22 | unit + property | No action changes a trip already on record; dropping what was learned and rebuilding from the record, and export then import, give identical presentations and confidence. |
| O59 | unit + property | Any actions applied, the journal emptied, then more actions: every presentation as with the journal kept. |
| O62 | unit, on fixtures | A first-build journal alone, opened by this build, and a version 1 export imported: list, every store's presentation and every code as the first build gave them. The fixtures are files the first build wrote. |
| O23 | build check | The merged manifest requests `CAMERA` and `INTERNET` and nothing else. |
| O24 | instrumented | Tier 2 walkthrough with radios off; the library shows numbers. |
| O60 | unit + property | The same actions with a catalogue that answers, one that fails and one that never returns: identical state, record and presentations. |
| O61 | unit | A recording stand-in for the network: every request the app makes is for one code's number and carries nothing else of the user's. |
| O55–O58 | unit + property | Rename an item anywhere in a generated sequence of actions: every presentation and confidence unchanged, its codes still add it; the old name then starts cold. Join two items: trips, statements and codes of both count, the entries merge by the rule. Unlink: the code is as never linked, nothing else differs. Written from tier 3 before the code, by an agent that has not seen it. |
| O41–O45 | unit + property | A scan of a linked code against typing the name; links inserted anywhere move nothing; links survive rebuild and export. Also at the view model, scanning against typing over the same history. |
| O46 | instrumented | With the camera refused, the scanner does not open and the list still works. |
| O47 | unit | The export of a history with scans holds only the decoded codes. |
| O48, O49 | unit + property | A scan to tick against a tap (and against typing then tapping, for an item not on the list), through to what the ended trip teaches; a second scan of a ticked item records nothing. Also at the view model. |
| O50–O54 | unit + property | Confidence against a reference written from tier 3 §I alone, edge cases at each threshold, the same run under two routers, stability within a trip, and the move scenario under every router. |

**Tier 4's predicates** (not frozen) are tested beside the code: P1 and P4–P5
as properties over generated actions; P2 by cutting the three files off
after every write of a sequence and loading what is left; P3 by the
benchmark's histories, as a ratio so it does not depend on the machine.

**Oracle tests the first build passed stay as they are**, since a list of
actions still builds any case. The exceptions are the ones tier 3
restated: the manifest check (O23), and any that took an entry's new name
to mean a different item (now O55–O57). Each such change is listed for the
owner before it is made.

**Decoding without a camera.** The decoder is tested on barcodes rendered by
ZXing's own encoder, upright and rotated, so the frame-to-code path is
checked with no camera in the loop. Whether it reads real packs under shop
lighting is for the phone.

## 3. The reference simulation suite

Specified before any router existed, by an agent that had seen none. Code in
`core/src/test/kotlin/dev/optrolley/sim/`; the report of every router's
curves is written to `core/build/sim-report.md`.

**Shops.** Zones in walking order, items assigned to zones at random. The
items are the shopper's repertoire there; names are drawn from a shuffled
numbering, so no order of names says anything about the layout.

| Shop | Zones | Items | Staples |
|------|------:|------:|--------:|
| corner | 6 | 36 | 10 |
| supermarket | 12 | 96 | 18 |
| superstore | 20 | 180 | 22 |

**Shopper.** Each trip: a list drawn from the repertoire — each staple with
probability 0.5–0.9, each other item 0.02–0.10, clamped to 8–30 entries —
added in random order hours apart; the store selected; every zone walked in
order, 15–60 s through each; items in a zone picked in random order, 2–12 s
apart; each ticked 0.3–2.5 s after it is picked; the trip finished by the
user. Ticked entries leave the list at the end, as tier 2 §5 says.

**Noise**, each with its own random stream, so turning one on leaves every
other draw unchanged:

| Source | Setting in the suite |
|--------|----------------------|
| Batched | per pick, 0.1 chance that it and the next 1–4 are ticked together at a pause, 0.4–2 s apart |
| Late | per pick, 0.1 chance it is ticked 1–10 s after the tick of the pick 1–4 later (at the till if none) |
| Reversed | per trip, 0.25 chance the zones are walked backwards |
| Rearranged | from trip 10: 30% of zones moved in the walk, 10% of items moved to another zone |
| Absent | 3 wanted items not stocked; each on a list with 0.3 chance, said "not here" with 0.5, removed after the trip with 0.5 |

**Scenarios:** clean, one per source, and everything — 7. Each × 3 shops ×
seeds 1–8, 20 trips. A shop's seed is shared across scenarios, so they differ
only by their noise.

**Measure**, per trip n, for the presentation at the start of the trip (its
list added, its store selected) against the trip's **walk** — the order the
items were picked up, grouped by the tie window on pick times. Noise
corrupts what the app is told, never the yardstick.

- **Disorder**: counted pairs — both walked, in different tie groups — that
  are presented the other way round. Normalised: as a share of the walk's
  counted pairs, then averaged over walks.
- **Paper**: the same for add order.
- **Noise floor**: the same for the layout in force at that trip, zone
  order. A pair within one zone counts ½: the walk picks within a zone in no
  order, so any order fixed before the walk gets it wrong half the time.
- **Bound** (diagnostic, not in O18): the true layout for placed items,
  with unplaced ones ahead in add order as tier 2 §6 requires — the best any
  router can do.

- **Placed pairs**: the counted pairs whose items were both placed (tier 3)
  by the trips before; disorder and floor are also taken over these alone.

**O18 holds for a router** when, pooled over scenarios, shops and seeds:
mean disorder ≤ paper at every n, over all pairs; at n = 5, mean normalised
disorder over placed pairs is within 0.05 of the floor's over the same
pairs; and over the scenarios with a rearrangement, the same at its fifth
trip (trip 14).

## 4. Fixtures

- **Synthetic histories**, generated. The bulk of the suite.
- **Real histories**, exported from the field trial once there is one. These
  are personal data and stay out of the repository (`/exports/` is ignored);
  anything committed as a fixture is anonymised first.

## 5. What cannot be automated

Whether the order is actually good in a real shop, whether the list can be
used one-handed, and whether people tick as they go. The field trial at build
step 9 answers these, and nothing earlier does.
