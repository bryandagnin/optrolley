# Open Questions

**Date:** 2026-10-09
**Last updated:** 2026-11-15

A live register, deliberately outside the spec tiers because it turns over as
answers arrive. Answered items graduate into the decisions table in
[tier 1](./01-rationale.md) §4; only what is still open or deferred stays here.

---

## Open

None. Q1–Q6 were answered at the build go-ahead and are decisions D2–D13
in tier 1; the values they set are in tier 3.

---

## Proposed expansions

Pencilled in by the owner as next features. None is designed: each needs
the changes to tiers 1–3 listed under it, made and agreed before any code.
Barcode scanning (was H7) was brought forward and is now D14–D15 and F12.

### H1. Geofencing to select the shop

**Status:** proposed expansion. Was deferred (D4), staged behind the manual
selector, which stays.

**Stage 1 — suggest on open.** One location reading when the app is opened;
offer the nearest known store. Needs only while-in-use location permission.
Store locations are learned from where the user was when they selected a
store by hand (F10) — never typed in.

**Stage 2 — true geofence.** The app knows it has arrived without being
opened. On current Android this needs background location permission, which
is a separate and much more intrusive grant `[from memory — verify against
current platform docs before designing]`, and the usual geofencing API comes
with Google Play services; the platform's own proximity alerts are the
alternative. Worth it only if stage 1 proves insufficient.

**The owner asked for auto-select, which goes further than F10's
"suggest".** What the frozen spec already says about it:

- **Never mid-trip** (O10, F10). Detection only acts when no trip is in
  progress; arriving at shop B with a trip open at shop A changes nothing.
- **Only ever the manual choice** (D4). An automatic selection is an ordinary
  `Selected` observation, so learning and replay are unaffected. Whether it
  also records that it was automatic (for the lab, and to undo it) is a
  design question.
- **Always visible and one tap to undo** (O40). A wrong auto-selection
  teaches nothing until the user ticks, since a trip starts at the first
  tick — but the first tick would then teach the wrong shop. Suggest-first
  may be the safer default, with auto-select as an opt-in.
- **Location becomes personal data in the history.** Learning a store's
  location means recording where the user stood when selecting it; that
  lands in the export (F9), which `/exports/` already keeps out of the repo.
- **O23 holds** (location is not a network permission), and every other
  oracle must hold with location denied — the app stays fully usable without
  it.

Tiers to change: tier 1 D4 (suggest vs select), tier 2 F10, tier 3 new
oracles (e.g. no automatic selection during a trip; denied location changes
nothing).

### H8. Separate writing the list from shopping it

**Status:** proposed by the owner (2026-10-19); not designed.

Making the list (at home: typing, scanning empties, editing) and walking
the shop (one-handed: glance, tick, "not here") are different jobs with
different screens in mind. One screen serves both today. A split might give
the shop a read-mostly view — bigger rows, nothing that can edit by
accident — and the home a write view with scanning and editing at hand.

Questions before designing:

- **What switches modes?** By hand, or implied: a store selected and a trip
  under way (F4) already says "shopping". An implied switch must still leave
  every entry reachable (O3) and must never move the list (T2).
- **What the shop view can still do.** Adding mid-trip is allowed (O8, O30)
  and probably has to stay one tap away; the forgotten-item case is common.
- **Where scanning goes.** Scanning now happens inline on the list (D17)
  and both adds and ticks (F12), so it belongs to both views; the shop view
  may want the camera on by default.

Tiers to change: tier 2 (the modes, and F12's place in them), tier 3
probably none — the oracles are about the list as presented, not which
screen shows it — unless the shop view hides an action, which O3 would need
to allow.

### H9. Predictive suggestions from purchase cadence

**Status:** proposed by the owner (2026-10-26) for consideration; not
designed.

Most of a list is the same staples coming round again: milk most trips,
coffee every few weeks. The history already says when each item was last
bought and how often, so the app could offer the ones that look due while
the list is being written. A purchase is a tick that survives trip end
(O33), at any store or none: cadence belongs to the household, not the
shop, so unlike routing it is not per store.

What the frozen spec already says about it:

- **Suggest, never add** (T1, T4). An entry put on the list by inference is
  the mirror of one moved off it by inference. A suggestion sits outside the
  list until the user takes it, and taking it is an ordinary add. Ignoring
  suggestions must cost nothing; a dismissal, if recorded at all, is a
  further observation (T5).
- **Cold start is silent** (T3). Nothing is suggested until an item has
  enough purchases to have a cadence; one purchase is a hunch.
- **The clock.** O19 keeps the clock out of the presentation, and recency is
  counted in trips (tier 4). Cadence in trips is pure but rough: a weekly
  shop and a top-up trip count the same. Cadence in days needs "now", which
  observations already carry (wall-clock milliseconds, tier 4) but the
  presentation may not use. Either suggestions sit outside the presentation
  with today's date as an explicit input, so they stay a testable function,
  or they are counted in trips. A decision either way.
- **Not a pantry manager** (tier 1 §3). Cadence must stay a reading of
  purchase history: no stock levels, no "how many left", no use-by dates.
  The steering line probably needs a sentence saying where the boundary is.
- **Typing already suggests items used before** (F1). Ranking those by how
  due they are is the least intrusive first stage and needs no new screen.
  A proactive "due" strip is a second stage, and belongs in the write view
  if H8 happens.

**Testable before it is built.** That staples recur regularly enough to
predict is a `[hypothesis]`, but the field trial's history is the evidence:
replay it and score how often an item flagged as due was in fact added
within the next trip or two.

Tiers to change: tier 1 (decisions on suggest-not-add and on the clock; the
pantry steering line), tier 2 (F1's typing suggestions, and a new F-number
for due items), tier 3 new oracles (e.g. a suggestion never changes the list
by itself; with no purchase history nothing is suggested; ignoring every
suggestion leaves the presentation as it would have been), and O19's scope
if days are used.

### H10. Renaming an item without losing what was learned

**Status:** proposed by the owner (2026-11-15), who has answered the first
questions; not designed.

An item is its name (D10), and the record refers to items by that name. So
correcting a mis-named entry makes it a different item: the route learned
under the old name stays there, and codes linked to the old name still add
the old name.

**Direction: items get an id, and the name becomes a label**, as stores
already work (F2). A rename changes the label and nothing else; routes,
"not here" statements and code links follow the id. Where the ids live is
H12's question.

What the owner has settled:

- **A rename is a correction, never a different thing.** Nobody renames
  milk to eggs, so the app does not ask which was meant (T4).
- **Splitting or joining items is done with new items**, not by
  reinterpreting what was learned: "milk" becomes "full-cream milk" and
  "trim milk", each starting cold. Whether the old item then needs
  retiring, or simply stops being added, is open; if it matters anywhere it
  is in typing suggestions and the library (H11).
- **Renaming onto a name already in use joins the two**, and that is
  acceptable: like products usually sit together. Where a shop keeps one
  apart (the organic maple syrup far from the rest), it stays an item of
  its own under its own name.
- **The old name, typed again later, is a new item** with nothing learned.
  This follows from ids and needs no rule of its own.

Still open: how a typed name finds its item once names are labels (exact
match on the current label, as D10 does now, is the obvious answer), and
how existing histories, which name items by key, are read.

Tiers to change: tier 1 D10 and D14 (what identity is), tier 2 F1 (editing a
name), tier 3 the item-match definition and new oracles (e.g. a rename
leaves every section and order as it was at every store, as O38 says of
stores; after a rename a linked code adds the new name).

### H11. A library of items and their barcodes

**Status:** proposed by the owner (2026-11-15); not designed.

A view of every item with the codes linked to it, used at home, not in the
shop. Most of what it shows is already derived from the history (D14): each
code, its item, and how often each was scanned.

- **A code can be unlinked from its item** there, without scanning the pack
  again. After that it is an unknown code (O42). Parked earlier as a
  possible O55.
- **Each code shows the product's name and a thumbnail**, looked up by its
  number. This is the one place a product name helps: to tell apart several
  codes that share the user's name. The list itself never shows it, since
  the user's name is the item and is the level at which they take a
  substitute (D14).

**The lookup needs the network, and the owner has said what T6 is for:
reliability, not privacy.** The app must never need a connection to do its
job in the shop. That is weaker than what tiers 1 and 3 say now ("offline,
private"; O23, no network permission), so both need restating before this
is designed. What should survive the restating:

- **Nothing in the shop waits on the network.** The list, routing, ticking
  and scanning work with no connection, exactly as now.
- **A lookup that fails or never happens changes nothing** but the missing
  name and picture.
- **Fetched names and pictures are a cache, not the record.** They can be
  thrown away and fetched again, and are not in the export (O47 as it
  stands).
- **Which catalogue** is undecided. Open Food Facts is an open one
  `[from memory — verify its coverage of local products before designing]`.
  A lookup tells that service which codes the household scans; the owner
  accepts that.

Tiers to change: tier 1 T6 and D15 (what the network may be used for),
tier 2 (a new F-number), tier 3 O23 restated, an oracle for unlinking, and
oracles for the points above. The manifest test that enforces O23 changes
with it.

### H12. Keeping the current state, and a data horizon

**Status:** proposed by the owner (2026-11-15); not designed.

Every action today replays the whole history and recomputes the
presentation and confidence from it. That is what T5 asks for in its
plainest form, and its cost grows with the history for as long as the app
is used.

**Keep the derived state as well as the log.** The current state (the
list, stores, items and their ids, code links, what each router has
learned) is saved alongside the history with the position in the history
it reflects. Opening the app loads it and replays only what came after;
each action updates both. T5 holds as long as the saved state is a cache:
it can be deleted and rebuilt from the history, and a test rebuilds it and
compares (O21).

**A horizon** is a point beyond which observations are no longer consulted.
Routing looks back only a few trips (D11; confidence reads the last three
witnesses), so most of an old history is already inert. Two strengths:

- **A horizon on reading.** The log is kept whole and exported whole; only
  the saved state and the recent tail are read. Nothing in tiers 1 to 3
  changes.
- **A horizon on keeping.** Observations older than it are dropped and the
  saved state becomes the start of the record. T5, O20, O21 and O22 would
  need restating, and the export would have to carry the saved state.

What a horizon must not lose: that an item was ever placed at a store
(tier 3, "placement is never lost"), and whatever H9 needs of purchase
dates.

**Measure before building.** The simulator can write a history several
years long; timing it would show whether the cost is in the replay or in
the routers, and at what length it is first felt on the phone.

Tiers to change: tier 4 (storage) for the saved state and a reading
horizon; tier 1 T5 and tier 3 section on the record as well for a keeping
horizon.

---

## Deferred

### H2. Chain as a starting guess for layout

A new branch of a familiar chain could borrow an order from its siblings
instead of starting cold. Rests on same-chain stores being laid out alike
`[hypothesis]`. Would need O16 restated, and provisional placements would
have to be distinguishable from learned ones to stay honest under T3.

**Revisit when** the field trial has history from two stores of one chain to
compare.

### H3. Planning a trip across several stores

Which shops to visit and in what order, given what is only available where.
A different optimiser on different data (D7). The Elsewhere section already
shows what a given shop cannot supply, which is the raw material.

### H4. Shared list

A household list is the most common ask of any shopping app and conflicts
head-on with T6. Would need a sync story that does not involve a server the
owner has to run. Export and import (F9) is the stopgap.

### H5. Drag-to-reorder as a teaching signal

Letting the user correct the run by hand, recorded as an observation.
Permitted by T4 (welcome, never required). Deferred because it is a second
source of evidence to weigh against ticks, and the first should be understood
on its own.

### H6. Walking the shop backwards

**Status:** built into every router candidate — a trip that runs against what
was learned is read as its mirror (tier 4 §5) — but unconfirmed in the field.

Entering by another door reverses the route. One reversed trip would read as
a rearrangement and unlearn a good order. Possible answers: recognise a trip
that runs against the model and learn from its mirror; or offer to flip the
run. Needs field evidence that it happens often enough to matter.
