# Tier 3 — Predicates and Oracles

**Date:** 2026-10-09
**Last updated:** 2026-10-18

---

## Status: FROZEN — 2026-10-18

Frozen at the owner's go-ahead to build, with tiers 1–2 accepted as drafted.
Before freezing, the set was re-derived from tiers 1–2 alone by an agent that
had not read tier 4, and the two sets reconciled: O25–O40 come from that
re-derivation, as do the values below.

They may not be weakened to accommodate a design. An oracle that cannot be
satisfied is a finding about tier 1 or 2 and is raised as one. Adding oracles
is fine. Loosening one needs explicit agreement and a recorded reason.

**Hard and soft.** Every oracle except O13–O18 is hard: every build satisfies
it. O13–O18 judge a routing method; the first build ships several methods side
by side (F11) and reports how each fares on them.

---

## Definitions

- **Presentation**: the sections and the order of entries shown for a given
  list, observation history and store selection.
- **Tie window**: **5 seconds**, chained — a *tie group* is a maximal run of
  ticks in which each follows the previous by less than 5 s. Ticks in one
  group are unordered relative to each other.
- **Idle timeout**: **2 hours** from the trip's last observation. The app
  records the end when it notices, so the end is part of the history.
- **Trip order**: the order of a trip's final ticks, as a sequence of tie
  groups.
- **Consistent history**: the trips at a store are consistent if some single
  total order of items agrees with every trip's order.
- **Placed**: an item is placed at a store once some ended trip there ticked
  it in a different tie group from at least one other item. Placement is
  never lost.
- **Item match**: two typed names are the same item if they are equal after
  trimming surrounding whitespace and ignoring case.

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
  order. **K = 3**, whatever the length of the older history (up to 100
  trips).
- **O18 — It gets better.** Over the reference simulation suite (tier 5 §3),
  mean *disorder* — the number of entry pairs presented in the opposite order
  to that walked — is, for every trip number n ≥ 1, no greater than paper
  order would give; and by trip **n = 5**, over pairs of items both already
  placed at the store, it is within **5% of those pairs** of the suite's
  noise floor: the disorder of the shop's true layout order against the same
  walks, over the same pairs. A scenario with a rearrangement is measured
  again from the rearrangement, with the same n and margin.

  *Amended 2026-10-18, with the owner's agreement.* As first frozen, the
  margin counted all pairs, and the simulator showed no router could meet it:
  items never bought at a store must be shown unplaced, ahead of the run
  (T3, O27), so even the true layout sat about 24% of pairs above the floor
  at n = 5. Counting only placed pairs judges what a router controls.
- **O19 — Deterministic.** The presentation is a function of the list, the
  observation history and the selected store, and of nothing else. Not the
  clock, not chance. (The routing method in use is itself chosen by a
  recorded observation, F11.)

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

## G. From the independent re-derivation

- **O25 — Unknown is not unavailable.** An item never ticked at a store, with
  no standing "not here" for that store or its chain, is never in Elsewhere
  there.
- **O26 — Section order.** With a store selected, the non-empty sections are
  presented in the order Unplaced, Run, Elsewhere, and every entry is in
  exactly one. With none selected there is one section.
- **O27 — Placed means Run.** At a store, an entry not in Elsewhere is in the
  Run if its item is placed there, and in Unplaced otherwise.
- **O28 — A tick stays put.** Ticking or unticking changes only that entry's
  ticked mark, not its position or section, until the trip ends. Ticked
  entries remain visible until then.
- **O29 — Learning waits for the next trip.** Two histories that differ only
  inside the trip in progress give the same sections and order (ticked marks
  aside).
- **O30 — A mid-trip addition lands where it belongs.** An entry added during
  a trip is presented where it would be had it been on the list when the
  trip began.
- **O31 — Undo and redo count once.** A tick, untick and re-tick within a
  trip teaches the same as the final tick alone. A tick undone and not redone
  teaches nothing.
- **O32 — No store, no learning.** Ticks made with no store selected change
  no store's presentation.
- **O33 — Trip end.** A trip ends when the user finishes it, selects another
  store or none, or the idle timeout passes. Then its ticked entries leave
  the list, and unticked entries stay with quantity and note intact. A
  session with no store selected ends the same way.
- **O34 — Chain scope.** "Not here" said of a chain puts the entry in
  Elsewhere at every store of that chain and at no other.
- **O35 — "Not here" is reversible.** Undoing it gives the presentation the
  history would give had it never been said. The history keeps both.
- **O36 — Items carry over.** Removing an entry and adding a matching name
  again gives the new entry the old item's placement.
- **O37 — Details move nothing.** Editing an entry's quantity or note changes
  no section or order at any store.
- **O38 — Renaming moves nothing.** Renaming a store or chain leaves its
  presentation unchanged; a new store under the old name starts cold.
- **O39 — Nothing is demanded.** Creating a store asks for a name and,
  optionally, a chain — nothing else. No action is ever refused for want of
  an aisle, category or position.
- **O40 — The store is always shown.** Wherever the list is shown, the
  selected store (or none) is shown with it.

Elsewhere follows the most recent explicit statement (O5), whatever its
scope; a purchase at a store is such a statement for that store (O6). It
takes effect from the next trip, since O28 holds the entry in place; a
"not here" said mid-trip moves the entry at once, being the user's own act.

---

## What is deliberately absent

Predicates about internals — the shape of the stored model, the cost of an
update, how cycles in contradictory evidence are broken — cannot be written
before tier 4. They are introduced there, tested in tier 5, and are **not**
frozen.

There is no oracle for "the order is good in a real shop". That is not
checkable from artefacts. It is what the field trial is for.
