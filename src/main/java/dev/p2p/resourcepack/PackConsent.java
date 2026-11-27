package dev.p2p.resourcepack;

import java.util.UUID;

/** A one-time choice for one player and one revision of the room's pack list. */
final class PackConsent {
    private final UUID id = UUID.randomUUID();
    private boolean pending = true;

    UUID id() { return id; }

    boolean pending() { return pending; }

    boolean choose(UUID requested) {
        if (!pending || !id.equals(requested)) return false;
        pending = false;
        return true;
    }

    static boolean required(boolean knownPrivateRoom, boolean configured, boolean owner) {
        return knownPrivateRoom && configured && !owner;
    }
}
