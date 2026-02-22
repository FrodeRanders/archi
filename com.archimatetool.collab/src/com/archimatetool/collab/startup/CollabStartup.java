package com.archimatetool.collab.startup;

import org.eclipse.ui.IStartup;

import com.archimatetool.collab.ArchiCollabPlugin;

/**
 * Early startup hook for collaboration wiring.
 */
public class CollabStartup implements IStartup {
    private final StartupServerModelBootstrap startupServerModelBootstrap = new StartupServerModelBootstrap();

    @Override
    public void earlyStartup() {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        if(plugin != null) {
            plugin.initializeLifecycleIfNeeded();
            startupServerModelBootstrap.maybeBootstrap(plugin);
        }
        ArchiCollabPlugin.logInfo("Collaboration startup initialized");
        // Auto-connect remains opt-in through startup pull configuration.
    }
}
