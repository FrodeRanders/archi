package com.archimatetool.collab.emf;

/**
 * Shared merge helpers for property-level CRDT decisions.
 */
public final class CrdtPropertyMerge {

    private CrdtPropertyMerge() {
    }

    public static Clock clock(long lamport, String clientId) {
        return new Clock(lamport, clientId == null ? "" : clientId);
    }

    public static boolean wins(Clock incoming, Clock existing) {
        if(incoming == null) {
            return false;
        }
        if(existing == null) {
            return true;
        }
        if(incoming.lamport() > existing.lamport()) {
            return true;
        }
        if(incoming.lamport() < existing.lamport()) {
            return false;
        }
        return incoming.clientId().compareTo(existing.clientId()) >= 0;
    }

    public static boolean shouldApplySet(Clock incoming, Clock existingValue, Clock tombstone) {
        if(tombstone != null && !wins(incoming, tombstone)) {
            return false;
        }
        return existingValue == null || wins(incoming, existingValue);
    }

    public static boolean shouldApplyUnset(Clock incoming, Clock existingValue, Clock tombstone) {
        Clock baseline = max(existingValue, tombstone);
        return baseline == null || wins(incoming, baseline);
    }

    private static Clock max(Clock a, Clock b) {
        if(a == null) {
            return b;
        }
        if(b == null) {
            return a;
        }
        return wins(a, b) ? a : b;
    }

    public record Clock(long lamport, String clientId) {
    }
}
