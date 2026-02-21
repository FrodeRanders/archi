package com.archimatetool.collab.emf;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Binds EMF capture to a model and sends mapped ops through the websocket session manager.
 */
public class ModelCollaborationController {

    private final CollabSessionManager sessionManager;
    private EmfChangeCapture changeCapture;
    private IArchimateModel model;

    public ModelCollaborationController(CollabSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void attach(IArchimateModel model) {
        detach();
        this.changeCapture = new EmfChangeCapture(sessionManager);
        this.model = model;
        this.model.eAdapters().add(changeCapture);
        ArchiCollabPlugin.logInfo("Attached collaboration change capture to model");
    }

    public void detach() {
        if(model != null) {
            if(changeCapture != null) {
                model.eAdapters().remove(changeCapture);
            }
            model = null;
        }
        if(changeCapture != null) {
            changeCapture.close();
            changeCapture = null;
        }
    }
}
