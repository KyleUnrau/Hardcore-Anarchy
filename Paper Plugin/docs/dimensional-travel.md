# End Travel

> Travel preserves the current life. Only death creates exile.

## The problem this solves

Samsara has no capital, no world spawn worth the name, and no transit hub. Players begin
hundreds of thousands of blocks apart, and the `Strongholds` data pack spreads strongholds across the
whole Overworld instead of 128 rings around 0,0.

Vanilla End travel undoes all of that. Every End portal in the world arrives near End 0,0 — one hub
everybody meets at, handed to the server by accident.

The Nether has no such problem. Overworld ↔ Nether travel is symmetric, eightfold-scaled, and works
exactly as a player expects, so **the plugin does not touch it at all**: no listener, no destination
override, no config. A Nether portal is a Nether portal.

## The rule

One transform, a pure function of position, used in both directions:

| Route | Rule |
|---|---|
| Overworld → End | `(x, z) → (-z, -x)` |
| End → Overworld | `(x, z) → (-z, -x)` |

Sign inversion **and** a swap of the axes: the End is the Overworld reflected in the line `z = -x`.

Reflection in a line is its own inverse, and that is the whole point. The gateway standing at the
reflection of a stronghold returns you to that stronghold because the arithmetic says so — not
because anything was recorded. There is no journey table to corrupt, migrate, or lose, and no way for
a rolled-back world to disagree with the plugin about where a door goes.

`/samsara map [x z]` reports the routes in and out of a coordinate without travelling.

## Going out

Stepping into an Overworld End portal teleports the traveller to the reflected coordinate and builds
an **arrival platform** there, carrying a gateway home. The central obsidian platform is never
created and never visited, and none of this depends on the dragon being dead.

Throwing a pearl into the portal does the same thing rather than the vanilla thing — see
[Doors can be thrown at](#doors-can-be-thrown-at).

### One portal, one platform

A traveller stepping into an End portal is standing in exactly one of its nine blocks — whichever
one their feet were over — and the reflection is a function of position, so it answers a different
question depending on where somebody stood.

Grid snapping was supposed to reconcile those nine answers, and it does not. **This was a real bug**,
and the shape of it was two arrival platforms in adjacent chunks, both leading back to the same
stronghold, because both were honest reflections of the same portal room. An opening three blocks
across that straddles a 16-block cell boundary hands its blocks two different cells on that axis, and
one straddling a boundary on *each* axis hands them four. Of the 256 positions a portal centre can
take relative to the grid, **60 straddle a boundary** — very nearly a quarter of the strongholds on
the server were going to do it, and the first one that did built its second platform 16 blocks from
the first.

A wider grid does not fix this and makes it worse: the boundary does not go away, it only moves, and
every portal landing on one is still split. The question was wrong. What names a portal is not one of
its blocks, snapped or otherwise — it is *the portal*, and a portal has a centre.

So the centre is found before the reflection is asked anything. All nine candidate centres the
traveller's block could belong to are scored against the world, and

- **a complete ring of twelve inward-facing frames settles it outright.** That is the shape vanilla
  requires to light the portal in the first place, no two portals can share a ring, and frames cannot
  be mined — so a portal missing half its opening is still unmistakably one portal in one place.
- **failing a ring, the opening itself decides.** A portal placed by an editor rather than lit has no
  frames, and the candidate covering the most portal blocks is still the true centre, since only it
  can cover all nine.

Ties break on the lowest coordinate, so a shape this has never seen still resolves the same way every
time. Determinism is the whole requirement: a centre that wavered would put the platform back exactly
where it started.

The centre is then snapped to a grid of `arrivalSiteSpacing` blocks (16 by default). Snapping no
longer carries the invariant above and is not what keeps a portal to one platform; what it earns is a
backstop for the one case no portal can be named at all, where collapsing an opening onto a shared
cell is better than nothing. It costs a return column within 8 blocks of the portal you set out
from, which is what bounds the search that finds the portal again on the way home.

Platforms already built by the old behaviour are left standing. Each one is a working way home — its
gateway reflects back to within reach of the portal search, so nobody who walks into the spare door
is stranded — and they can be cleared by hand.

## Getting around: wormholes

Every End gateway that is **not** one of those ways home is a wormhole. That includes the gateways
the dragon leaves behind, the ones waiting on the outer islands, and the plugin's own grid. Step in
and you come out in a distant End cell; step into the gateway waiting there and you come back.

A wormhole announces itself as nothing at all, because it **is** a vanilla End gateway: the same
twelve blocks of bedrock around one gateway block, sealed above and below, open on all four sides,
floating over the ground. You get into one the way you always have — a pearl through the side, or a
trapdoor to stand in. Nothing distinguishes the plugin's from the dragon's, and nothing should: they
do the same thing.

### Why the pairing is arithmetic, not a file

The obvious implementation is a saved list of gateway pairs. That file can be lost, corrupted, or
fall out of step with a world that was edited or rolled back, and every one of those failures strands
somebody — the gateway in front of them no longer knows where it goes.

So `WormholePairing` computes it. Cell indices run through a seeded Feistel permutation `P`, and

```
partner(n) = P⁻¹( P(n) XOR 1 )
```

Two properties fall out as identities rather than as things to remember:

- **Wormholes are two-way.** `partner(partner(n)) == n`, because the two XORs cancel. A pairing is
  never half-built and never disagrees with itself from the far end.
- **No gateway leads to itself.** `P(n) ^ 1 != P(n)` and `P⁻¹` is injective. There are no duds.

Because `P` is a keyed pseudorandom permutation, flipping one bit of the *ciphertext* lands the
partner in an unrelated part of the End. Neighbouring gateways go to wildly different places, and the
destination cannot be worked out by eye — but it is perfectly stable, and `/samsara wormholes [x z]`
will tell an administrator exactly where any of them goes.

Pairings are made between **cells** of `wormholes.cellSize` blocks (16 by default), not between exact
blocks, because the gateway at the far end may not exist yet — it is built on arrival. Every gateway
inside one cell shares a destination.

#### Every wormhole stands at its cell's centre

That is an invariant, not a convention, and it is what keeps a cell to one gateway.

A pairing returns a traveller to a cell **centre**. So a gateway anywhere else in its cell can be
left from but never arrived at: jump away from it, come back, and the arrival finds no gateway where
it landed and builds one — eight blocks from the one you set off through. Do it again and there are
three. This was a real bug, and the shape of it was a grid node at `512,512` sitting eight blocks
off the centre of the cell containing it. Scattering nodes inside their cells makes that the ordinary
case rather than the unlucky one: a scattered node lands on a 16-block cell centre only by accident.

So a node is snapped to its cell centre before anything is built, which makes every wormhole in the
world a fixed point of the network. `partner(partner(site)) == site` exactly, and a round trip lands
*on* the gateway it set out from rather than beside it.

#### And it is built only once

Standing in the right place is half of it. The other half is standing at the right *height*, and for
a while a wormhole did not: its height was recomputed from the terrain on every single arrival.

That reads as harmless, because the build is idempotent — a second visit should find everything
already there. It is only idempotent if the inputs do not change, and they do. The height is raised
to clear anything in the footprint the builder refuses to overwrite, and the footprint is exactly
where a player stands a trapdoor to crawl in, pillars up to reach the gateway, or where a chorus
plant grows another block. Any of those moves the height the next arrival picks. The new shell goes
up a few blocks over; the old one's bedrock stays, because bedrock is placed and never removed; and
the two seal each other into a column with a gateway bricked up inside it. Go back and forth a few
times and the End grows a pillar where a wormhole used to be. **This was a real bug**, and the shape
of it was a wormhole visibly closed by a second wormhole grown on top of it.

So a height is chosen once, when the column is empty, and after that **the gateway standing there is
the record**. Its persistent data already says which site it belongs to, and that rides along in the
chunk across restarts, so nothing new is written to disk — a rebuild reads its height back off the
world the same way routing reads a destination off a coordinate.

Where a column already holds several, the lowest is the wormhole and the rest are demolished: their
bedrock as well as their gateway block, inside a gateway's own shell being the one place the builder
is allowed to take its bedrock back out again. Drift was always upwards, since the height only ever
rose to clear an obstruction, so the lowest gateway is the original standing where it was before
anything went wrong. A world that already grew a stack collapses back to one gateway the next time
anybody travels through it, and the survivor's bricked-up openings are cut back open.

Home platforms are anchored the same way, to the doorway standing in them, which stops a terrace
climbing a block every time somebody leaves a chest on it. Doorways orphaned by the old behaviour are
reported in the log and left standing rather than demolished — a stray wormhole shell floats in the
void, but a stray doorway stands on a platform players build around, and pulling bedrock out from
under somebody unasked is worse than an odd-looking door.

#### The network always spans the whole End

Those identities are about *cells*, and they only help a traveller whose cell is one the network
actually has. A position past the edge of the index space must be folded onto a cell that exists, and
every folded position then shares that cell's single destination — so the gateway at the far end
leads back to the cell, not to the traveller. **A network smaller than the End is not a smaller
network, it is a broken one.**

This was a real bug, and the shape of it is worth keeping in mind. The network used to span
`wormholes.reach` (8,388,608 by default) while the Overworld reflection and the distributed grid both
reached the world border at 29,999,984. A player who stepped into the gateway at End
`19997184,-9998848` was thrown to `4978296,3428456` correctly — but the gateway waiting there led to
`8388600,-8388600`, the centre of the boundary cell that their origin had been folded into, and the
two of them bounced back and forth forever. Everything past 8.4M shared one ring of cells, so that
"round trip lands within half a cell" was, out there, a round trip landing 12 million blocks away.

The reach is therefore no longer configurable: it is the End's own world border, always. A Feistel
network permutes a fixed number of *bits*, which is what forced the old power-of-two cell count and
made covering the border impossible. The domain is now exactly the cells that exist, whatever number
that is, and the power-of-two permutation is fitted to it by
[cycle walking](https://en.wikipedia.org/wiki/Format-preserving_encryption) — encrypt into the
enclosing power-of-two space, and keep encrypting while the result lands outside the real domain.
Because `P` permutes the enclosing space, the orbit of any cell returns to it, so the walk terminates
(after fewer than four steps on average) and the result is a permutation of the real domain. Both
identities above survive untouched.

Folding still exists, but it now applies only to coordinates past the world border, where nobody can
stand.

### The distributed grid

The End is divided into cells of `gateways.spacing` blocks (512 by default) and each cell holds
exactly one gateway. *Where* in its cell is decided by hashing the cell's indices with the End's own
seed, so the layout is irregular but reproducible: one world always scatters its gateways the same
way, and no two worlds scatter them alike. Nodes are built the first time a player comes within
`gateways.materialiseRadius` of one.

Without them, a traveller who arrived by End portal would have to fly to the central island to find
their first gateway. With them, there is always a wormhole a few hundred blocks off.

**Why scattered and not a lattice.** A gateway at every cell centre reads as reasonable and plays
badly, because what a player notices is not gateways per square kilometre — it is whether one drifts
into view along the line they are actually flying, and a gateway is small enough that "in view" means
a hundred or two hundred blocks. On a lattice that is settled by the row they happen to be in: fly
east along a row of nodes and you meet one every cell; fly east halfway between two rows and you meet
*nothing, ever*, however far you go. Two players doing the same thing get opposite games and neither
can tell why. Scattering removes the rows — each cell offsets its gateway independently on both axes,
so the perpendicular distance from any straight course varies cell to cell instead of being fixed for
the whole journey.

`gateways.separation` (128 by default) is the one thing the scatter has to be told. Offsets are
confined to a window `spacing - separation` wide, centred in the cell, so two gateways either side of
a shared edge can never land a few blocks apart — and because the window straddles the cell centre
rather than its corner, nothing can land on End 0,0 or on either axis.

**The separation is kept small on purpose.** A margin no gateway may occupy is also a band of
coordinates no gateway can be *seen* from: fly due east down the middle of one and the nearest
position a gateway could possibly take is `separation / 2` away, so a shorter sight range goes
unrewarded however far you fly. That is the lattice's blind course again, narrowed but not gone.
At 128 the bound is 64 blocks — four chunks, which every player clears — so no straight course is
blind. At 320 it would be 160 and a slice of headings would be back to seeing nothing.

Measured over 20,000 random due-east flights, with a gateway noticed at 128 blocks: first sighting
after **874 blocks** on average, 14,223 at worst, and no flight in the sample went without. The
1024-block lattice it replaces left **75%** of those same flights seeing nothing at all in 200,000
blocks, because the row they started in was the row they stayed in. The nearest gateway to an
arbitrary standing position also halves, from a mean of 390 blocks to 211.

**Nothing about the layout is written to disk.** Node positions are a pure function of the seed and
the build is idempotent, so after a restart the first player back in the area simply re-runs a build
that finds everything already in place.

### The End's own gateways are adopted

A gateway the world generated is claimed by the plugin the moment its chunk loads: labelled a
wormhole belonging to its cell, and given an exact exit pointing at itself.

The label is what routes it. The exit is what stops vanilla doing something irreversible first —
vanilla resolves a gateway's destination the instant anything enters it, generating an outer island
and a return gateway if the region is empty, and it does that *before* any plugin is consulted.
Cancelling the teleport afterwards is too late; the island is already there. Filling the exit in
ahead of time means the only thing vanilla is ever left holding is a hop to where the traveller
already stands.

The consequence is that no gateway anywhere in the End still leads to the outer islands, and from
them to End 0,0. That route was the hub this server is built to not have.

## Coming home

**Only an arrival platform leads out of the End.** A wormhole never reaches the Overworld, and
neither does anything a player finds in the wild.

This is the rule that shapes the dimension. Leaving means reaching a door that somebody made by
lighting an End portal from the other side — your own, or another player's, which drops you into
*their* part of the Overworld. Wormholes make that reachable; they never substitute for it. Nobody
emerges into an untouched Overworld region by falling through a hole in the End.

### You come out at the portal, not above it

An Overworld End portal is nearly always at the bottom of a stronghold. The reflection names the
portal's **column**, and a column is not a place to stand — asking the height map about it answers
with the roof of the stronghold, hundreds of blocks up. Arriving there is technically the promised
coordinate and practically the wrong place entirely.

So the destination is resolved by looking for the portal itself:

1. **The journey's own record.** Stepping into a portal stores a validated standing spot beside it,
   and the return re-validates that spot rather than trusting it: the stronghold may have been dug
   out, flooded or built over since. If the exact spot no longer works, the search around it is
   small and centred on the *portal's* height, so the answer is still the portal room.
2. **The portal block.** Failing a record — somebody else's gateway, or a journey that was closed —
   the mapped column is searched top to bottom for an `END_PORTAL` block, and the traveller is set
   down beside whichever one is nearest. The snap grid guarantees the portal is within half a cell
   of the mapped column, so this is a small search that almost always succeeds on the first column.

A gateway is a public door, so the record is only consulted when its End site is the one being left
from. Walking into another player's gateway takes you to *their* portal, never to your own
somewhere else in the world. `/samsara endrecover` is the one exception: sending a stuck traveller to
their own portal is the point of the command.

Neither rung can put anybody inside the portal they just came out of — the standing test rejects
portal blocks outright, so a return can never bounce straight back into the End.

## The exit portal at End 0,0

The exit portal appears when the dragon dies and is relit with the egg, exactly as in vanilla. What
it does is vanilla too, and deliberately so: **you wake in your bed, or at your respawn anchor**.
This is the one place on the server where a bed does what a bed does everywhere else, and it costs
nothing — killing a dragon at End 0,0 is not something anybody stumbles into.

Only the last case needs changing. Vanilla falls back to the world spawn and there is no such place
here, so a player with no bed and no anchor is returned to where *this life* began: their last exile,
or their first spawn if they have never died.

No exile is calculated, no life is rotated, no death is recorded. Nobody died.

### The dangerous overlap

Vanilla leaves the End through **respawn** machinery — the exit portal literally respawns the player,
firing `PlayerRespawnEvent` with reason `END_PORTAL`. Untouched, that would look exactly like a death
to this plugin and would exile someone who never died.

`RespawnListener` therefore checks the respawn reason first and handles it there, never falling
through to the exile path.

## Doors can be thrown at

Every route above describes a player *entering* something. A thrown ender pearl enters it instead,
and the difference is not cosmetic: the pearl is the thing in the portal, so none of the player
events this plugin listens for ever fire. Vanilla crosses the **pearl**, resolves the destination the
vanilla way, and then drags the owner to wherever the pearl came out.

That is a hole shaped exactly like the one this whole feature exists to close, because the vanilla
destination is End 0,0 going in and the Overworld's shared spawn coming out. **This was a real bug**,
and the shape of it was a player pearling into their own stronghold portal and landing on the
obsidian platform at the centre of the End — the hub, reached by throwing something at the door
rather than stepping through it, with the reflection, the platform and the way home all skipped.

So a pearl thrown by a player is intercepted wherever a door is, and there are three answers.

| Thrown into | What happens |
|---|---|
| An **Overworld End portal** | The thrower travels, exactly as if they had walked in. The pearl is spent where it is |
| An **End gateway** | The thrower travels — home or by wormhole, whichever that gateway is. The pearl is spent where it is |
| The **exit portal at End 0,0** | Refused. The crossing is cancelled and the pearl flies on as an ordinary pearl |

The first two are the same journey with a step taken out of it. The pearl is removed once the plugin
has taken responsibility: left alive it would sail out the far side and teleport its owner back to
wherever it landed, undoing the journey a tick later.

The exit portal is refused because there is nothing to route it to. Leaving the End is respawn
machinery — a bed, an anchor, or where this life began — and a pearl reaches none of it; vanilla
would put the thrower at the Overworld's shared spawn, which is the 0,0 hub arriving from the other
direction and a bed skipped besides. Refusing costs the pearl its crossing and nothing else. **The
way out of the End is walked into, not thrown at.**

### Caught before the crossing, and again after

A pearl is caught twice, and the earlier catch is the one that matters. `EntityPortalEnterEvent`
fires the moment the pearl touches the portal block — before vanilla has resolved a destination or
built anything at End 0,0 — and it names the portal block itself rather than wherever the pearl had
drifted to by the time a transition was worked out. `EntityPortalEvent` is a backstop for a crossing
that gets that far anyway. Both end at the same routing call, and a second event for a journey
already under way is swallowed by the same transit claim that swallows a portal firing every tick.

What identifies an End portal here is **the block the pearl is standing in**, not the event's portal
type. Nether portals are untouched in every direction, and a block is the plainer statement of which
kind of door this is.

### Whose pearl moves whom

Only a pearl thrown by a player who is **in the same world as the portal** routes anybody. An owner
somewhere else entirely is not walking into this door, and moving them as though they were would
teleport somebody out of a world they had no reason to leave. A pearl from a dispenser, or from
somebody who has since logged out, carries nobody and is left to vanilla.

## What gets built

Two structures, and they look nothing alike. That is deliberate: a traveller should be able to tell
at a glance whether the door in front of them leads *out* of the End or merely *across* it.

### The platform — only ever a way home

A 7×7 terrace of end stone brick ringed in obsidian, with crying obsidian at the four corners and at
the centre — the one block a traveller always lands on, so it is the one block that is unmistakable
from anywhere on the platform. Each corner is marked by a single brick block one course high with a
stair beside it on each rim stepping back down, and it stops there: three blocks of every rim are
left open to walk out through.

On the `-Z` rim stands the doorway: a solid bedrock face three blocks wide with a two-block-tall
opening cut out of it, one step above the floor and capped by a single course of bedrock, and a
second face of the same size behind it so the whole thing reads as a block of bedrock from outside
and nobody steps into the void. In front of it,
a stair on the centre line makes the step up, and either side a brick block capped with an end stone
brick wall stands as a post. A waypoint, not a settlement.

Frankly artificial, and it should be. Somebody made it: a player lit an End portal on the other side
of the world, and this is the door that opened.

### The wormhole — a vanilla End gateway

Twelve blocks of bedrock in a cross above and below a single gateway block, capped top and bottom,
its four sides open, floating `wormholes.gatewayHeight` blocks (10 by default) over the ground.
Block for block what `EndGatewayFeature` builds, because a wormhole that announced itself as
something else would be telling the player something untrue.

No platform. Where there is no ground beneath it, an **End island** is grown — vanilla's own
`EndIslandFeature`, a disc four to six blocks across narrowing as it descends, which is exactly what
vanilla does for a gateway whose destination turns out to be empty. The traveller arrives on the
island with the gateway overhead; getting back in is a pearl or a trapdoor, as it has always been.

The island's shape is drawn from a random seeded on its own coordinates. That is not decoration — a
fresh seed would redraw a different island on every visit and stack them into a tower.

### Both

- **Nothing natural is destroyed.** Only air and end stone are ever replaced. If an end city, chorus
  forest, existing gateway or player build occupies the footprint, the structure is built *above* it
  rather than cut through it.
- **It repairs itself.** Every visit re-runs the same build, so griefing or a stray creeper does not
  strand the next traveller. The bedrock cannot be mined out.
- **It sits on land where there is land.** Natural End terrain is used as the floor; only genuinely
  empty regions get a platform or an island in the void. The builder ignores its own bedrock and
  gateway blocks when reading the terrain, so a rebuilt site stays on its floor instead of climbing
  every visit.
- **It is built once.** The terrain is only asked where a site stands on the day the site is created.
  After that the gateway already standing there gives the height back, so nothing that appears in the
  footprint later can move the structure and leave the old one behind.
- **It leaves the dragon island alone.** Nothing is built within `centralIslandProtectRadius` of End
  0,0. Routing is never bent by this — an Overworld portal near 0,0 still reflects onto the central
  island — but the arena is landed on, not paved over.

## Gateway identity

Each gateway block carries, in its persistent data:

| Key | Meaning |
|---|---|
| `return_gateway` | The plugin has taken responsibility for this gateway |
| `gateway_kind` | `home` or `wormhole` |
| `site_x`, `site_z` | The centre of the site it belongs to |

The kind is what keeps the End's own gateways working as wormholes rather than being mistaken for
doors home. The site centre matters because a gateway rarely stands at the coordinate its
destination is computed from — a home gateway sits on the platform *rim*, and a wormhole's pairing
belongs to its cell — so routing from the block itself would land a traveller a few blocks off.

Persistent data rides along in the chunk, so this survives restarts with no plugin-side storage. A
gateway with no recorded kind is read as `home`, which is what everything built before kinds existed
was; a gateway with no tag at all is one nobody has adopted yet, and is treated as a wormhole.

## Death

A death in any dimension is a real death, and the existing exile system takes over in full: the Ender
chest drops, the life ends, and a new life begins far away.

Three things happen that are specific to travel:

- **The in-flight claim is released**, so a teleport still resolving cannot report back onto the new
  life.
- **The journey record is closed** by the life rotation, so a new life never inherits the old life's
  way home.
- **The exile distance is measured in Overworld terms.** A death in the End at `(x, z)` is measured
  from Overworld `(-z, -x)`; a death in the Nether from `(8x, 8z)`. Raw End or Nether coordinates are
  not comparable Overworld distances, and this needs no record of how the player got there.

Ordinary travel never invokes any of the exile machinery.

## Crossing first, landing second

Every destination here may be terrain that has never been generated, so a journey always contains a
wait. The only real decision is which side of the dimension boundary it is spent on, and the answer
is the far side.

The traveller is put into the destination dimension in the **first** tick of the journey, above the
column they are bound for, and the chunk loading and site building happen while they are already
there. Only one chunk has to load for that — the one under the hold — rather than the whole footprint
the arrival is resolved in.

Two things were wrong with the older order, and both are the same mistake seen from different ends:

- **The transition was invisible.** The client shows its loading screen when the dimension changes,
  and it holds it until the terrain it needs has arrived. Crossing last meant the change went out
  after everything was already loaded, so the screen that should have covered the journey flashed
  past in a frame or two and the journey itself was spent watching the old world. Crossing first puts
  the real screen up — the End's starfield going in, the Overworld's coming out — for exactly as long
  as the server is actually working.
- **The wait was dangerous.** An End portal block is not solid and a stronghold portal room has lava
  under it, so a traveller waiting out a slow journey was standing somewhere that could kill them.
  Parking them on the nearest ledge patched it. Leaving the dimension entirely removes it.

### Suspending happens first, and synchronously

Crossing needs a chunk, and a chunk can take a moment. Cancelling the vanilla transition does not,
and takes effect immediately — so between the two there is a window in which the plugin has stopped
the journey and left an ordinary, falling, damageable player standing in a hole. That window is the
one that put somebody in the lava under a stronghold.

So the suspension is applied **inside the portal event itself**, before anything asynchronous starts.
From that tick the traveller cannot fall, cannot be hurt and cannot walk away. Only then does the
crossing begin, and it may take as long as it needs.

### What actually holds a player

Not gravity. `setGravity(false)` is an entity flag the server keeps for entities the server moves,
and a player is not one of them: the client simulates its own movement and reports where it went, so
a player with the flag set falls exactly as before. It is still set, because it is saved to the
player file and is therefore a durable marker of an unfinished journey — but it holds nobody.

What holds a player is **flight**. A flying client applies no gravity, and a fly speed of zero leaves
it nowhere to go. With invulnerability that is a real suspension.

Once across, the traveller waits above the world's build ceiling: nothing to stand in, nothing to
fall into, nothing that can hurt them. Above rather than below, because the void is the one damage
invulnerability does not stop.

### Nothing is left hanging

Four things end a hold, and between them they cover every way a journey can stop:

1. It finishes, and the traveller is delivered.
2. It never reports back, and a watchdog releases them after 30 seconds — onto the ground beneath
   them if they crossed, back to the spot the journey started from if they did not.
3. The server loses them mid-journey, and their next login finishes it. This is what the gravity
   marker is for: it lives in the player file and survives the logout, the crash and the restart that
   would lose an in-memory note.
4. A door they walk into finds a claim with no traveller attached and takes it over. A claim is what
   stops a portal firing every tick from stacking up journeys, and while one is held every further
   attempt is *cancelled* — which is right for a journey in flight and catastrophic for one that
   leaked, because it means the door in front of an unprotected player refuses to open until the
   claim ages out.

### Cooldowns are cleared, not applied, on this side

`portalCooldownTicks` is applied on arriving **in the End**, where the traveller lands on a platform.
It is explicitly *not* applied on returning to the Overworld, and it is cleared when a journey is
abandoned. Both for the same reason: a returning traveller is standing beside their own End portal,
and a portal cooldown does not stop them walking back into it — nothing does — it only stops the
portal answering when they do. An End portal block is not solid, so a door that ignores you is a
hole in the floor. Loops are prevented by the transit claim, which is about a journey rather than a
doorway, and which cannot drop anybody through anything.

### Wormholes

Both ends are in the End, so there is no boundary to cross and no loading screen to earn. The
traveller is still suspended, because the far end may be an unbuilt cell in the void and a gateway
that has fired will not fire again for five seconds — long enough for somebody left to their own
devices to walk out of it and conclude it is broken.

Turning `immediateTransition` off restores the older order in both directions, including the ledge
parking that stood in for the suspension.

## Safety

Every destination is validated before a teleport, falling back in this order:

1. **`PORTAL`** — beside the Overworld portal itself, by record or by search. The ordinary case.
2. **`EXACT`** — the mapped coordinate is landable.
3. **`NEARBY`** — a landable spot within `returnSearchRadius` of it.
4. **`FORCED`** — the mapped column as it is, logged at `WARNING`.

Rungs 2 to 4 are reached only when there is **no portal left at the mapped column** — it was never
lit from the Overworld, or the world has been edited since. They are a recovery path for a door that
no longer exists, not the route home, and a server seeing them routinely has something else wrong.
Only these three tell the player anything; landing at the portal is silent about the terrain.

The ladder never leaves the mapped coordinate's neighbourhood and **never falls back to world
spawn**: a gateway that dropped travellers at spawn would be exactly the hub this server does not
have. In the worst case a traveller arrives at the coordinate they were promised and deals with the
terrain themselves. This is a recovery path, not an exile — the life, inventory and history continue
untouched.

Landing is tested by walking a column *down* from the height map rather than testing only the block
it names. The topmost block that stops motion is frequently something nobody can stand on — leaves,
a snow layer, the surface of a lake — and testing only that block rejects an ordinary forest and
sends the search looking elsewhere. The descent stops at the first liquid, because the seabed under
an ocean satisfies every other rule and drowns whoever lands on it.

Other guards:

- **Duplicate events.** A portal fires every tick a player stands in it. `TransitRegistry` claims a
  player for the few ticks a journey takes; duplicates are swallowed, and a claim that never reports
  back expires after 15 seconds so nobody is locked out of their own portal. The claim also covers
  the two ways into a gateway arriving as two different events.
- **Pearls.** See [Doors can be thrown at](#doors-can-be-thrown-at). A pearl thrown by a dispenser,
  or by somebody who has since left, carries nobody and is left to vanilla — which, every gateway
  having an exact exit by then, does nothing worth mentioning.
- **Cooldown.** `portalCooldownTicks` is applied on arrival to stop immediate re-entry loops.
- **Falling.** Cancelling an End portal stops the transition but not the fall, and a stronghold
  portal room has lava underneath. With `immediateTransition` on this cannot arise — the traveller is
  out of the Overworld within a tick. With it off they are stood on a ledge beside the portal for the
  wait instead.
- **World borders and extremes.** Every transform clamps to the live world border and to vanilla's
  29,999,984 limit, in `long` arithmetic.
- **Chunk loading.** Destinations are millions of blocks away in terrain that has never been
  generated. Chunks are always loaded asynchronously and world work is done in a scheduled main-thread
  task; the server is never stalled on generation.

## What is left alone

Overworld ↔ Nether travel entirely; End terrain, islands, end cities, chorus forests, the central
island, the dragon fight, the exit portal's vanilla conditions, and existing worlds and
infrastructure. The End's own gateways keep standing exactly where they were generated, and look
exactly as they always did — only where they *lead* is changed.

The dragon island keeps its vanilla significance. It is simply no longer everyone's front door.

## Division of responsibility

| Component | Owns |
|---|---|
| **Plugin** | All End routing, the reflection, wormhole pairing, teleportation, site construction, gateway identity, safety, recovery and logging. Authoritative. |
| **`Strongholds` data pack** | Stronghold placement only. Unchanged by this feature, and no dependency in either direction. |
| **Companion data pack** | Not needed — see below. |
| **Resource pack** | Nothing required. Player-facing strings are configurable in `config.yml`. |

### Why no data pack for the gateways

A data pack was considered for distributing gateway structures and rejected:

- Worldgen structure sets only apply to **newly generated chunks**, so an existing world would get
  gateways in unexplored regions only — exactly where they are least useful.
- A data pack cannot write the persistent data that distinguishes a home gateway from a wormhole, so
  the plugin could not tell them apart.
- A data pack cannot make routing decisions, so the plugin would still own everything that matters.

Lazy materialisation from a pure grid function does the same job, works in already-generated chunks,
and ships as one class.

## Configuration

Everything lives under `dimensionalTravel` in `config.yml`. `enabled: false` restores vanilla
behaviour entirely. A config still using the older `endTravel:` section name is read as a fallback.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Turn End travel on or off |
| `overworldName` | *(`worldName`)* | Only End portals in this world are intercepted |
| `endWorldName` | `world_the_end` | The End world to route into |
| `arrivalSiteSpacing` | `16` | Grid the End arrival point is snapped to; `1` disables snapping |
| `arrivalPlatformRadius` | `3` | Platform is `2 × radius + 1` blocks square |
| `arrivalPlatformY` | `64` | Height to build at in the void: the platform's floor, and an island's top |
| `buildArrivalSites` | `true` | Build platforms and wormholes automatically |
| `centralIslandProtectRadius` | `1024` | Never *build* within this radius of End 0,0 |
| `gateways.enabled` | `true` | Materialise the distributed wormhole grid |
| `gateways.spacing` | `512` | End blocks per cell; each holds one gateway, scattered within it |
| `gateways.separation` | `128` | Closest two neighbouring gateways may come; keep well under half of `spacing` |
| `gateways.materialiseRadius` | `192` | How close a player must come for a node to be built |
| `gateways.scanIntervalTicks` | `100` | How often to check for nearby unbuilt nodes |
| `wormholes.enabled` | `true` | Make non-home End gateways two-way wormholes |
| `wormholes.cellSize` | `16` | Blocks per pairing cell. Every wormhole is built at a cell centre |
| `wormholes.gatewayHeight` | `10` | Blocks between the ground and the gateway floating above it |
| `wormholes.seed` | `0` | Which pairing this world uses. **Changing it repoints every wormhole** |
| `returnSearchRadius` | `16` | Recovery only: how far to look for open ground when no portal remains at the mapped column |
| `portalCooldownTicks` | `100` | Cooldown applied after arriving |
| `immediateTransition` | `true` | Cross the dimension boundary in the first tick and resolve the landing afterwards |
| `debug` | `false` | Narrate travel to the console: every departure, arrival and routing decision. Off, the console stays quiet and only failures are logged |

Obsolete keys are ignored with a warning naming each one: `overworldToEndScale`, `netherFromEnd`,
`netherPortals`, `netherWorldName`, `wormholes.reach`.

## Commands

| Command | Description |
|---|---|
| `/samsara map [x z]` | The routes in and out of a coordinate |
| `/samsara wormholes [x z]` | Where a wormhole here comes out, and the grid nodes nearby |
| `/samsara expedition [player]` | A player's open End journey record |
| `/samsara endrecover <player>` | Force a stuck traveller out of the End now |
| `/samsara endrecover clear <player>` | Drop a corrupt journey record without moving anyone |

## Journal entries

Transitions are recorded in the traveller's own record, `playerdata/<uuid>.json`, under `journal`,
alongside the existing reasons:

| Reason | Meaning |
|---|---|
| `END_DEPART` | Crossed into the End; the location is the arrival platform |
| `END_RETURN` | Left the End and was set down at the portal, or at the reflected coordinate itself |
| `END_RETURN_NEARBY` | Left the End, but no portal remained at the mapped column |
| `END_WORMHOLE` | Jumped between two End cells; the location is where they came out |

The server log carries the detail: every route taken, every fallback with both locations, every
wormhole with its distance, every node built and where it leads, journeys closed by death, and any
failure to build or teleport.

## Existing worlds and legacy players

Safe to add to a running server with an existing world.

- **Players already in the End** are routed by position like anyone else. Nothing about them needs to
  have been recorded in advance, because routing keeps no per-player state.
- **Existing player data files** load unchanged. Journey records written by a previous design are
  read and then ignored for routing.
- **Existing End infrastructure** — bases near the centre, natural gateways, end cities, previously
  generated chunks — is untouched. Natural gateways keep standing and keep their appearance; they
  are adopted into the network the first time their chunk loads, and now lead sideways rather than
  to the outer islands.
- **Duplicate arrival platforms** built before the portal centre was anchored are left standing, and
  the plugin will not tidy them up. Only one of them is the site the portal now reaches; the others
  are spare doors on the same stronghold, and they keep working — a spare reflects back to within 8
  blocks of a portal block, and the return search reaches 10 — so nobody who walks into one is
  stranded. Remove them by hand if the clutter matters. Their bedrock is not mineable, so clearing
  one means an operator in creative or a world editor.
- **Wormholes stacked on top of each other** by a build before the height was anchored are repaired
  in place: the next traveller through one collapses the stack to its lowest gateway, removes the
  bedrock of the others, and reopens the sides of the one that is left. It is logged at `INFO` with
  the heights involved. Home platforms are not repaired this way — leftover doorways are logged and
  left alone.
- **Wormholes built as platforms** by a pre-release build of this feature are not demolished, and
  the plugin will not tidy them up. Their gateway still works — it routes by cell like any other —
  but the vanilla-shaped wormhole for that cell is built separately, so both will stand. On a world
  that ran the earlier design, remove the old `+Z`-rim wormhole doors by hand, or regenerate the
  End.
- **Existing beds and respawn data** keep working exactly as before. Beds still skip the night and
  still set a vanilla spawn point; death still exiles regardless. The exit portal is now the one
  place a bed is honoured for travel.
- **Platforms from an earlier build** are upgraded in place on the next visit, gaining the kind tag
  on their gateways. Untagged gateways of ours are read as ways home, which is what they were.
- **Turning the feature off** is safe at any time. Platforms, wormholes and islands already built
  remain as ordinary blocks, and every gateway the plugin ever touched keeps the exact exit it was
  given — so they all become harmless, hopping the player to where they already stood. This is not
  reversible for the End's own gateways: an adopted gateway has lost vanilla's route to the outer
  islands and will not rediscover it.

## Known limitations

- **Ocean arrivals.** An Overworld destination in open ocean gives no solid ground, so a traveller
  arrives at the water surface and swims. Survivable, and preferable to relocating them somewhere
  that breaks the coordinate contract.
- **Reaching a grid node.** Nodes are built within `materialiseRadius` of a player, but the player
  still has to find them. The End gateway beam is visible from a long way off; there is no map or
  compass integration.
- **Getting into a wormhole costs something.** A vanilla gateway is a one-block cavity sealed above
  and below, floating ten blocks up. Nobody walks into one: it is a pearl, an elytra, or the crawl
  trick with a trapdoor, exactly as it has always been. That is the point of the shape and not a
  defect — but it does mean a traveller who lands on an island in the void with no pearls left has
  a problem, and the island's end stone to solve it with. Only the ways home are walk-through.
- **Wormholes are blind.** A jump goes somewhere effectively random, and the only way to learn the
  network is to travel it. That is the intent, but it means no player can plan a route to a known
  destination without having flown it once.
- **Changing `wormholes.seed`** silently repoints every wormhole in the world. Nobody is stranded —
  the gateway in front of them still works — but it will no longer go back where it did.
- **Clamping at the world border** puts a site slightly off the snap grid. Only reachable within a
  few blocks of the border.
