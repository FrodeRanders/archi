package com.archimatetool.collab.emf;

/**
 * Merge helpers for OR-Set style member add/remove clocks.
 */
public final class CrdtOrSet {

    private CrdtOrSet() {
    }

    public static CrdtPropertyMerge.Clock clock(long lamport, String clientId) {
        return CrdtPropertyMerge.clock(lamport, clientId);
    }

    public static boolean shouldApplyAdd(CrdtPropertyMerge.Clock incomingAdd,
                                         CrdtPropertyMerge.Clock existingAdd,
                                         CrdtPropertyMerge.Clock existingRemove) {
        if(existingRemove != null && !CrdtPropertyMerge.wins(incomingAdd, existingRemove)) {
            return false;
        }
        return existingAdd == null || CrdtPropertyMerge.wins(incomingAdd, existingAdd);
    }

    public static boolean shouldApplyRemove(CrdtPropertyMerge.Clock incomingRemove,
                                            CrdtPropertyMerge.Clock existingAdd,
                                            CrdtPropertyMerge.Clock existingRemove) {
        CrdtPropertyMerge.Clock baseline = max(existingAdd, existingRemove);
        return baseline == null || CrdtPropertyMerge.wins(incomingRemove, baseline);
    }

    public static boolean isPresent(CrdtPropertyMerge.Clock addClock, CrdtPropertyMerge.Clock removeClock) {
        if(addClock == null) {
            return false;
        }
        if(removeClock == null) {
            return true;
        }
        return CrdtPropertyMerge.wins(addClock, removeClock);
    }

    private static CrdtPropertyMerge.Clock max(CrdtPropertyMerge.Clock a, CrdtPropertyMerge.Clock b) {
        if(a == null) {
            return b;
        }
        if(b == null) {
            return a;
        }
        return CrdtPropertyMerge.wins(a, b) ? a : b;
    }
}
