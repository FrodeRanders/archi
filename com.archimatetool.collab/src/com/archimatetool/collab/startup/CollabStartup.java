package com.archimatetool.collab.startup;

import org.eclipse.ui.IStartup;

import com.archimatetool.collab.ArchiCollabPlugin;

/**
 * Early startup hook for collaboration wiring.
 */
public class CollabStartup implements IStartup {

    @Override
    public void earlyStartup() {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        if(plugin != null) {
            plugin.initializeLifecycleIfNeeded();
        }
        ArchiCollabPlugin.logInfo("Collaboration startup initialized");
        // Intentionally no auto-connect by default. Session connect is explicit per model.
    }
}
