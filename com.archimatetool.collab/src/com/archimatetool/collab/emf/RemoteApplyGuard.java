package com.archimatetool.collab.emf;

/**
 * Guard used to suppress local capture while applying remote operations.
 */
public final class RemoteApplyGuard {

    private static final ThreadLocal<Boolean> REMOTE_APPLY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private RemoteApplyGuard() {
    }

    public static boolean isRemoteApply() {
        return REMOTE_APPLY.get();
    }

    public static void runAsRemoteApply(Runnable runnable) {
        Boolean previous = REMOTE_APPLY.get();
        REMOTE_APPLY.set(Boolean.TRUE);
        try {
            runnable.run();
        }
        finally {
            REMOTE_APPLY.set(previous);
        }
    }
}
