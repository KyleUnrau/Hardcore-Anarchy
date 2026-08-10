package dev.unrau.samsara.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One of a player's paths: a persistent existence in the world, with its own position, its own
 * belongings and its own history.
 *
 * <p>A path is not a life. A life ends when its body does, and Samsara's whole premise is that it
 * ends far from where it was lived; the path it belonged to survives that and receives the next
 * life. So a player who dies on {@code Original} wakes up on {@code Original}, half a world away
 * and with nothing, exactly as they always did. What a path adds is that they might have somewhere
 * else to be instead.
 *
 * <p>The name is the player's own and is how every command addresses it. The id is not shown
 * anywhere: it is what the files on disk are keyed by, so that renaming a path is a rename and not
 * a migration.
 */
public final class PlayerPath {

    private final UUID id;
    private String name;
    private final long createdAt;

    /**
     * Who this path began alongside, by the names they were using at the time.
     *
     * <p>History and nothing else — no command reads it to decide anything. It exists because a
     * shared beginning is the one fact about a path that is not recoverable from the path itself,
     * and a player looking at a list of names months later deserves to be told which of them they
     * walked into the world with.
     */
    private final List<String> companions;

    public PlayerPath(UUID id, String name, long createdAt, List<String> companions) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.companions = new ArrayList<>(companions);
    }

    /** A path beginning now, walked alone. */
    public static PlayerPath beginning(String name) {
        return new PlayerPath(UUID.randomUUID(), name, System.currentTimeMillis(), List.of());
    }

    /** A path beginning now, alongside the named players. */
    public static PlayerPath beginning(String name, List<String> companions) {
        return new PlayerPath(UUID.randomUUID(), name, System.currentTimeMillis(), companions);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public long createdAt() {
        return createdAt;
    }

    public List<String> companions() {
        return Collections.unmodifiableList(companions);
    }

    /** Whether this path answers to the given name, however it was typed. */
    public boolean isNamed(String candidate) {
        return name.equalsIgnoreCase(candidate);
    }

    @Override
    public String toString() {
        return name + " (" + id + ")";
    }
}
