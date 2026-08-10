package dev.unrau.samsara.path;

import dev.unrau.samsara.data.PlayerData;

/**
 * A path that nobody is currently walking, held whole.
 *
 * <p>Both halves are needed and neither is enough. The {@link IncarnationState} is what Minecraft
 * would have kept — position, belongings, experience, condition. The {@link PlayerData} is what
 * Samsara would have kept — which life this is, where it began, what it has survived, whether an
 * End journey was open when its owner stepped away. Restoring one without the other produces a
 * player standing in the right place as the wrong existence.
 *
 * <p>An active path has no snapshot. It <em>is</em> the player, and its record is the ordinary one
 * in {@code playerdata/}. That is the invariant the whole switch is built on: a snapshot file
 * existing for the path the index calls active means the last switch did not finish.
 */
public record PathSnapshot(PlayerData record, IncarnationState state) {
}
