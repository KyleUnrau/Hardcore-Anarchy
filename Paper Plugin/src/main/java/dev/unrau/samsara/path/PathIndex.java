package dev.unrau.samsara.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Which paths one account holds, and which of them is being walked.
 *
 * <p>Small, and deliberately so. Everything expensive about a path — where it stands, what it is
 * carrying, what has happened to it — lives in that path's own file. This is the part that has to
 * be read to answer "who are you at the moment", which is asked on every join.
 *
 * <p>The one invariant worth stating: <b>a player always has at least one path, and exactly one of
 * them is active.</b> Every rule the commands enforce follows from it — you cannot abandon the path
 * you are standing in, and you cannot abandon the last one, because either would leave an account
 * with nowhere to exist.
 */
public final class PathIndex {

    /** Schema version written by {@link PathStore}. Bumped when the on-disk layout changes. */
    public static final int CURRENT_DATA_VERSION = 1;

    private final List<PlayerPath> paths = new ArrayList<>();
    private UUID activePathId;

    /** An account that has just been given its first path. */
    public static PathIndex beginningWith(PlayerPath first) {
        PathIndex index = new PathIndex();
        index.add(first);
        index.activePathId = first.id();
        return index;
    }

    public List<PlayerPath> paths() {
        return Collections.unmodifiableList(paths);
    }

    public int size() {
        return paths.size();
    }

    public boolean isEmpty() {
        return paths.isEmpty();
    }

    public UUID activePathId() {
        return activePathId;
    }

    public void setActivePathId(UUID id) {
        this.activePathId = id;
    }

    /**
     * The path being walked, or null if the index does not name one that it also holds.
     *
     * <p>Null is a broken index rather than an ordinary state, and every caller repairs it rather
     * than working around it: an account with paths but no active one is an account whose player is
     * standing in the world as nobody.
     */
    public PlayerPath active() {
        return activePathId == null ? null : byId(activePathId);
    }

    public PlayerPath byId(UUID id) {
        for (PlayerPath path : paths) {
            if (path.id().equals(id)) return path;
        }
        return null;
    }

    /** The path of this name, whatever case it was typed in, or null if there is none. */
    public PlayerPath byName(String name) {
        if (name == null) return null;
        for (PlayerPath path : paths) {
            if (path.isNamed(name)) return path;
        }
        return null;
    }

    /** Whether this name is already taken, ignoring one path that is allowed to keep it. */
    public boolean nameTaken(String name, PlayerPath except) {
        PlayerPath holder = byName(name);
        return holder != null && holder != except;
    }

    public void add(PlayerPath path) {
        paths.add(path);
    }

    public boolean remove(PlayerPath path) {
        return paths.remove(path);
    }

    /** Every path except the one being walked, in the order they were created. */
    public List<PlayerPath> dormant() {
        List<PlayerPath> others = new ArrayList<>(paths);
        others.remove(active());
        return others;
    }
}
