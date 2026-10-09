# Tier 3 — Predicates and Oracles

**Date:** 2026-10-09

---

## Status: DRAFT — not frozen

These are meant to become frozen oracles: stated over what a user can observe
— the list as presented, the observation history, an export file — and
checkable by someone who knows nothing about how the app is built.

**They are not frozen yet, for two reasons.**

1. Tiers 1 and 2 are unconfirmed drafts. Freezing predicates derived from an
   unconfirmed scope would freeze the guess.
2. They were written by the same agent that outlined tier 4. Independence
   should be structural: before freezing, have them re-derived from tiers 1–2
   by an agent that has not read tier 4, and reconcile the two sets.

Once frozen: they may not be weakened to accommodate a design. An oracle that
cannot be satisfied is a finding about tier 1 or 2 and is raised as one.
Adding oracles is fine. Loosening one needs explicit agreement and a recorded
reason.

Values marked **[set at freeze]** must be fixed before any router exists, so
that the threshold cannot be chosen to fit the result.

---

## Definitions

- **Presentation**: the sections and the order of entries shown for a given
  list, observation history and store selection.
- **Tie window**: the interval within which two ticks are treated as
  simultaneous. **[set at freeze]**
- **Trip order**: the order of a trip's final ticks, with ticks inside one tie
  window unordered relative to each other.
- **Consistent history**: the trips at a store are consistent if some single
  total order of items agrees with every trip's order.

## A. List integrity

Derived from T1.

- **O1 — Conservation.** For every store selection, including none, the
  entries presented across all sections are exactly the entries on the list.
  None missing, none duplicated.
- **O2 — Selection only rearranges.** Changing the selected store changes
  order and sectioning and nothing else: not membership, quantity, note or
  ticked state.
- **O3 — Reachability.** Every entry can be ticked, edited and removed from
  every store's presentation, whichever section it is in.
- **O4 — Elsewhere is announced.** Whenever any entry is in Elsewhere, that
  fact and the number of such entries are shown wherever the list is shown.
- **O5 — Positive evidence only.** An entry is in Elsewhere for a store only
  if the user's most recent explicit statement about that item and that store
  or its chain says it is not available there.
- **O6 — Purchase overrides.** An item ticked at a store is not in Elsewhere
  at that store afterwards, whatever was said before.

## B. Stability

Derived from T2.

- **O7 — Ticking moves nothing else.** Within a trip, no tick or untick
  changes the relative order of any two other entries.
- **O8 — Additions disturb nothing.** Adding an entry mid-trip does not change
  the relative order of entries already presented.
- **O9 — No drift.** Within a trip, the relative order of two unticked entries
  changes only through an explicit user action on one of them.
- **O10 — No silent store change.** The selected store never changes during a
  trip except by explicit user action.

## C. Cold start

Derived from T3.

- **O11 — Paper order.** With no store selected, or a store with no
  observations, entries are presented in the order they were added.
- **O12 — Unplaced stay together, in entry order.** Unplaced entries form one
  contiguous group, in the order they were added.

## D. Learning

Derived from T4, T3 and F7–F8.

- **O13 — One trip is enough.** After a single trip at a store in which
  entries were ticked in an order x₁…xₙ with no ties, a list of those same
  items is presented at that store in the order x₁…xₙ.
- **O14 — Consistent history is honoured.** Where the history at a store is
  consistent and every total order agreeing with it places *a* before *b*,
  *a* is presented before *b* at that store.
- **O15 — Ties teach nothing.** Permuting the ticks inside any tie window
  leaves every presentation unchanged.
- **O16 — Stores are independent.** No observation made at one store changes
  the presentation at another. *(Will need restating as "own-store evidence
  always dominates" if chain priors are adopted, H2.)*
- **O17 — A rearrangement is absorbed.** If a store's history agrees with one
  order and is followed by K successive trips agreeing with a different order,
  each containing both *a* and *b*, then *a* and *b* are presented in the new
  order. **K [set at freeze]; proposed ≤ 3.**
- **O18 — It gets better.** Over the reference simulation suite (tier 5 §3),
  mean *disorder* — the number of entry pairs presented in the opposite order
  to that walked — is, for every trip number n ≥ 1, no greater than paper
  order would give, and falls to within a stated margin of the suite's noise
  floor by a stated n. **Margin and n [set at freeze].**
- **O19 — Deterministic.** The presentation is a function of the list, the
  observation history and the selected store, and of nothing else. Not the
  clock, not chance.

## E. The record

Derived from T5.

- **O20 — Append-only.** No user action alters or removes a recorded
  observation. A correction is a further observation.
- **O21 — Rebuildable.** Discarding everything derived and rebuilding from the
  observation history yields the identical presentation for every store.
- **O22 — Round trip.** Exporting, then importing into an empty install,
  yields the identical list and the identical presentation for every store.

## F. The device

Derived from T6.

- **O23 — No network.** The installed app holds no network permission. This is
  checkable from the package alone, and makes "location never leaves the
  phone" true by construction.
- **O24 — Fully offline.** Every function in tier 2 works with all radios off.

---

## What is deliberately absent

Predicates about internals — the shape of the stored model, the cost of an
update, how cycles in contradictory evidence are broken — cannot be written
before tier 4. They are introduced there, tested in tier 5, and are **not**
frozen.

There is no oracle for "the order is good in a real shop". That is not
checkable from artefacts. It is what the field trial is for.
