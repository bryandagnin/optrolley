# Tier 2 — High-Level Functional Specification

**Date:** 2026-10-09
**Last updated:** 2026-11-17

---

## Status: ACCEPTED FOR THE FIRST BUILD

Follows from tier 1 and inherits its uncertainty. Describes what the app does
as the user sees it. How it does it belongs to tier 4.

---

## 1. Vocabulary

| Term | Meaning |
|------|---------|
| **Item** | A thing the user buys, with the name they gave it. Remembered across lists. Its name can be corrected without changing anything else about it (D10). |
| **Entry** | An item's presence on the current list, with an optional quantity and note. |
| **Store** | One physical shop. |
| **Chain** | A named group of stores under one brand. A store belongs to at most one. |
| **Trip** | One continuous visit to one store. |
| **Tick** | Marking an entry as got. |
| **Run** | The entries with a learned position in the selected store, in that order. |
| **Unplaced** | Entries with no learned position in the selected store. |
| **Elsewhere** | Entries the user has said cannot be got at the selected store. |
| **Record** | The trips that have ended, each kept as it was walked: its store, when, and what was ticked in what order. The raw material of everything learned (T5). |
| **Journal** | Every action as it was made, kept for the field trial and for export. The app does not need it to work (D19). |
| **Code** | A barcode the user has scanned. **Linked** once the user has said which item it means. |
| **Library** | Every item and the codes linked to it (F13). |

## 2. The list (F1)

- One list. Add an entry by typing a name, or by scanning a code (F12);
  optionally a quantity and a note.
- Typing suggests items used before.
- An entry can be removed, ticked and unticked, and its quantity and note
  changed.
- Adding a name that matches an existing item reuses that item, so what was
  learned about it carries over. Names match ignoring case and surrounding
  whitespace (D10).
- **Changing an entry's name renames its item**, here and everywhere: it is a
  correction, and what was learned stays with the item. If the new name is
  another item's, the two become one item, with what was learned of both;
  if both were on the list, one entry is left. To have two items where
  there was one, add new ones.
- Adding a name already on the list does not add it twice; it shows the entry
  that is there.

## 3. Stores and chains (F2)

- The user creates a store by naming it, and may assign it to a chain.
- Nothing else is asked for. No address, no layout, no aisles (T4).
- Stores and chains can be renamed. Learned routes follow the store, not the
  name.

## 4. Selecting the store (F3)

- The user picks the current store from their stores, or picks none.
- With no store selected the app is a plain list (T3).
- The selection is always visible while the list is.

## 5. Trips (F4)

- A trip starts without ceremony: at the first tick while a store is selected.
- It ends when the user finishes it, selects a different store or none, or
  leaves it idle long enough.
- When a trip ends, ticked entries leave the list. Unticked entries stay, for
  the next trip or the next store.
- With no store selected, ticking works and nothing is learned. Ticked entries
  leave the list by the same triggers.

## 6. Presentation (F5)

For the selected store the list is shown in sections, in this order:

1. **Unplaced** — in the order they were added. Shown first so they are seen
   before setting off, since the app cannot say when they will be met (D8).
2. **Run** — in learned walking order.
3. **Elsewhere** — still visible, still tickable, clearly set apart.

Ticked entries stay visible, marked as done, until the trip ends.

A store with nothing learned has every entry unplaced, which is exactly the
plain list of T3. Nothing about cold start is a special case.

During a trip the order is frozen (T2). An entry added mid-trip is inserted
where it belongs without moving anything else.

**Confidence.** Each entry in the Run shows how well its place is borne out
by the user's own recent walks of that store: *tentative*, *settling* or
*settled* (D16). It is not shown by colour alone. Like the order, it is
fixed for the length of a trip and changes only from the next one. It says
how sure the run is, never why.

## 7. Availability (F6)

Three states for an item at a store: got here before, said to be not here,
and unknown. **Unknown is not unavailable** — an unknown item is presented
normally.

- **"Not here"** is an action on an entry, alongside tick. It moves the entry
  to Elsewhere for this store and leaves it on the list.
- It can be said of the store or of its whole chain.
- It is reversible, and ticking the item at that store reverses it — from the
  next trip, since a tick moves nothing during this one.
- It needs a store selected; with none there is nowhere for it to apply.
- Nothing is ever moved to Elsewhere by inference (T1). Finishing a trip with
  an entry unticked says nothing about availability.

## 8. Learning (F7)

- When a trip ends, the order its entries were ticked in is what the store
  learns from.
- Entries ticked together in quick succession say nothing about their order
  relative to each other.
- A tick undone and redone counts once, at its final position.
- What was learned takes effect from the next trip in that store.
- What is learned in one store does not affect another. (Whether a chain's
  stores should share a starting guess is deferred, H2.)

## 9. Adaptation (F8)

When a store is rearranged, the run follows within a few trips, with no action
from the user. Old evidence gives way to new.

## 10. Export and import (F9)

- The user can export everything — the list, items, stores, code links,
  the record of trips and the journal — to a file, and import it on a fresh
  install.
- This is the backup and the migration path. There is no other, by T6.
- A file exported by a build from before trips were the record can still be
  imported, and an install from before then keeps everything across the
  update.

## 11. Routing method (F11) — first build only

- Every candidate routing method is built in, and a setting chooses which one
  orders the run (D12). A change takes effect from the next trip.
- A lab view scores every method against each trip already walked: how many
  pairs it would have presented out of the walked order, using only the
  trips before. This is how real data picks the method.
- The choice is part of the app's state, so the presentation stays a
  function of that state and the record.

Once a method is chosen, the setting and the losing methods go.

## 12. Scanning (F12)

A way to add entries faster, especially at home from the empties, and to
tick them off in the shop. It does nothing the user could not have typed or
tapped.

- **Scanning happens on the list itself** (D17). From the add bar the user
  turns the camera on; a small viewfinder sits in the corner beside the add
  bar, never over an entry, and codes are read one after another with a
  buzz for each. The entry a scan adds or ticks is brought into view and
  marked for a moment, and the last scan is shown above the add bar with
  undo. The camera stays on until the user turns it off, finishes the trip
  or leaves the app.
- **A code seen for the first time adds nothing until the user names it.**
  Naming offers the same suggestions as typing, with the list's own entries
  first. The name given links the code to that item.
- **A linked code adds its item exactly as typing its name would**, reusing
  the item and everything learned about it, and says so, with undo. A code
  for an item already on the list adds nothing twice; it says the entry is
  there (F1).
- **Many codes can mean one item.** Which item a code means is the user's
  choice. It can be changed at any later scan, or undone in the library
  (F13), and the latest link is what the code means.
- **Names are where substitutes live.** If any brand of sugar will do, every
  sugar's code is linked to "sugar"; a preferred brand goes in the entry's
  note. The app never decides that two items are alike.
- **The scanner adds or ticks**, by a switch beside it that starts on
  tick while a trip is in progress and on add otherwise. Ticking by scan is
  ticking: the entry is marked as got, as a tap would mark it, and learning
  reads it the same way. An item scanned to tick that is not on the list is
  added and ticked, since it has just been picked up. A scan never unticks,
  and tapping stays the way to tick for anyone who never scans.
- The camera is asked for at the first scan, and only then. Without it,
  everything else works. No image is kept.
- Links survive export and import (F9).

## 13. The item library (F13)

Where items and codes are looked after, at home. Nothing in it is needed to
shop.

- **Every item is listed**, with the codes linked to it, whether or not it
  is on the list.
- **An item can be renamed** there, as from its entry (F1).
- **A code can be unlinked** from its item. It is then a code never seen:
  its next scan asks for a name (F12).
- **Each code shows its product's name and a small picture**, to tell apart
  codes that share an item. They are looked up by the code's number and
  kept on the phone. The look-up sends the number and nothing else. With
  no connection, or no answer, the code shows its number, and nothing else
  in the app is any different.
- **A code is looked up when the user asks, or when it is scanned to add.**
  In the library a code can be looked up, or all those not yet looked up
  can be, one after another. A code scanned to add something, at home, is
  looked up by itself if it never has been. A code scanned to tick, in the
  shop, is not. Nothing waits for an answer.
- **Nothing is looked up until the user turns it on**, in Settings, where
  the option says what will be sent (a code's number) and to whom. While it
  is off nothing is sent, the library shows numbers, and nothing that would
  look a code up is offered. Turning it off again stops all sending; what
  was already fetched stays on the phone until the user clears it.
- A product's name is never used as an item's name, and is never shown on
  the list (D14).

## 14. Settings (F14)

A screen of options, each with its name, a switch, and two or three
sentences beneath saying what turning it on does. Reached from the menu.
Whatever an option enables is hidden while it is off, not shown disabled.
The first option is looking codes up (F13).

## 15. Store detection (F10) — later

- With location permission, the app suggests the nearest known store when
  opened.
- A store's location is learned the same way as its layout: from where the
  user was when they selected it. It is never typed in.
- A suggestion never changes the store during a trip.

Not part of the first build. Staging is in open questions H1.

## 16. Out of scope for this tier

Everything tier 1 §3 excludes, plus: multiple lists (D9), shared lists (H4),
manual drag-to-reorder as a teaching signal (H5), and any presentation of
*why* an entry is where it is (how sure the run is, F5, is not why).
