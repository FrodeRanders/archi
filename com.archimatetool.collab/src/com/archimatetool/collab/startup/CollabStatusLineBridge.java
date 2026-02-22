package com.archimatetool.collab.startup;

import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.ws.CollabSessionManager;

/**
 * Keeps a small collaboration status indicator in the active part's status line.
 */
public class CollabStatusLineBridge implements IWindowListener, IPartListener,
        CollabSessionManager.SessionStateListener, CollabSessionManager.SubmitConflictListener {

    private static final String PREFIX = "Collab: ";
    private static final int CONFLICT_BANNER_TTL_MS = 10000;

    private final CollabSessionManager sessionManager;

    private IWorkbench workbench;
    private IWorkbenchWindow activeWindow;
    private volatile String conflictBannerMessage;
    private volatile long conflictBannerUntilEpochMs;

    public CollabStatusLineBridge(CollabSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void start() {
        if(!PlatformUI.isWorkbenchRunning()) {
            return;
        }

        sessionManager.addSessionStateListener(this);
        sessionManager.addSubmitConflictListener(this);

        runOnUiThreadAsync(() -> {
            workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(this);
            hookWindow(workbench.getActiveWorkbenchWindow());
            refreshStatusLine();
        });
    }

    public void stop() {
        sessionManager.removeSessionStateListener(this);
        sessionManager.removeSubmitConflictListener(this);

        runOnUiThreadSync(() -> {
            if(activeWindow != null) {
                activeWindow.getPartService().removePartListener(this);
                activeWindow = null;
            }

            if(workbench != null) {
                workbench.removeWindowListener(this);
                workbench = null;
            }
        });
    }

    @Override
    public void stateChanged(boolean connected, String modelId) {
        runOnUiThreadAsync(this::refreshStatusLine);
    }

    @Override
    public void conflictDetected(String modelId, String opBatchId, String code, String message) {
        conflictBannerMessage = "Conflict dropped (" + code + ")" + (modelId == null ? "" : " [" + modelId + "]");
        conflictBannerUntilEpochMs = System.currentTimeMillis() + CONFLICT_BANNER_TTL_MS;
        runOnUiThreadAsync(() -> {
            refreshStatusLine();
            scheduleConflictBannerClear();
        });
    }

    @Override
    public void windowActivated(IWorkbenchWindow window) {
        hookWindow(window);
        refreshStatusLine();
    }

    @Override
    public void windowOpened(IWorkbenchWindow window) {
        hookWindow(window);
        refreshStatusLine();
    }

    @Override
    public void windowDeactivated(IWorkbenchWindow window) {
    }

    @Override
    public void windowClosed(IWorkbenchWindow window) {
        if(activeWindow == window) {
            activeWindow.getPartService().removePartListener(this);
            activeWindow = null;
        }
    }

    @Override
    public void partActivated(IWorkbenchPart part) {
        refreshStatusLine(part);
    }

    @Override
    public void partBroughtToTop(IWorkbenchPart part) {
        refreshStatusLine(part);
    }

    @Override
    public void partOpened(IWorkbenchPart part) {
        refreshStatusLine(part);
    }

    @Override
    public void partClosed(IWorkbenchPart part) {
        refreshStatusLine();
    }

    @Override
    public void partDeactivated(IWorkbenchPart part) {
    }

    private void hookWindow(IWorkbenchWindow window) {
        if(window == null || window == activeWindow) {
            return;
        }

        if(activeWindow != null) {
            activeWindow.getPartService().removePartListener(this);
        }

        activeWindow = window;
        activeWindow.getPartService().addPartListener(this);
    }

    private void refreshStatusLine() {
        if(activeWindow == null) {
            return;
        }
        refreshStatusLine(activeWindow.getPartService().getActivePart());
    }

    private void refreshStatusLine(IWorkbenchPart part) {
        IStatusLineManager statusLineManager = getStatusLineManager(part);
        if(statusLineManager == null) {
            return;
        }

        String text;
        if(sessionManager.isConnected()) {
            String model = sessionManager.getCurrentModelId();
            text = PREFIX + "Connected" + (model != null ? " (" + model + ")" : "");
        }
        else {
            text = PREFIX + "Disconnected";
        }

        long now = System.currentTimeMillis();
        if(conflictBannerMessage != null && conflictBannerUntilEpochMs > now) {
            statusLineManager.setErrorMessage(conflictBannerMessage);
        }
        else {
            statusLineManager.setErrorMessage(null);
            conflictBannerMessage = null;
            conflictBannerUntilEpochMs = 0L;
        }
        statusLineManager.setMessage(text);
    }

    private void scheduleConflictBannerClear() {
        Display display = resolveDisplay();
        if(display == null || display.isDisposed()) {
            return;
        }
        long delayMs = Math.max(1L, conflictBannerUntilEpochMs - System.currentTimeMillis());
        int boundedDelay = (int)Math.min(Integer.MAX_VALUE, delayMs);
        display.timerExec(boundedDelay, this::refreshStatusLine);
    }

    private IStatusLineManager getStatusLineManager(IWorkbenchPart part) {
        if(part instanceof IEditorPart) {
            return ((IEditorPart)part).getEditorSite().getActionBars().getStatusLineManager();
        }
        if(part instanceof IViewPart) {
            return ((IViewPart)part).getViewSite().getActionBars().getStatusLineManager();
        }
        return null;
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
            ArchiCollabPlugin.logDebug("Skipping status-line UI stop due to SWT shutdown: " + ex.getMessage());
        }
    }

    private Display resolveDisplay() {
        if(PlatformUI.isWorkbenchRunning()) {
            return PlatformUI.getWorkbench().getDisplay();
        }
        return Display.getCurrent();
    }
}
