package com.archimatetool.collab.emf;

/**
 * Merge helpers for entity-level tombstone semantics.
 */
public final class CrdtEntityMerge {

    private CrdtEntityMerge() {
    }

    public static boolean shouldApplyCreate(CrdtPropertyMerge.Clock incoming, CrdtPropertyMerge.Clock tombstone) {
        return tombstone == null || CrdtPropertyMerge.wins(incoming, tombstone);
    }

    public static boolean shouldApplyUpdate(CrdtPropertyMerge.Clock incoming, CrdtPropertyMerge.Clock tombstone) {
        return shouldApplyCreate(incoming, tombstone);
    }

    public static boolean shouldAdvanceTombstone(CrdtPropertyMerge.Clock incoming, CrdtPropertyMerge.Clock existingTombstone) {
        return existingTombstone == null || CrdtPropertyMerge.wins(incoming, existingTombstone);
    }
}
