# Paths

> A path is a branch, not a life. Lives happen along it, one after another.

## The problem this solves

A player who wants a second existence in this world — somewhere else on the map, near a different
person, or simply starting from nothing without giving up what they have — has exactly one option on
an ordinary server: buy a second Microsoft account. That answer costs money, produces a second
player the server has to treat as a stranger, and is available only to people who can pay for it.

A **path** is that second existence, held properly. Each one carries everything that makes a player
a player:

| Kept per path | Kept per account |
|---|---|
| Position (world and coordinates, facing) | Advancements |
| Inventory, armour, off hand, held slot | Statistics |
| Ender chest | Unlocked recipes |
| Experience level, bar and total | Contacts, ignores, `/msg` history ([social.md](social.md)) |
| Health, hunger, saturation, exhaustion | Bans, whitelist, permissions |
| Air, fire, fall distance, potion effects | |
| Samsara's own record: life id, first spawn, last exile, last death, death count, open End journey, journal | |

The right-hand column is one judgement made consistently. Advancements and statistics already survive
death, which is the strongest statement this server makes about what a life owns — so they belong to
the person, not to the existence. Social records are kept apart for the same reason and were already
in a separate directory before paths existed.

## A path is not a life

This is the distinction everything else follows from, and the one players get wrong.

- A **life** ends when you die. Your inventory drops, your ender chest drops, and you wake hundreds
  of thousands of blocks away with nothing. That has not changed and is not touched by this feature.
- A **path** survives that. It keeps its name, and it receives the new life.

So a player who dies on `Original` wakes up on `Original`. There is no `Original-2`. Nothing in the
path system runs on death — `DeathListener` and `RespawnListener` are exactly as they were, and the
death screen is vanilla's. A path is the branch; the incarnations happen along it.

Everything a player is told leans on this, because the natural reading of "five existences" on a
hardcore server is "five deaths and you're out". Somebody who believes that hoards their paths and
never begins one.

## Commands

```
/path                          which paths you hold, and which you are walking
/path switch <name>            leave this existence and take up another
/path new <name>               begin one, alone
/path new <name> <player...>   ask those players to begin one alongside you
/path accept <player> [name]   agree to a shared beginning
/path decline [player]         refuse one — or withdraw your own
/path rename <old> <new>       rename one
/path abandon <name> confirm   destroy one, and drop what it was carrying
```

No permission is needed for any of them. `/paths` is an alias. Operators can read — but not change —
anybody's list with `/samsara paths [player]`.

Names are 1–16 characters of letters, digits, underscores and hyphens, compared without regard to
case, and may not be one of the words above. Case-insensitivity is the important one: nobody should
be able to lose an existence to the shift key.

## Beginning together

This is the one place on the server where two players are deliberately put down in the same square
metre, so it is built entirely around consent.

```
Steve:      /path new Together Alex Herobrine
Alex:       /path accept Steve
Herobrine:  /path accept Steve Second      ← already has a path called Together
```

Nothing happens until the last answer arrives. No path is created, nobody is moved, and no existing
path is touched — the offer is a question, and one refusal or one disconnection ends it for
everybody rather than quietly shrinking it. It lapses on its own after
`paths.invitationTimeoutSeconds`, and a player may only be deciding about one at a time.

When the last person agrees, **one** location is searched for — not one each — and all of them are
put down there together, each in a brand new path of their own with nothing in it. Their existing
paths are untouched: beginning again together does not mean giving up what you already have. From
that moment the paths are entirely separate, and `/path` remembers who each of them began alongside.

## Abandoning

`/path abandon <name> confirm` destroys a path for good and frees its slot. It is settled like a
death, because materially it is one:

- Its inventory **and** its ender chest fall into the world at the place that existence was standing,
  where anybody at all can find them.
- Its experience falls with them, capped the way vanilla caps a death drop.
- Its history goes with it.

Nothing comes back with the player. A way to abandon an existence and keep its pockets would be a
free courier service for shulker boxes, which is why the order of operations is chosen against
duplication rather than against inconvenience: **the path is removed from the index and its file
deleted before anything is dropped.** A server that dies in the middle of that loses the belongings,
which is what dying is. It can never produce them twice.

Two things are refused outright: abandoning the path you are standing in, and abandoning your last
one. Between them they guarantee there is always somewhere left for you to be.

## How a switch cannot duplicate anything

A switch has to survive the server being killed at any instant, and it has to survive the player
being killed at any instant. Neither may leave two copies of an item.

The invariant is one sentence: **the path a player is walking never has a dormant file.** The active
path *is* the player — its Samsara record is the ordinary `playerdata/<uuid>.json` that the exile,
travel and journal systems already act on, and its Minecraft state is whatever the server has in
front of it. Only dormant paths are files.

```
1.  remember where the outgoing existence is standing        (nothing written)
2.  teleport the player to the incoming existence's place     (may take seconds)
    ├── refused, or they died, or they logged out
    │   └── send them back. Nothing was written; nothing changed.
    └── arrived
        3.  read the outgoing existence off the live player   ← after the move, not before
        4.  write the outgoing existence to its own file      ← active path now HAS a file
        5.  write the incoming record to playerdata/
        6.  apply the incoming Minecraft state to the player
        7.  write the index: the incoming path is now active  ← commit
        8.  delete the incoming path's file                   ← active path has no file again
```

**Step 3 is why nothing duplicates.** The teleport crosses terrain that may never have been
generated, and in the second that takes the player can drop something, be hit, eat, or die. Reading
them beforehand would store an existence still holding an item that is by then lying on the ground —
the same item in two places, one of them recoverable by walking back into that path. Reading them
afterwards, when the move is finished and nothing more can happen, cannot. Only the *position* comes
from before, because the position is the one thing the move itself changed.

**Steps 4 to 8 are why a crash cannot.** At every instant in that window the active path has a file,
and `PathService.recoverInterruptedSwitch` reads that on the next join as "the switch did not
finish", restores the player from that file and deletes it. Whichever side of step 7 the crash landed
on, the answer is the same and it is correct: the other path still has its own file and is still
dormant, so the player has simply not moved.

Beginning a path takes the same route, with a fresh empty existence in place of a stored one. It
joins the index in the same write that makes it active, so a crash before that leaves an account that
never heard of it.

Meanwhile a player may not switch while dead, while an exile is still being searched for, while
already midway through a move, or while between dimensions. The first is the one that matters: a
player on the respawn screen is between two lives and Samsara is already deciding where the next one
begins.

## What the server says

There are three of these events and vanilla only knows about two. A player can connect, can
disconnect, or can do neither and simply go to live somewhere else as somebody else — and it is that
third one the game has no words for at all.

```yaml
paths:
  messages:
    join: ""                                         # vanilla's, by default
    quit: ""                                         # vanilla's, by default
    departure: "%player%'s incarnation here ends."   # where they were
    arrival: "%player% enters an incarnation."       # where they now are
```

`%player%` and `%path%` are substituted; `&` colour codes work. All four go through the same
proximity scoping as everything else ([social.md](social.md)), so stepping out of an existence is news
exactly where it happened — to the people who were standing there, and to that player's contacts.

**`join` and `quit` ship empty**, which hands those two back to vanilla. Vanilla writes them as
*translatable* components, so a client set to French reads them in French; a configured string is a
string, in whatever language the operator wrote it. Connecting and disconnecting are the two things
the game already announces correctly, and trading every player's own language for a turn of phrase is
not a default worth taking. Fill them in if you would rather have the wording.

`departure` and `arrival` treat an empty line as silence instead, because there is nothing to fall
back to — nothing in the game announces a player becoming somebody else, so the choice there is these
words or none.

Note that `%path%` is a player's own name for a private thing. The shipped defaults leave it out of
every broadcast on purpose.

## On disk

```
plugins/Samsara/
├── playerdata/<uuid>.json          the path being walked — unchanged, and unaware of any of this
└── paths/<uuid>/
    ├── index.json                  which paths exist, their names, and which is active
    └── <pathId>.json               one file per dormant path, holding it whole
```

`index.json`:

```json
{
  "dataVersion": 1,
  "activePathId": "5f0d1c8e-...",
  "paths": [
    { "id": "5f0d1c8e-...", "name": "Original", "createdAt": 1785000000000 },
    { "id": "8a6f2b31-...", "name": "Together", "createdAt": 1786000000000,
      "companions": ["Alex", "Herobrine"] }
  ]
}
```

A dormant path's file holds a `record` — the same shape as `playerdata/<uuid>.json`, written by the
same codec, so a field added to a player record is a field dormant paths keep too — and a `state`,
which is the Minecraft half. Items in it are Minecraft's own NBT, base64'd, via
`ItemStack#serializeAsBytes`: a stack put away on one version comes back on the next with its
enchantments, damage, custom name and contents intact, run through the server's own data fixer if the
format moved underneath it.

Damaged files are set aside as `.corrupt` rather than overwritten, exactly as player records are.
Losing a path is bad; overwriting the only evidence that it existed is worse.

## Migration

There is none, and that is the point. Every player who existed before paths did is walking their
first one. It is called whatever `paths.defaultName` says, and it already contains their position,
their belongings and their history — because none of those moved. The index is written the first time
they join after the upgrade.

`paths.enabled: false` turns the whole thing off: `/path` refuses, join and leave messages are
vanilla's, and every player has the one existence they are standing in. Nothing is deleted, so
turning it back on restores whatever anybody had.

## Where this goes next

The design deliberately leaves room for it. A path is a persistent branch that receives successive
incarnations, and at the moment "receives an incarnation" means only what Samsara already did on
death — exile far away, with nothing. Anything richer that reincarnation grows into attaches to the
path rather than to the account, and is expected to sit alongside `PathService` rather than inside
it:

- `PlayerPath` already carries a creation time and the companions it began with, and is where a
  per-path lineage of incarnations would hang.
- `PlayerData.beginNewLife()` is the single point where one incarnation ends and the next begins, and
  it is called from exactly one place.
- `IncarnationState` is the whole definition of what an existence *is*. Anything that should survive
  a switch is a field there and nowhere else.
