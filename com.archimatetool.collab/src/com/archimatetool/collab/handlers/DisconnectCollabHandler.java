package com.archimatetool.collab.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

import com.archimatetool.collab.ArchiCollabPlugin;

/**
 * Disconnects the collaboration websocket session.
 */
public class DisconnectCollabHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        if(plugin != null && plugin.getSessionManager() != null) {
            plugin.getSessionManager().disconnect();
            ArchiCollabPlugin.logInfo("Collaboration websocket disconnected");
        }
        return null;
    }

    @Override
    public boolean isEnabled() {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        return plugin != null
                && plugin.getSessionManager() != null
                && plugin.getSessionManager().isConnected();
    }
}
