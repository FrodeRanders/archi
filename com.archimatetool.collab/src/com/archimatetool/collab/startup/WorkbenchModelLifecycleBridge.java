package com.archimatetool.collab.startup;

import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.editor.actions.ModelSelectionHandler;
import com.archimatetool.model.IArchimateModel;

/**
 * Hooks the collaboration controller to the active Archi model in the workbench.
 */
public class WorkbenchModelLifecycleBridge implements IWindowListener, ModelSelectionHandler.IModelSelectionHandlerListener {

    private final CollabSessionManager sessionManager;

    private IWorkbench workbench;
    private IWorkbenchWindow activeWindow;
    private ModelSelectionHandler modelSelectionHandler;
    private IArchimateModel attachedModel;

    public WorkbenchModelLifecycleBridge(CollabSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void start() {
        if(!PlatformUI.isWorkbenchRunning()) {
            return;
        }

        runOnUiThreadAsync(() -> {
            if(workbench != null) {
                return;
            }

            workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(this);
            hookWindow(workbench.getActiveWorkbenchWindow());
            ArchiCollabPlugin.logInfo("Collaboration model lifecycle bridge started");
        });
    }

    public void stop() {
        runOnUiThreadSync(() -> {
            detachCurrentModel();
            disposeSelectionHandler();

            if(workbench != null) {
                workbench.removeWindowListener(this);
                workbench = null;
            }

            activeWindow = null;
        });
    }

    @Override
    public void updateState() {
        IArchimateModel model = modelSelectionHandler != null ? modelSelectionHandler.getActiveArchimateModel() : null;

        if(model == attachedModel) {
            return;
        }

        detachCurrentModel();

        if(model != null) {
            attachedModel = model;
            sessionManager.attachModel(model);
            ArchiCollabPlugin.logInfo("Collaboration attached to active model");
        }
    }

    @Override
    public void windowActivated(IWorkbenchWindow window) {
        hookWindow(window);
    }

    @Override
    public void windowOpened(IWorkbenchWindow window) {
        hookWindow(window);
    }

    @Override
    public void windowDeactivated(IWorkbenchWindow window) {
    }

    @Override
    public void windowClosed(IWorkbenchWindow window) {
        if(window == activeWindow) {
            detachCurrentModel();
            disposeSelectionHandler();
            activeWindow = null;
        }
    }

    private void hookWindow(IWorkbenchWindow window) {
        if(window == null || window == activeWindow) {
            return;
        }

        detachCurrentModel();
        disposeSelectionHandler();

        activeWindow = window;
        modelSelectionHandler = new ModelSelectionHandler(this, window);
        modelSelectionHandler.refresh();
    }

    private void disposeSelectionHandler() {
        if(modelSelectionHandler != null) {
            modelSelectionHandler.dispose();
            modelSelectionHandler = null;
        }
    }

    private void detachCurrentModel() {
        if(attachedModel != null) {
            sessionManager.detachModel();
            attachedModel = null;
            ArchiCollabPlugin.logInfo("Collaboration detached from active model");
        }
    }

    private void runOnUiThreadAsync(Runnable runnable) {
        Display display = resolveDisplay();
        if(display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if(display.isDisposed()) {
                return;
            }
            runnable.run();
        });
    }

    private void runOnUiThreadSync(Runnable runnable) {
        Display display = resolveDisplay();
        if(display == null || display.isDisposed()) {
            return;
        }

        if(Thread.currentThread() == display.getThread()) {
            runnable.run();
            return;
        }

        try {
            display.syncExec(() -> {
                if(display.isDisposed()) {
                    return;
                }
                runnable.run();
            });
        }
        catch(SWTException ex) {
            ArchiCollabPlugin.logDebug("Skipping lifecycle UI stop due to SWT shutdown: " + ex.getMessage());
        }
    }

    private Display resolveDisplay() {
        if(PlatformUI.isWorkbenchRunning()) {
            return PlatformUI.getWorkbench().getDisplay();
        }
        return Display.getCurrent();
    }
}
