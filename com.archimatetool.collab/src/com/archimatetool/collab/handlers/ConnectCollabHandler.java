package com.archimatetool.collab.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.ui.ConnectCollabDialog;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IIdentifier;

/**
 * Opens collaboration connection dialog and connects websocket session.
 */
public class ConnectCollabHandler extends AbstractHandler {

    private static final String DEFAULT_WS_BASE_URL = "ws://localhost:8081";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        if(plugin == null || plugin.getSessionManager() == null) {
            return null;
        }

        CollabSessionManager sessionManager = plugin.getSessionManager();
        IArchimateModel model = getActiveModel(event);

        String defaultModelId = sessionManager.getCurrentModelId();
        if((defaultModelId == null || defaultModelId.isBlank()) && model instanceof IIdentifier) {
            defaultModelId = ((IIdentifier)model).getId();
        }

        ConnectCollabDialog dialog = new ConnectCollabDialog(
                HandlerUtil.getActiveShell(event),
                DEFAULT_WS_BASE_URL,
                defaultModelId,
                sessionManager.getUserId(),
                sessionManager.getSessionId());

        if(dialog.open() != Window.OK) {
            return null;
        }

        sessionManager.setActor(dialog.getUserId(), dialog.getSessionId());
        sessionManager.setServerBackedSession(true);
        sessionManager.connect(dialog.getWsBaseUrl(), dialog.getModelId());
        if(model != null && sessionManager.isConnected()) {
            sessionManager.attachModel(model);
        }

        return null;
    }

    @Override
    public boolean isEnabled() {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        return plugin != null
                && plugin.getSessionManager() != null
                && !plugin.getSessionManager().isConnected();
    }

    private IArchimateModel getActiveModel(ExecutionEvent event) {
        if(HandlerUtil.getActivePart(event) == null) {
            return null;
        }
        return HandlerUtil.getActivePart(event).getAdapter(IArchimateModel.class);
    }
}
