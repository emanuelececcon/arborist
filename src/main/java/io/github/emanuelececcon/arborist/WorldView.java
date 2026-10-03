package io.github.emanuelececcon.arborist;

import org.bukkit.Material;

/** Read-only access to the blocks of one world, so the tree analysis can be tested without a server. */
interface WorldView {

    /** The block type, AIR outside the build height, or null when its chunk is not loaded. */
    Material type(int x, int y, int z);

    /** Whether the leaves at this position are persistent (placed by a player). Only asked for leaves. */
    boolean persistent(int x, int y, int z);

    /** Whether the log at this position was placed by a player. Only asked for natural logs. */
    boolean placed(int x, int y, int z);

    default Material type(long pos) {
        return type(Pos.x(pos), Pos.y(pos), Pos.z(pos));
    }

    default boolean persistent(long pos) {
        return persistent(Pos.x(pos), Pos.y(pos), Pos.z(pos));
    }

    default boolean placed(long pos) {
        return placed(Pos.x(pos), Pos.y(pos), Pos.z(pos));
    }
}
