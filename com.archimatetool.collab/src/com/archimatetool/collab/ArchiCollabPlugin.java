package com.archimatetool.collab;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.archimatetool.collab.startup.CollabStatusLineBridge;
import com.archimatetool.collab.startup.WorkbenchModelLifecycleBridge;
import com.archimatetool.collab.ws.CollabSessionManager;

/**
 * Collaboration plugin entry point.
 */
public class ArchiCollabPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.archimatetool.collab";

    private static ArchiCollabPlugin instance;
    private static volatile boolean debugEnabled =
            Boolean.parseBoolean(System.getProperty("archi.collab.debug", "false"))
                    || "true".equalsIgnoreCase(System.getenv("ARCHI_COLLAB_DEBUG"));
    private static volatile boolean traceEnabled =
            Boolean.parseBoolean(System.getProperty("archi.collab.trace", "false"))
                    || "true".equalsIgnoreCase(System.getenv("ARCHI_COLLAB_TRACE"));

    private CollabSessionManager sessionManager;
    private WorkbenchModelLifecycleBridge lifecycleBridge;
    private CollabStatusLineBridge statusLineBridge;
    private boolean lifecycleInitialized;

    public ArchiCollabPlugin() {
        instance = this;
    }

    public static ArchiCollabPlugin getInstance() {
        return instance;
    }

    public static void logInfo(String message) {
        if(instance != null) {
            instance.getLog().log(new Status(IStatus.INFO, PLUGIN_ID, message));
        }
    }

    public static void logDebug(String message) {
        if(debugEnabled) {
            logInfo("[DEBUG] " + message);
        }
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void logTrace(String message) {
        if(traceEnabled) {
            logInfo("[TRACE] " + message);
        }
    }

    public static boolean isTraceEnabled() {
        return traceEnabled;
    }

    public static void logError(String message, Throwable throwable) {
        if(instance != null) {
            instance.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, message, throwable));
        }
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        sessionManager = new CollabSessionManager();
        lifecycleBridge = new WorkbenchModelLifecycleBridge(sessionManager);
        statusLineBridge = new CollabStatusLineBridge(sessionManager);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if(lifecycleBridge != null) {
            try {
                lifecycleBridge.stop();
            }
            catch(Exception ex) {
                logError("Error stopping collaboration lifecycle bridge", ex);
            }
        }
        lifecycleBridge = null;

        if(statusLineBridge != null) {
            try {
                statusLineBridge.stop();
            }
            catch(Exception ex) {
                logError("Error stopping collaboration status bridge", ex);
            }
        }
        statusLineBridge = null;

        if(sessionManager != null) {
            try {
                sessionManager.disconnect();
            }
            catch(Exception ex) {
                logError("Error disconnecting collaboration session manager", ex);
            }
        }
        sessionManager = null;
        instance = null;
        super.stop(context);
    }

    public synchronized void initializeLifecycleIfNeeded() {
        if(lifecycleInitialized || lifecycleBridge == null) {
            return;
        }

        lifecycleBridge.start();
        if(statusLineBridge != null) {
            statusLineBridge.start();
        }
        lifecycleInitialized = true;
    }

    public CollabSessionManager getSessionManager() {
        return sessionManager;
    }
}
