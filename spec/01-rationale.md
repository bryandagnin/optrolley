# Tier 1 — Rationale, Core Tenets, Steering

**Date:** 2026-10-09
**Last updated:** 2026-11-15

---

## Status: ACCEPTED FOR THE FIRST BUILD

Written while scaffolding, from a one-line brief ("android shopping list app
with self-optimising auto-routing") and two answers about scope. The owner
accepted it as drafted by going ahead with the build (2026-10-18); the field
trial is where it gets tested. §4 marks which decisions were stated, which
were inferred, and which were accepted that way. Barcode scanning (D14–D15)
was asked for by the owner ahead of the trial and revises §3. T5, T6, D10
and D18–D21 are the owner's direction after the first weeks of use.

---

## 1. Why this exists

A shopping list written in the order things came to mind makes you zigzag.
You pass the milk, reach the bottom of the list, and walk back for it.

The usual fix is to sort the list by aisle, which means telling an app where
everything is. That is a chore nobody keeps up: it has to be done per shop, it
goes stale when the shop is rearranged, and it is worth nothing in the shop
down the road.

But you already tell the app where everything is, every time you shop. **The
order you tick things off is the order you walked.** A list that remembers that
order can present itself that way next time, and get it more right each trip.
No aisle numbers, no categories, no map.

## 1.1 What "auto-routing" means here

**Routing is the order of the list inside one shop.** It is not navigation and
it is not planning a trip between shops.

Two things about shops shape everything below:

- **Every shop is laid out differently.** A route is learned per shop and is
  worthless in another one.
- **Not everything can be bought everywhere.** Some items exist only at one
  shop or one chain. A list shown in a shop has to be about *that* shop: what
  can be got here, in the order it will be met — without losing sight of what
  still has to be got elsewhere.

So the app must know which shop it is in. Being told by hand is enough to be
useful; working it out from location is a convenience layered on top (D4).

## 2. Core tenets

Ordered. When two conflict, the lower number wins.

### T1. Nothing on the list is ever lost

Routing may reorder the list and split it into sections. It may never drop an
entry, and never put one where it cannot be seen and ticked.

*Why:* the one job of a shopping list is that you do not forget things. A
routing error costs a few steps. A hidden entry costs the trip.

### T2. The list holds still while you shop

During a trip the order does not change on its own. Ticking an entry does not
shuffle the rest. Learning takes effect on the *next* trip.

*Why:* the list is read in glances, one-handed, pushing a trolley. An order
that moves under the thumb is worse than a bad order that stays put.

### T3. Never worse than paper

With nothing learned, the app is a plain list in the order things were added.
Routing has to earn every departure from that.

*Why:* the first trip in any shop is a cold start, and there will always be
new shops and new items. If cold start is unpleasant, the app is abandoned
before it has learned anything.

### T4. Learning is passive

Everything the app knows about a shop it learned from ordinary use. It never
asks for an aisle, a category or a layout. Explicit corrections are welcome
and are never required.

*Why:* setup tax is what killed the sort-by-aisle approach. An optimiser that
needs feeding is a second chore.

### T5. Trips are the record; the model is derived

Each trip, once ended, is kept as it was walked: which shop, what was ticked
and in what order. Trips are appended and never rewritten. What the app
believes about a shop's layout is computed from them and can be thrown away
and rebuilt at any time.

Everything else the app holds, the list, the items and their names, the
stores, which code means which item, is its current state. It is kept as
what it is now and changed in place.

*Why:* the routing method will change; the history of real trips is the
irreplaceable part. A better method should improve old shops retroactively,
and a rebuildable model is one that can be tested by replaying trips. The
rest is not evidence of anything, and treating it as a log to be replayed
made a name impossible to correct and every action slower than the last.

### T6. Works with no connection

No account, and no server of its own. Everything done in a shop works with
no signal, because supermarkets are where signal is worst. A connection may
be used at home for a convenience, and when it is missing or fails nothing
else is any different.

The list and the trips are never sent anywhere.

*Why:* a list that needs a connection fails in exactly the place it is used.

### Where they bite

- **T1 over T4.** Passively inferring "this shop doesn't stock that" would
  move entries out of the way on a guess. T1 forbids it: only something the
  user explicitly said may move an entry out of the main run.
- **T2 over self-optimisation.** Re-sorting as soon as a tick contradicts the
  model would be more optimal and is forbidden.
- **T3 over cleverness.** An item never bought in this shop has no known
  position. It is shown as such, not slotted in on a hunch.
- **T6 over the library.** Looking a code up needs the network (D20), so it
  is never in the way of adding, ticking or scanning: those finish whether
  or not an answer ever comes.

## 3. Steering: what this is not

- **Not navigation.** No store maps, no indoor positioning, no "turn left".
- **Not a trip planner.** It does not decide which shops to visit or in what
  order. (Deferred, not rejected — see open questions H3.)
- **Not a price tool.** No prices, no comparison, no offers.
- **Not a pantry or recipe manager.**
- **Not shared**, for now. One person, one phone (H4).
- **Not a product database.** Items are whatever the user named, and the
  list shows only those names. A barcode is the user's own shortcut to a
  name they chose (D14). A product's catalogue name and picture appear in
  the item library alone, to tell one code from another (D20).
- **Not growing much further.** The item library closes out barcodes. Beyond
  the ideas already in the open-questions register, no sizable feature is
  planned.

## 4. Decisions

| # | Decision | Source |
|---|----------|--------|
| D1 | The project is called `optrolley-autoroute` (**opt**imal trolley). | stated |
| D2 | "Auto-routing" is the in-shop order of the list, learned from tick-off order. | inferred; accepted |
| D3 | The app is store-aware: routes are per shop, and availability is per shop or chain. | stated |
| D4 | The current shop is chosen by hand first. Automatic detection (e.g. geofence) is a later convenience and may only ever *supply* that same choice. | stated |
| D5 | Single user, single device, no account and no server of its own. | accepted |
| D6 | Native Android in Kotlin; routing logic in a module with no Android dependency. Reasoning and counter-case in tier 4 §1. | accepted |
| D7 | Planning a trip across several shops is out of scope for now. | accepted |
| D8 | Unplaced entries are shown ahead of the run (was Q2). | accepted |
| D9 | One list (was Q4). | accepted |
| D10 | An item is a thing of its own with a name, as a store is; it is not its name. A typed name finds its item by matching ignoring case and surrounding whitespace, nothing cleverer: "trim milk" is not "milk" (was Q5). Renaming an item corrects its name and keeps everything learned about it. Renaming onto a name already in use joins the two items. Splitting one item into several is done by making new items, which start with nothing learned. A name no item has any more, typed again, is a new item. | stated |
| D11 | A rearrangement is absorbed within 3 trips (was Q3; values in tier 3). | accepted |
| D12 | The first build ships every candidate routing method, selectable, and scores each against real trips. Choosing one waits for real data. | stated |
| D13 | Application id `dev.optrolley.autoroute`, display name "Optrolley", minimum Android 10 (API 29) (was Q6). | accepted |
| D14 | Camera barcode scanning adds items, and in the shop can tick them, as a shortcut to the user's own names (was H7). A code's first scan asks for a name; no catalogue's name is ever used for an item. Many codes may mean one item, and the name chosen is the level at which the user will take a substitute ("sugar" covers every brand of sugar). Routing learns by item (D10), not by code. The code a scan read is kept with what the scan did, so per-product use stays derivable later. | stated |
| D15 | The app may hold the camera permission. It is asked for at the first scan, never required, and no image from the camera is kept. It may use the network for the library's lookups and nothing else (D20, T6). | stated |
| D16 | Each Run entry shows a confidence level, one rule for every routing method: how well its presented place agrees with the user's last few walks of that store. It can fall as well as rise, so a moved item is not shown as settled. | stated |
| D18 | Learning is redone when a trip ends, not at each action: the list holds still during a trip (T2), so nothing learned can change what is shown until then. An action costs the same however many trips there have been. | stated |
| D19 | Every action is also written to a journal, as it was before trips were the record: to study how the app is really used during the field trial (§5), and for export. The app does not read it to run. It may be cut short or dropped once the trial has answered its questions. | accepted |
| D20 | An item library, used at home: every item with the codes that mean it. There an item is renamed (D10) and a code is unlinked from its item, after which it is a code never seen. Each code shows its product's name and a small picture, looked up by its number when there is a connection and kept on the phone. A lookup sends the code and nothing else. Without one the library works the same and shows the number. | stated |
| D21 | The trips kept are all of them. No trip is dropped for being old unless measurement on the phone shows it is needed; a rarely bought item is placed by old trips alone. | accepted |
| D17 | Scanning happens inline on the list, with a small corner viewfinder the user turns on, not on a screen of its own. | stated |

## 5. Assumptions not yet tested

The premise rests on these. None has been checked against real shopping
trips; each is a `[hypothesis]` until the field trial in tier 4 §9.

- **Tick order tracks walking order.** People tick as they pick up. If they
  mostly tick in batches at the checkout, or at home afterwards, there is
  little to learn from.
- **People walk a given shop the same way each time.** Entering by the other
  door reverses the route (H6).
- **Layouts are stable for months**, and change in pieces rather than all at
  once.
- **Order within an aisle is noise.** Only the order *between* areas of the
  shop is stable enough to learn.
