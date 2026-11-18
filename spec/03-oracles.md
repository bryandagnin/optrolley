# Tier 3 — Predicates and Oracles

**Date:** 2026-10-09
**Last updated:** 2026-11-18

---

## Status: FROZEN — 2026-10-18

Frozen at the owner's go-ahead to build, with tiers 1–2 accepted as drafted.
Before freezing, the set was re-derived from tiers 1–2 alone by an agent that
had not read tier 4, and the two sets reconciled: O25–O40 come from that
re-derivation, as do the values below.

They may not be weakened to accommodate a design. An oracle that cannot be
satisfied is a finding about tier 1 or 2 and is raised as one. Adding oracles
is fine. Loosening one needs explicit agreement and a recorded reason.

**Revised 2026-11-15, with the owner's agreement**, to follow tier 1's new
T5, T6 and D10. The reasons, as the rule above asks:

- *Section E and O19* spoke of an observation history as the record. The
  record is now the trips (T5); they say the same things of it.
- *O23* forbade any network permission. T6 is about working with no
  connection, not about holding none, and the library looks codes up
  (D20). O24 and O60–O61 keep what O23 was protecting.
- *The item-match definition, O36 and O43* took an item to be its name. An
  item is now a thing with a name (D10); O55–O58 say what follows.

Nothing else is loosened. Where an oracle says "history" it means the
record together with the app's current state.

**Hard and soft.** Every oracle except O13–O18 is hard: every build satisfies
it. O13–O18 judge a routing method; the first build ships several methods side
by side (F11) and reports how each fares on them.

---

## Definitions

- **Record**: the trips that have ended at a store, oldest first, each with
  its store, when it ended and its trip order.
- **State**: everything else the app holds: the list, the items and their
  names, stores and chains, code links, standing "not here" statements, the
  selected store, the trip in progress and the routing method chosen.
- **Presentation**: the sections and the order of entries shown for a given
  state and record.
- **Tie window**: **5 seconds**, chained — a *tie group* is a maximal run of
  ticks in which each follows the previous by less than 5 s. Ticks in one
  group are unordered relative to each other.
- **Idle timeout**: **2 hours** from the trip's last action. The app ends
  the trip when it notices, as of when the two hours ran out.
- **Trip order**: the order of a trip's final ticks, as a sequence of tie
  groups.
- **Consistent history**: the trips at a store are consistent if some single
  total order of items agrees with every trip's order.
- **Placed**: an item is placed at a store once some ended trip there ticked
  it in a different tie group from at least one other item. Placement is
  never lost.
- **Item match**: a typed name means the item whose name equals it after
  trimming surrounding whitespace and ignoring case. If there is none, it
  names a new item.

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
  trips on record, entries are presented in the order they were added.
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
- **O19 — Deterministic.** The presentation is a function of the state and
  the record, and of nothing else. Not the clock, not chance, not the
  journal, not anything fetched. (The routing method in use is part of the
  state, F11.)

## E. The record

Derived from T5.

- **O20 — Append-only.** No user action alters or removes a trip on record.
  Renaming or joining items changes what the items in it are called, not
  what was walked.
- **O21 — Rebuildable.** Discarding everything learned and rebuilding from
  the record yields the identical presentation and confidence for every
  store.
- **O22 — Round trip.** Exporting, then importing into an empty install,
  yields the identical list and the identical presentation for every store.
- **O59 — The journal is not needed.** With the journal emptied, every
  presentation is identical and every function in tier 2 works as before.
  Only what export writes of past actions differs.
- **O62 — Nothing is left behind.** An install holding a history from before
  trips were the record gives, after the update, the identical list, the
  identical presentation for every store, and every code adding what it
  added before. So does importing a file such a build exported.

## F. The device

Derived from T6.

- **O23 — Two permissions.** The installed app holds the camera and the
  internet permissions and no other. This is checkable from the package
  alone. It holds no location permission, so where the user is cannot leave
  the phone.
- **O24 — Fully offline.** With all radios off, every function in tier 2
  works, except that the library shows a code's number where its product's
  name and picture would be.
- **O60 — A look-up is inert.** Whether a look-up succeeds, fails, is slow or
  never happens changes no entry, section, order, confidence, link or item
  name. It changes only what the library shows beside a code.
- **O61 — Only the code is sent.** The one thing the app sends anywhere is
  the number of a code, to look it up. Never the list, an item's name, a
  store or a trip.
- **O63 — Nothing is sent without agreement.** Until the user has agreed to
  look-ups, and after they withdraw it, the app sends nothing at all,
  whatever is scanned or opened.

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
- **O29 — Learning waits for the next trip.** Two states that differ only in
  what has been ticked and unticked in the trip in progress give the same
  sections and order (ticked marks aside).
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
- **O35 — "Not here" is reversible.** Undoing it gives the presentation
  there would be had it never been said.
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

## H. Scanning

Derived from F12, D14 and D15. Added 2026-10-19 with the owner's go-ahead
for barcode scanning; written from tiers 1–2 before any design.

- **O41 — A scan is a typed name.** Scanning a linked code gives the same
  list and the same presentation, at every store, as typing the name of the
  item it is linked to at that moment.
- **O42 — No name, no entry.** Scanning a code that is not linked changes
  nothing on the list. Only naming it does.
- **O43 — The latest link is the meaning.** A code adds the item of its most
  recent link, unless it has been unlinked since (O58). Linking one code
  never changes what another code adds, and any number of codes may add the
  same item.
- **O44 — Links move nothing.** Linking or relinking a code changes no
  section or order at any store, and no entry on the list.
- **O45 — Links survive.** After a rebuild (O21), and after export and import
  into an empty install (O22), every code adds what it added before.
- **O46 — The camera is optional.** With the camera permission denied, or no
  camera at all, every function in tier 2 except scanning works.
- **O47 — No pictures.** No image from the camera is kept, and nothing
  written to the export is an image; a scan contributes only the code it
  read. A product's picture in the library is a fetched copy: it can be
  discarded and fetched again, and is not exported.
- **O48 — A scan to tick is a tap.** Scanning a linked code to tick gives the
  same list, the same presentation at every store, and the same learning
  once the trip ends, as tapping that item's tick at that moment — or, if
  the item is not on the list, as typing its name and then tapping its tick.
  Within the trip it moves nothing else (O7, O28).
- **O49 — A scan never unticks.** Scanning to tick an item already ticked
  changes nothing.

## I. Confidence

Derived from F5 and D16. Added 2026-10-22 with the owner's agreement.

- **Witness**: an ended trip at the store that ticked the item in a
  different tie group from at least one other item now in the Run. Its
  **last witnesses** are its three most recent, or as many as there are.
- **Agreement** of a witness: of the pairs it ticked in different tie
  groups between the item and another item now in the Run, the share
  ticked in the order presented. A trip that ran against the presented order
  for more than half of all such pairs among the Run's items was walked from
  the other door, and is read in mirror, as learning reads it (H6).
- **Confidence** of a Run entry: **settled** if it has three last witnesses
  and their mean agreement is at least **0.8**; **tentative** if it has one
  or none, or their mean agreement is below **0.6**; otherwise **settling**.
  The mean is the plain mean of each witness's share, compared exactly.

- **O50 — Run only.** Every entry in the Run has exactly one confidence;
  entries in other sections, and with no store selected, have none.
- **O51 — As defined.** Each Run entry's confidence is the one the
  definition above gives from the record and the presentation.
- **O52 — One rule for every method.** Two routing methods that present the
  same run at a store give every entry in it the same confidence.
- **O53 — It holds still.** Confidence is a function of the state and the
  record (O19). Within a trip it is unchanged by ticking, unticking,
  editing details, or anything else that leaves the same entries in the Run
  (as O29); it is unchanged by permuting ticks within a tie window (as
  O15), and at one store by anything done at another (as O16). Adding,
  removing or moving an entry out of the Run mid-trip may change others',
  since agreement is counted against the Run as it stands.
- **O54 — It can fall.** If every trip at a store has walked the same four
  or more items in one order, and the next walks the first of them last, or
  the last first, with the rest in order, that item is not settled after
  it, under every routing method that ships. (With three or fewer, such a
  trip runs against most pairs and is read as walked from the other door.)

## J. Items and the library

Derived from D10, F1 and F13. Added 2026-11-15; written from tiers 1–2
before any design.

- **O55 — Renaming an item moves nothing.** Renaming an item to a name no
  item has leaves every section, order and confidence at every store as it
  was, and its entry's quantity, note and tick. Every code that added it
  still adds it, under the new name. It is the same whether done from the
  entry or from the library.
- **O56 — The old name starts cold.** After a rename, the old name typed
  again is a new item: placed at no store, in no store's Elsewhere, and
  added by no code.
- **O57 — Joining keeps both.** Renaming an item onto another item's name
  leaves one item under that name. Every trip that ticked either counts for
  it, every "not here" said of either stands for it, and every code linked
  to either adds it. If both were on the list one entry is left, in the
  earlier one's place in the order they were added, and ticked only if both
  were, so that the end of a trip cannot take away something not yet got
  (T1). No other entry changes section or order within a trip (O7).
- **O58 — Unlinking forgets the link and nothing else.** A code unlinked in
  the library is as a code never linked (O42): its next scan adds nothing
  until it is named. No entry, section or order changes, the item it meant
  is as it was, and no other code is affected.

---

## What is deliberately absent

Predicates about internals — the shape of the stored model, the cost of an
update, how cycles in contradictory evidence are broken — cannot be written
before tier 4. They are introduced there, tested in tier 5, and are **not**
frozen.

There is no oracle for "the order is good in a real shop". That is not
checkable from artefacts. It is what the field trial is for.
