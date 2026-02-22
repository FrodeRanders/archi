package com.archimatetool.collab.emf;

import java.util.Map;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EContentAdapter;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IProperty;

/**
 * Captures local EMF changes and maps them to collaboration ops.
 */
public class EmfChangeCapture extends EContentAdapter {
    private static final long NOTATION_DEBOUNCE_MS = 180;

    private final OpMapper opMapper = new OpMapper();
    private final CollabSessionManager sessionManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "archi-collab-emf-debounce");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ScheduledFuture<?>> pendingNotationUpdates = new ConcurrentHashMap<>();

    public EmfChangeCapture(CollabSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void notifyChanged(Notification notification) {
        super.notifyChanged(notification);
        ArchiCollabPlugin.logTrace("EMF event type=" + eventTypeName(notification.getEventType())
                + " notifier=" + className(notification.getNotifier())
                + " feature=" + featureName(notification));

        if(RemoteApplyGuard.isRemoteApply()) {
            ArchiCollabPlugin.logTrace("EMF event ignored: remote apply guard active");
            return;
        }

        boolean hasCollabModelContext = sessionManager != null
                && sessionManager.getCurrentModelId() != null
                && !sessionManager.getCurrentModelId().isBlank();
        if(notification.getNotifier() == null
                || sessionManager == null
                || (!sessionManager.isConnected() && !hasCollabModelContext)) {
            ArchiCollabPlugin.logTrace("EMF event ignored: notifier/session missing or disconnected"
                    + " notifierNull=" + (notification.getNotifier() == null)
                    + " sessionNull=" + (sessionManager == null)
                    + " connected=" + (sessionManager != null && sessionManager.isConnected())
                    + " hasContext=" + hasCollabModelContext);
            return;
        }

        switch(notification.getEventType()) {
            case Notification.ADD -> handleAdd(notification, notification.getNewValue());
            case Notification.ADD_MANY -> handleAddMany(notification);
            case Notification.REMOVE -> handleRemove(notification, notification.getOldValue());
            case Notification.REMOVE_MANY -> handleRemoveMany(notification);
            case Notification.SET -> handleSet(notification);
            default -> {
                ArchiCollabPlugin.logTrace("EMF event ignored: unsupported event type " + notification.getEventType());
            }
        }
    }

    private void handleAddMany(Notification notification) {
        Object newValue = notification.getNewValue();
        if(newValue instanceof Collection<?> items) {
            for(Object item : items) {
                handleAdd(notification, item);
            }
            return;
        }
        ArchiCollabPlugin.logTrace("ADD_MANY ignored: payload is not a collection (" + className(newValue) + ")");
    }

    private void handleRemoveMany(Notification notification) {
        Object oldValue = notification.getOldValue();
        if(oldValue instanceof Collection<?> items) {
            for(Object item : items) {
                handleRemove(notification, item);
            }
            return;
        }
        ArchiCollabPlugin.logTrace("REMOVE_MANY ignored: payload is not a collection (" + className(oldValue) + ")");
    }

    private void handleAdd(Notification notification, Object newValue) {
        if(newValue instanceof IProperty property) {
            Object owner = property.eContainer() != null ? property.eContainer() : notification.getNotifier();
            String targetId = opMapper.targetIdForOwner(owner);
            if(targetId != null && property.getKey() != null && !property.getKey().isBlank()) {
                send(opMapper.toSetPropertySubmitOps(
                        targetId,
                        property.getKey(),
                        property.getValue(),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(add)");
            }
            return;
        }

        if(newValue instanceof IArchimateElement element) {
            send(opMapper.toCreateElementSubmitOps(
                    element,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateElement");
            return;
        }
        if(newValue instanceof IArchimateRelationship relationship) {
            send(opMapper.toCreateRelationshipSubmitOps(
                    relationship,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateRelationship");
            return;
        }
        if(newValue instanceof IArchimateDiagramModel view) {
            send(opMapper.toCreateViewSubmitOps(
                    view,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateView");
            return;
        }
        if(newValue instanceof IDiagramModelArchimateObject viewObject) {
            send(opMapper.toCreateViewObjectSubmitOps(
                    viewObject,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateViewObject");
            return;
        }
        if(newValue instanceof IDiagramModelArchimateConnection connection) {
            send(opMapper.toCreateConnectionSubmitOps(
                    connection,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateConnection");
            return;
        }
        ArchiCollabPlugin.logTrace("ADD ignored: no mapping for value type " + className(newValue));
    }

    private void handleRemove(Notification notification, Object oldValue) {
        if(oldValue instanceof IProperty property) {
            Object owner = property.eContainer() != null ? property.eContainer() : notification.getNotifier();
            String targetId = opMapper.targetIdForOwner(owner);
            String key = opMapper.key(property);
            if(targetId != null && key != null && !key.isBlank()) {
                send(opMapper.toUnsetPropertySubmitOps(
                        targetId,
                        key,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "UnsetProperty(remove)");
            }
            return;
        }

        if(oldValue instanceof IArchimateElement element) {
            send(opMapper.toDeleteElementSubmitOps(
                    element,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteElement");
            return;
        }
        if(oldValue instanceof IArchimateRelationship relationship) {
            send(opMapper.toDeleteRelationshipSubmitOps(
                    relationship,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteRelationship");
            return;
        }
        if(oldValue instanceof IArchimateDiagramModel view) {
            send(opMapper.toDeleteViewSubmitOps(
                    view,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteView");
            return;
        }
        if(oldValue instanceof IDiagramModelArchimateObject viewObject) {
            cancelPending("vo:" + viewObject.getId());
            send(opMapper.toDeleteViewObjectSubmitOps(
                    viewObject,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteViewObject");
            return;
        }
        if(oldValue instanceof IDiagramModelArchimateConnection connection) {
            cancelPending("conn:" + connection.getId());
            send(opMapper.toDeleteConnectionSubmitOps(
                    connection,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteConnection");
            return;
        }
        ArchiCollabPlugin.logTrace("REMOVE ignored: no mapping for value type " + className(oldValue));
    }

    private void handleSet(Notification notification) {
        Object notifier = notification.getNotifier();
        String featureName = featureName(notification);

        if(notifier instanceof IArchimateRelationship relationship) {
            boolean includeName = "name".equals(featureName);
            boolean includeDocumentation = "documentation".equals(featureName);
            boolean includeEndpoints = "source".equals(featureName) || "target".equals(featureName);
            if(includeName || includeDocumentation || includeEndpoints) {
                send(opMapper.toUpdateRelationshipSubmitOps(
                        relationship,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId(),
                        includeName,
                        includeDocumentation,
                        includeEndpoints),
                        "UpdateRelationship");
            } else {
                ArchiCollabPlugin.logTrace("SET ignored for relationship feature=" + featureName);
            }
            return;
        }

        if(notifier instanceof IArchimateElement element) {
            boolean includeName = "name".equals(featureName);
            boolean includeDocumentation = "documentation".equals(featureName);
            if(includeName || includeDocumentation) {
                send(opMapper.toUpdateElementSubmitOps(
                        element,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId(),
                        includeName,
                        includeDocumentation),
                        "UpdateElement");
            } else {
                ArchiCollabPlugin.logTrace("SET ignored for element feature=" + featureName);
            }
            return;
        }

        if(notifier instanceof IDiagramModel view) {
            boolean includeName = "name".equals(featureName);
            boolean includeDocumentation = "documentation".equals(featureName);
            if(includeName || includeDocumentation) {
                send(opMapper.toUpdateViewSubmitOps(
                        view,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId(),
                        includeName,
                        includeDocumentation),
                        "UpdateView");
                return;
            }
            if("viewpoint".equals(featureName) && notifier instanceof IArchimateDiagramModel archimateView && notifier instanceof IIdentifier identifier) {
                send(opMapper.toSetPropertySubmitOps(
                        "view:" + identifier.getId(),
                        "viewpoint",
                        archimateView.getViewpoint(),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(viewpoint)");
                return;
            }
            if("connectionRouterType".equals(featureName) && notifier instanceof IIdentifier identifier) {
                send(opMapper.toSetPropertySubmitOps(
                        "view:" + identifier.getId(),
                        "connectionRouterType",
                        view.getConnectionRouterType(),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(connectionRouterType)");
            } else {
                ArchiCollabPlugin.logTrace("SET ignored for view feature=" + featureName);
            }
            return;
        }

        if(notifier instanceof IDiagramModelArchimateObject viewObject) {
            scheduleNotationUpdate("vo:" + viewObject.getId(),
                    () -> send(opMapper.toUpdateViewObjectOpaqueSubmitOps(
                            viewObject,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "UpdateViewObjectOpaque"));
            return;
        }

        if(notifier instanceof IDiagramModelArchimateConnection connection) {
            scheduleNotationUpdate("conn:" + connection.getId(),
                    () -> send(opMapper.toUpdateConnectionOpaqueSubmitOps(
                            connection,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "UpdateConnectionOpaque"));
            return;
        }

        if(notifier instanceof IBounds bounds) {
            EObject container = bounds.eContainer();
            if(container instanceof IDiagramModelArchimateObject viewObject) {
                scheduleNotationUpdate("vo:" + viewObject.getId(),
                        () -> send(opMapper.toUpdateViewObjectOpaqueSubmitOps(
                                viewObject,
                                sessionManager.getCurrentModelId(),
                                sessionManager.getLastKnownRevision(),
                                sessionManager.getUserId(),
                                sessionManager.getSessionId()),
                                "UpdateViewObjectOpaque(bounds)"));
            }
            return;
        }

        if(notifier instanceof IDiagramModelBendpoint bendpoint) {
            EObject container = bendpoint.eContainer();
            if(container instanceof IDiagramModelConnection dmc && dmc instanceof IDiagramModelArchimateConnection connection) {
                scheduleNotationUpdate("conn:" + connection.getId(),
                        () -> send(opMapper.toUpdateConnectionOpaqueSubmitOps(
                                connection,
                                sessionManager.getCurrentModelId(),
                                sessionManager.getLastKnownRevision(),
                                sessionManager.getUserId(),
                                sessionManager.getSessionId()),
                                "UpdateConnectionOpaque(bendpoints)"));
            }
            return;
        }

        if(notifier instanceof IProperty property) {
            Object owner = property.eContainer();
            String targetId = opMapper.targetIdForOwner(owner);
            if(targetId == null) {
                ArchiCollabPlugin.logTrace("SET property ignored: could not resolve owner targetId");
                return;
            }
            String feature = featureName(notification);
            if("key".equals(feature)) {
                Object oldKey = notification.getOldValue();
                if(oldKey instanceof String oldKeyText && !oldKeyText.isBlank()) {
                    send(opMapper.toUnsetPropertySubmitOps(
                            targetId,
                            oldKeyText,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "UnsetProperty(key-changed)");
                }
            }
            String key = opMapper.key(property);
            if(key != null && !key.isBlank()) {
                send(opMapper.toSetPropertySubmitOps(
                        targetId,
                        key,
                        opMapper.value(property),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(update)");
            } else {
                ArchiCollabPlugin.logTrace("SET property ignored: key is blank");
            }
            return;
        }
        ArchiCollabPlugin.logTrace("SET ignored: no mapping for notifier type " + className(notifier) + " feature=" + featureName);
    }

    private String featureName(Notification notification) {
        Object feature = notification.getFeature();
        if(feature instanceof EStructuralFeature structuralFeature) {
            return structuralFeature.getName();
        }
        return "";
    }

    private void send(String submitOpsJson, String opLabel) {
        if(submitOpsJson == null || submitOpsJson.isBlank()) {
            ArchiCollabPlugin.logTrace("Submit skipped for opLabel=" + opLabel + " because mapped payload is empty");
            return;
        }
        sessionManager.sendSubmitOps(submitOpsJson);
        ArchiCollabPlugin.logInfo("Submitted " + opLabel + " op from local EMF capture");
    }

    private void scheduleNotationUpdate(String key, Runnable task) {
        cancelPending(key);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                task.run();
            }
            finally {
                pendingNotationUpdates.remove(key);
            }
        }, NOTATION_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pendingNotationUpdates.put(key, future);
    }

    private void cancelPending(String key) {
        ScheduledFuture<?> previous = pendingNotationUpdates.remove(key);
        if(previous != null) {
            previous.cancel(false);
        }
    }

    public void close() {
        pendingNotationUpdates.values().forEach(f -> f.cancel(false));
        pendingNotationUpdates.clear();
        scheduler.shutdownNow();
    }

    private String eventTypeName(int eventType) {
        return switch(eventType) {
            case Notification.ADD -> "ADD";
            case Notification.ADD_MANY -> "ADD_MANY";
            case Notification.REMOVE -> "REMOVE";
            case Notification.REMOVE_MANY -> "REMOVE_MANY";
            case Notification.SET -> "SET";
            default -> String.valueOf(eventType);
        };
    }

    private String className(Object object) {
        return object == null ? "null" : object.getClass().getSimpleName();
    }
}
