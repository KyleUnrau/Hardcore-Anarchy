# Social Visibility

> Nearby is temporary. A contact is a relationship. Ignore beats both.

## The problem this solves

Samsara places every life somewhere the last one was not, hundreds of thousands of blocks from
anybody else. The world is enormous and empty and the distance is the entire point.

Vanilla chat undoes all of it. The player list is one room: every message, every death, every
advancement and every join is broadcast to everybody, at any distance, in any dimension. A server
whose premise is that nobody shares a front door hands its players a single shared hallway to stand
in — and worse than the noise is what the noise *tells* them. A name in the death list says somebody
is standing over loot. An advancement says somebody just reached the Nether. None of that should
travel a million blocks.

So chat is local, and there is exactly one way to be heard further: a relationship both players
agreed to.

## The hierarchy

From weakest to strongest:

| | Reach | Consent | Survives |
|---|---|---|---|
| **Nearby** | `social.radius`, same world | none needed | nothing — you walk away and it is over |
| **Automatic contact** | anywhere | both players had `auto` on — the default — and spent real time together | logout, distance, death, exile |
| **Manual contact** | anywhere | asked, and accepted | logout, distance, death, exile |
| **Ignore** | — | the recipient's alone | forever, until they undo it |

`/ignore` overrides everything above it. A private message crosses any distance and is still refused
by it.

## What is scoped

Five things, and they all go through one place — [`SocialAudience`](../src/main/java/dev/unrau/samsara/social/SocialAudience.java):

- ordinary chat, and `/me`
- join and leave messages
- player death messages
- advancement announcements
- tamed animal deaths (locally only — see below)

Nothing here rebuilds a message. Chat has viewers removed from the event Paper already built; joins,
leaves, deaths and advancements have their component lifted off the event and re-sent unchanged. That
keeps every client's own language, the death message's hover text, the advancement's card, and the
chat signature that lets a client report a message. A system that reformatted them as strings would
quietly destroy all of it.

The console is always a recipient. The server log is not a place with a position in it.

### Why one layer and not five listeners

Every one of those events answers the same question — *who should be told?* — and differs only in
three answers: how far it carries, whether the player it is about is one of its own recipients, and
whether a contact receives it at any distance. Those three live in
[`SocialEvent`](../src/main/java/dev/unrau/samsara/social/SocialEvent.java), one line per kind. The
rule itself exists once.

Five copies of that rule would be five chances for one of them to drift, and the drift a player would
notice is the one where somebody they blocked can still be heard.

## Contacts

A contact is **mutual by construction**. Every path that creates one writes both records and every
path that ends one clears both; there is no method in
[`SocialGraph`](../src/main/java/dev/unrau/samsara/social/SocialGraph.java) that can leave A holding
B without B holding A. That is what stops "adding" somebody from being a way to watch them.

```
/contact add <player>       ask
/contact accept <player>    say yes
/contact decline <player>   say no
/contact remove <player>    end it, from either side
/contacts                   who they are
/contact requests           what is outstanding
/contacts auto on|off       let them form on their own
```

Both spellings work everywhere; `/contacts` is an alias of `/contact`.

### What it is not

A contact is **hearing, not finding**. It carries no coordinates, no location tracking, no inventory
access, no teleport, and no way to know which world somebody is in. It is exactly the visibility that
standing next to them would have given, extended across the map.

### Requests

`/contact add` sends a question. Nothing becomes visible until it is answered. A request:

- needs the other player online — a question asked of somebody who is there
- expires after `contacts.requestExpirySeconds`
- is not stored on disk; what survives a restart is the *answer*, and the answer is a contact
- crossed with one going the other way counts as an acceptance, because both of them have said yes

Declining closes the door for `contacts.requestCooldownSeconds`, in **both** directions. Without
that, "no" costs one keystroke to give and nothing at all to ignore.

### Removal, and why proximity does not undo it

Either player can remove a contact, unilaterally and without the other's agreement. Removal:

1. clears the contact from both records, so nothing becomes one-sided;
2. writes a **severance** into both records;
3. throws away whatever proximity progress the pair had accumulated.

The severance is the part that matters. Without it, two players who deliberately stopped knowing each
other and then happened to stand in the same base for twenty minutes would find the relationship
quietly restored — and the second time it happened they would have no idea why. It is cleared by one
of exactly two deliberate acts: a manual request that is accepted, or `/contacts auto allow <player>`.

`auto allow` lifts only the caller's own side. The other player's still stands until they say the
same, because one person does not get to decide for both that the falling-out is over.

### Automatic contacts

**On by default.** What a contact costs the pair is that they stop being hidden from each other by
distance — which an evening spent working side by side has already stopped being true of. Both
players must still have it on before a pair is so much as sampled: there is no half-consent state
where one of them is accumulating progress towards something the other has switched off. `/contacts
auto off` is one command, and both the contacts list and the help text say so. Servers that want the
stricter reading — where being willing to end up permanently visible to your neighbours is something
each player states first — set `defaultOn: false`.

The rule is a **score**, not a stopwatch:

```yaml
contacts:
  auto:
    defaultOn: true
    radius: 48              # credit runs out here
    closeRadius: 16         # full credit inside here
    requiredMinutes: 20     # of scored nearness — accumulated, not consecutive
    decayRate: 0.25         # score lost per second apart, per second-at-touching-distance
    sampleIntervalTicks: 100
    forgetAfterMinutes: 180 # backstop: past this a pair start from nothing
```

Every pass scores each eligible pair for how close they were, and keeps the running total on both
players' records so it survives a restart. A pass delayed by a lagging server credits at most two
intervals, never the whole gap.

**Distance is a fraction, not a yes.** Inside `closeRadius` a second beside somebody is worth a
second. From there it tapers linearly to nothing at `radius`. Two players at the same workbench reach
twenty minutes in twenty minutes; two players at opposite ends of a field take four times as long;
two players who merely share a wide circle barely move.

**Time apart runs the same arithmetic backwards.** The fraction that is *not* nearness is charged at
`decayRate`, so a pair at the far edge of the radius lose almost as fast as a pair on opposite sides
of the world — and nothing happens at the radius boundary except that the last of the credit runs
out. That is what makes "briefly pass near one another" mean nothing: an evening together comfortably
survives the next day, and an afternoon of walking past each other at spawn is gone by the evening.

The fade is charged when a pair are next looked at, not by visiting every pair on the map. What is
stored is the score at the moment they were last together; the score *now* is that figure with the
time since taken off, worked out on demand. `forgetAfterMinutes` is the backstop under it, for a
server that sets `decayRate: 0` — past that gap the entry is dropped rather than faded.

A pair is skipped entirely if either of them has severed it, if either ignores the other, if they are
already contacts, or if either is at `contacts.max`.

`/samsara social [player]` prints the current score for the top five pairs, and how long since each
was last together — the only part of the system whose state is otherwise invisible.

### Cost

The sampler does not compare everybody to everybody.
[`ProximityGrid`](../src/main/java/dev/unrau/samsara/social/ProximityGrid.java) buckets players by
the learning radius, so only the eight neighbouring cells are ever measured. Players who have turned
automatic contacts off are not in the calculation at all.

## Ignore

```
/ignore <player>     stop seeing them entirely
/ignore list         who that is
/unignore <player>   undo it
```

It is checked **first**, before contacts and before distance, and it is the only test that can refuse
on its own. There is no distance at which an ignored player becomes audible again and no relationship
that reinstates them, including being a contact.

It is one-sided and unannounced. The player being ignored is never told, because a block that
notifies its target is an invitation to argue about it — and the arguing happens to the person who
wanted it to stop.

Ignoring somebody does **not** remove them as a contact. Those are different statements, and
collapsing them would mean a player could not quiet somebody down for an evening without tearing
something up. `/ignore` says so when the two overlap.

## Private messages

```
/msg <player> <message>
/reply <message>
```

An addressed message is not the problem proximity chat was solving: nobody is made to read it, it
names exactly one person, and on a server where the people you know are a thousand kilometres away it
is the only way to say anything to them before either of you has agreed to anything permanent. So
distance does not apply.

Both are rendered with vanilla's own `commands.message.display.*` translation keys, so every client
sees the line it would have seen from `/msg`, in its own language.

### Vanilla owns the label

`/msg`, `/tell`, `/w` and `/whisper` are the server's own commands, registered before any plugin
loads; declaring them in `plugin.yml` does not displace them. Without something else,
`/ignore` could be walked straight around by typing `/msg` — so
[`SocialCommandListener`](../src/main/java/dev/unrau/samsara/listener/SocialCommandListener.java)
intercepts the command line before it is dispatched, including the namespaced `/minecraft:msg` form,
and hands it to the same code the plugin command uses. `/me` is intercepted for the same reason: it
is chat wearing a different hat, and leaving it global would have been a hole straight through the
middle of this.

`/say` is left alone. It is an operator's broadcast and is meant to be one.

### Anti-spam by shape, not by cooldown

A flat cooldown is the easy thing to write and the wrong thing to have. It costs a spammer a loop
with a sleep in it and costs everybody else the ability to hold a conversation, so the one behaviour
it reliably prevents is the one nobody was complaining about.

What actually distinguishes abuse is its shape:

| Rule | Default | Applies to |
|---|---|---|
| unique recipients per window | 6 | non-contacts only |
| same message to distinct recipients | 3 | non-contacts only |
| messages per window | 40 | everybody |
| interval between messages | 200 ms | everybody |

Messages to contacts are exempt from both shape rules — a relationship both players agreed to is a
conversation, not a broadcast. What is left applying to everybody is the crude flood ceiling, which
describes a macro rather than a person.

Duplicate detection ignores case, punctuation and spacing, because the difference between
`join my base!!` and `Join   my base` is one line of code to a spammer and no difference at all to
the six people receiving it.

`samsara.social.unlimited` (op by default) is exempt.

## Tamed animals

Vanilla sends a pet's death message to its owner, wherever they are, and to nobody else. That is
already the conservative answer and it is left exactly as it is — the plugin never widens it, and
never repeats it to the owner, because saying it twice is worse than saying it once.

What is added is the other half of "conservative": whoever was standing there watched it happen, so
players within `pets.radius` are told, and nobody else. Not the server, and not the owner's contacts
— `PET_DEATH` is the one event that does not travel along a contact, because a wolf dying is news in
a place, not news about a person.

The recipient's ignore list applies, and it applies to the *owner*: blocking somebody blocks their
animals with them. A world with `showDeathMessages` off gets nothing, as it should.

## Persistence

`plugins/Samsara/social/<uuid>.json`, one file per player, separate from `playerdata/`:

```json
{
  "dataVersion": 1,
  "name": "Steve",
  "autoContacts": true,
  "contacts": { "5f0d1c8e-...": "Alex" },
  "ignored":  { "8a6f2b31-...": "Herobrine" },
  "autoSuppressed": [ "c31f0a72-..." ],
  "proximity": { "5f0d1c8e-...": { "seconds": 640.5, "at": 1785000000000 } }
}
```

`seconds` is the score *as of* `at`, not a stopwatch reading and not the score now — a second at
touching distance is worth one, and everything further away a fraction of one. The current score is
this figure with the fade for the time since `at` taken off, which is why being apart costs a player
something without the server having to walk every pair to charge them for it.

Deliberately not part of `playerdata/`. That record describes a **life** — where it began, where it
ended — and is rewritten on every death and every crossing of a dimension boundary by several callers
doing their own read-modify-write. This one describes a **person**, and survives everything that
record does not: logging out, dying, being exiled to the other side of the world. Dropping a
long-lived social graph into the middle of the other one would mean two subsystems racing to be the
last to save the same file.

Contacts, ignores and severances are written the moment they change. Proximity progress — the only
high-frequency part — is flushed every `saveIntervalSeconds`, on quit, and on shutdown, and entries
the fade has taken to nothing are pruned as the file is written, so it never becomes a list of
everybody the player has ever walked past.

Online players are always held in memory, which is the invariant the rest of the package rests on: a
record read for somebody who is online is always the live one.

## Turning it off

`social.enabled: false` gives vanilla back entirely — every listener asks again at the moment an
event arrives, so `/samsara reload` is enough on a running server.

Short of that:

- `social.radius: 0` — proximity off, contacts the only way to see anybody
- `social.radius: 30000000` — vanilla's one shared room, with contacts and ignore still working
- `social.contacts.enabled: false` — proximity only, nothing crosses the map but `/msg`
- `social.contacts.auto.enabled: false` — manual contacts only
- `social.messages.enabled: false` — no private messages at all, including vanilla's

`samsara.social.observe` (op by default) receives everything from any distance, for moderation.
`/ignore` still beats it: it is the recipient's switch, and staff are recipients too.
