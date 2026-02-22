package com.archimatetool.collab.emf;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.notation.NotationDeserializer;
import com.archimatetool.collab.util.SimpleJson;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDocumentable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.util.ArchimateModelUtils;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.widgets.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Applies remote operations into the local EMF model.
 */
public class RemoteOpApplier {
    private static final long MAX_DEFERRED_AGE_MILLIS = TimeUnit.MINUTES.toMillis(3);
    private static final int MAX_DEFERRED_QUEUE_SIZE = 512;
    private static final int DEFERRED_RETRY_INTERVAL_MS = 200;

    private record DeferredOp(String opJson, long firstSeenAtMillis, int attempts) {}

    private final CollabSessionManager sessionManager;
    private final NotationDeserializer notationDeserializer = new NotationDeserializer();
    private final List<DeferredOp> deferredOps = new ArrayList<>();
    private boolean deferredRetryScheduled;

    public RemoteOpApplier(CollabSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void applyOpsEnvelope(String envelopeJson) {
        Display display = Display.getDefault();
        Runnable task = () -> RemoteApplyGuard.runAsRemoteApply(() -> applyOpsEnvelopeInternal(envelopeJson));
        if(display != null && !display.isDisposed()) {
            display.asyncExec(task);
        }
        else {
            task.run();
        }
    }

    public void applySnapshotEnvelope(String envelopeJson) {
        Display display = Display.getDefault();
        Runnable task = () -> RemoteApplyGuard.runAsRemoteApply(() -> applySnapshotEnvelopeInternal(envelopeJson));
        if(display != null && !display.isDisposed()) {
            display.asyncExec(task);
        }
        else {
            task.run();
        }
    }

    private void applySnapshotEnvelopeInternal(String envelopeJson) {
        if(envelopeJson == null || envelopeJson.isBlank()) {
            return;
        }

        syncRevisionHint(envelopeJson);

        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload"));
        if(payload == null) {
            ArchiCollabPlugin.logInfo("CheckoutSnapshot missing payload");
            return;
        }

        String snapshot = SimpleJson.asJsonObject(SimpleJson.readRawField(payload, "snapshot"));
        if(snapshot == null) {
            snapshot = payload;
        }

        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null) {
            ArchiCollabPlugin.logInfo("CheckoutSnapshot ignored: no active model attached");
            return;
        }

        clearDeferredOps();
        clearModelContents(model);

        int applied = 0;
        applied += applySnapshotArray(snapshot, "elements", "CreateElement", "element");
        applied += applySnapshotArray(snapshot, "relationships", "CreateRelationship", "relationship");
        applied += applySnapshotArray(snapshot, "views", "CreateView", "view");
        applied += applySnapshotArray(snapshot, "viewObjects", "CreateViewObject", "viewObject");
        applied += applySnapshotArray(snapshot, "connections", "CreateConnection", "connection");

        ArchiCollabPlugin.logInfo("Applied CheckoutSnapshot operations count=" + applied);
    }

    private void applyOpsEnvelopeInternal(String envelopeJson) {
        if(envelopeJson == null || envelopeJson.isBlank()) {
            return;
        }

        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload"));
        String opBatch = payload == null ? null : SimpleJson.asJsonObject(SimpleJson.readRawField(payload, "opBatch"));
        if(opBatch == null) {
            // Compatibility: some envelopes send the op-batch directly as payload.
            opBatch = asOpBatch(payload);
        }
        if(opBatch == null) {
            opBatch = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "opBatch"));
        }
        if(opBatch == null) {
            opBatch = asOpBatch(envelopeJson);
        }
        if(opBatch == null) {
            ArchiCollabPlugin.logInfo("OpsBroadcast missing opBatch payload");
            return;
        }

        syncRevisionHint(opBatch);

        List<String> ops = SimpleJson.readArrayObjectElements(opBatch, "ops");
        if(ops.isEmpty()) {
            return;
        }

        int applied = 0;
        for(String op : ops) {
            boolean ok = applyOp(op);
            if(ok) {
                applied++;
            }
            else {
                if(shouldDeferAfterFailure(op)) {
                    deferOp(op);
                }
                else {
                    ArchiCollabPlugin.logTrace("Remote op ignored/failed: " + summarizeOp(op));
                }
            }
        }
        applied += retryDeferredOps();
        scheduleDeferredRetryIfNeeded();
        if(applied > 0) {
            ArchiCollabPlugin.logInfo("Applied " + applied + " remote operation(s)");
        }
    }

    private boolean shouldDeferAfterFailure(String opJson) {
        String type = SimpleJson.readStringField(opJson, "type");
        if(type == null) {
            return false;
        }
        return switch(type) {
            case "CreateViewObject" -> shouldDeferCreateViewObject(opJson);
            case "CreateConnection" -> shouldDeferCreateConnection(opJson);
            case "UpdateViewObjectOpaque" -> shouldDeferViewObjectOpaque(opJson);
            case "UpdateConnectionOpaque" -> shouldDeferConnectionOpaque(opJson);
            default -> false;
        };
    }

    private boolean shouldDeferCreateViewObject(String opJson) {
        String viewObjectJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "viewObject"));
        if(viewObjectJson == null) {
            return false;
        }
        String viewObjectId = stripPrefix(SimpleJson.readStringField(viewObjectJson, "id"), "vo:");
        if(viewObjectId == null || findObjectById(viewObjectId) != null) {
            return false;
        }
        EObject viewEObject = findPrefixedObject(SimpleJson.readStringField(viewObjectJson, "viewId"));
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(viewObjectJson, "representsId"));
        return !(viewEObject instanceof IDiagramModel) || !(representsEObject instanceof IArchimateElement);
    }

    private boolean shouldDeferCreateConnection(String opJson) {
        String connectionJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "connection"));
        if(connectionJson == null) {
            return false;
        }
        String connectionId = stripPrefix(SimpleJson.readStringField(connectionJson, "id"), "conn:");
        if(connectionId == null || findObjectById(connectionId) != null) {
            return false;
        }
        EObject viewEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "viewId"));
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "representsId"));
        EObject sourceEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "sourceViewObjectId"));
        EObject targetEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "targetViewObjectId"));
        return !(viewEObject instanceof IDiagramModel)
                || !(representsEObject instanceof IArchimateRelationship)
                || !(sourceEObject instanceof IConnectable)
                || !(targetEObject instanceof IConnectable);
    }

    private boolean shouldDeferViewObjectOpaque(String opJson) {
        String viewObjectId = stripPrefix(SimpleJson.readStringField(opJson, "viewObjectId"), "vo:");
        return viewObjectId != null && findObjectById(viewObjectId) == null;
    }

    private boolean shouldDeferConnectionOpaque(String opJson) {
        String connectionId = stripPrefix(SimpleJson.readStringField(opJson, "connectionId"), "conn:");
        return connectionId != null && findObjectById(connectionId) == null;
    }

    private synchronized void deferOp(String opJson) {
        for(int i = 0; i < deferredOps.size(); i++) {
            DeferredOp deferred = deferredOps.get(i);
            if(deferred.opJson.equals(opJson)) {
                deferredOps.set(i, new DeferredOp(opJson, deferred.firstSeenAtMillis, deferred.attempts + 1));
                scheduleDeferredRetryIfNeeded();
                return;
            }
        }
        if(deferredOps.size() >= MAX_DEFERRED_QUEUE_SIZE) {
            DeferredOp dropped = deferredOps.remove(0);
            ArchiCollabPlugin.logTrace("Deferred queue full, dropping oldest remote op: " + summarizeOp(dropped.opJson));
        }
        deferredOps.add(new DeferredOp(opJson, System.currentTimeMillis(), 1));
        ArchiCollabPlugin.logTrace("Deferred remote op: " + summarizeOp(opJson));
        scheduleDeferredRetryIfNeeded();
    }

    private synchronized void clearDeferredOps() {
        deferredOps.clear();
    }

    private synchronized int retryDeferredOps() {
        if(deferredOps.isEmpty()) {
            return 0;
        }

        long now = System.currentTimeMillis();
        int applied = 0;
        List<DeferredOp> nextPass = new ArrayList<>();
        for(DeferredOp deferred : deferredOps) {
            if(applyOp(deferred.opJson)) {
                applied++;
                continue;
            }
            long ageMillis = now - deferred.firstSeenAtMillis;
            if(ageMillis >= MAX_DEFERRED_AGE_MILLIS) {
                ArchiCollabPlugin.logTrace("Deferred remote op dropped after timeout: "
                        + summarizeOp(deferred.opJson)
                        + " ageMs=" + ageMillis
                        + " attempts=" + deferred.attempts);
                continue;
            }
            nextPass.add(new DeferredOp(deferred.opJson, deferred.firstSeenAtMillis, deferred.attempts + 1));
        }
        deferredOps.clear();
        deferredOps.addAll(nextPass);
        return applied;
    }

    private void scheduleDeferredRetryIfNeeded() {
        Display display = Display.getDefault();
        if(display == null || display.isDisposed()) {
            return;
        }
        synchronized(this) {
            if(deferredOps.isEmpty() || deferredRetryScheduled) {
                return;
            }
            deferredRetryScheduled = true;
        }
        display.timerExec(DEFERRED_RETRY_INTERVAL_MS, () -> {
            synchronized(RemoteOpApplier.this) {
                deferredRetryScheduled = false;
            }
            if(display.isDisposed()) {
                return;
            }
            RemoteApplyGuard.runAsRemoteApply(() -> {
                int applied = retryDeferredOps();
                if(applied > 0) {
                    ArchiCollabPlugin.logInfo("Applied " + applied + " deferred remote operation(s)");
                }
                scheduleDeferredRetryIfNeeded();
            });
        });
    }

    private String asOpBatch(String jsonObject) {
        if(jsonObject == null) {
            return null;
        }
        if(SimpleJson.readRawField(jsonObject, "ops") == null) {
            return null;
        }
        if(SimpleJson.readRawField(jsonObject, "opBatchId") == null
                && SimpleJson.readRawField(jsonObject, "assignedRevisionRange") == null) {
            return null;
        }
        return jsonObject;
    }

    private void syncRevisionHint(String opBatchJson) {
        String assignedRange = SimpleJson.asJsonObject(SimpleJson.readRawField(opBatchJson, "assignedRevisionRange"));
        if(assignedRange == null) {
            return;
        }
        Long to = SimpleJson.readLongField(assignedRange, "to");
        if(to != null) {
            sessionManager.setLastKnownRevision(to);
        }
    }

    private boolean applyOp(String opJson) {
        String type = SimpleJson.readStringField(opJson, "type");
        if(type == null) {
            return false;
        }

        return switch(type) {
            case "CreateElement" -> applyCreateElement(opJson);
            case "UpdateElement" -> applyUpdateElement(opJson);
            case "DeleteElement" -> applyDeleteElement(opJson);
            case "CreateRelationship" -> applyCreateRelationship(opJson);
            case "UpdateRelationship" -> applyUpdateRelationship(opJson);
            case "DeleteRelationship" -> applyDeleteRelationship(opJson);
            case "CreateView" -> applyCreateView(opJson);
            case "UpdateView" -> applyUpdateView(opJson);
            case "DeleteView" -> applyDeleteView(opJson);
            case "CreateViewObject" -> applyCreateViewObject(opJson);
            case "DeleteViewObject" -> applyDeleteViewObject(opJson);
            case "CreateConnection" -> applyCreateConnection(opJson);
            case "DeleteConnection" -> applyDeleteConnection(opJson);
            case "SetProperty" -> applySetProperty(opJson);
            case "UnsetProperty" -> applyUnsetProperty(opJson);
            case "UpdateViewObjectOpaque" -> applyViewObjectOpaque(opJson);
            case "UpdateConnectionOpaque" -> applyConnectionOpaque(opJson);
            default -> false;
        };
    }

    private boolean applyCreateElement(String opJson) {
        String elementJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "element"));
        if(elementJson == null) {
            return false;
        }

        String elementId = stripPrefix(SimpleJson.readStringField(elementJson, "id"), "elem:");
        if(elementId == null || findObjectById(elementId) != null) {
            return false;
        }

        EObject created = createEObject(SimpleJson.readStringField(elementJson, "archimateType"));
        if(!(created instanceof IArchimateConcept concept)) {
            return false;
        }

        if(concept instanceof IIdentifier identifier) {
            identifier.setId(elementId);
        }
        if(concept instanceof INameable nameable) {
            setIfPresentName(nameable, elementJson, "name");
        }
        if(concept instanceof IDocumentable documentable) {
            setIfPresentDocumentation(documentable, elementJson, "documentation");
        }

        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null) {
            return false;
        }
        model.getDefaultFolderForObject(concept).getElements().add(concept);
        ArchiCollabPlugin.logTrace("Applied CreateElement id=elem:" + elementId + " name=" + getName(concept));
        return true;
    }

    private boolean applyUpdateElement(String opJson) {
        String elementId = stripPrefix(SimpleJson.readStringField(opJson, "elementId"), "elem:");
        String patchJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "patch"));
        EObject eObject = findObjectById(elementId);
        if(!(eObject instanceof IArchimateConcept concept) || patchJson == null) {
            return false;
        }

        if(concept instanceof INameable nameable) {
            setIfPresentName(nameable, patchJson, "name");
        }
        if(concept instanceof IDocumentable documentable) {
            setIfPresentDocumentation(documentable, patchJson, "documentation");
        }
        ArchiCollabPlugin.logTrace("Applied UpdateElement id=elem:" + elementId + " patch=" + summarizePatch(patchJson));
        return true;
    }

    private boolean applyDeleteElement(String opJson) {
        String elementId = stripPrefix(SimpleJson.readStringField(opJson, "elementId"), "elem:");
        EObject eObject = findObjectById(elementId);
        if(eObject == null) {
            return false;
        }
        EcoreUtil.delete(eObject, true);
        ArchiCollabPlugin.logTrace("Applied DeleteElement id=elem:" + elementId);
        return true;
    }

    private boolean applyCreateRelationship(String opJson) {
        String relationshipJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "relationship"));
        if(relationshipJson == null) {
            return false;
        }

        String relationshipId = stripPrefix(SimpleJson.readStringField(relationshipJson, "id"), "rel:");
        if(relationshipId == null || findObjectById(relationshipId) != null) {
            return false;
        }

        EObject created = createEObject(SimpleJson.readStringField(relationshipJson, "archimateType"));
        if(!(created instanceof IArchimateRelationship relationship)) {
            return false;
        }

        if(relationship instanceof IIdentifier identifier) {
            identifier.setId(relationshipId);
        }

        if(relationship instanceof INameable nameable) {
            setIfPresentName(nameable, relationshipJson, "name");
        }
        if(relationship instanceof IDocumentable documentable) {
            setIfPresentDocumentation(documentable, relationshipJson, "documentation");
        }

        EObject source = findPrefixedObject(SimpleJson.readStringField(relationshipJson, "sourceId"));
        EObject target = findPrefixedObject(SimpleJson.readStringField(relationshipJson, "targetId"));
        if(source instanceof IArchimateConcept sourceConcept) {
            relationship.setSource(sourceConcept);
        }
        if(target instanceof IArchimateConcept targetConcept) {
            relationship.setTarget(targetConcept);
        }

        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null) {
            return false;
        }
        model.getDefaultFolderForObject(relationship).getElements().add(relationship);
        ArchiCollabPlugin.logTrace("Applied CreateRelationship id=rel:" + relationshipId + " name=" + getName(relationship));
        return true;
    }

    private boolean applyUpdateRelationship(String opJson) {
        String relationshipId = stripPrefix(SimpleJson.readStringField(opJson, "relationshipId"), "rel:");
        String patchJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "patch"));
        EObject eObject = findObjectById(relationshipId);
        if(!(eObject instanceof IArchimateRelationship relationship) || patchJson == null) {
            return false;
        }

        if(relationship instanceof INameable nameable) {
            setIfPresentName(nameable, patchJson, "name");
        }
        if(relationship instanceof IDocumentable documentable) {
            setIfPresentDocumentation(documentable, patchJson, "documentation");
        }

        EObject source = findPrefixedObject(SimpleJson.readStringField(patchJson, "sourceId"));
        EObject target = findPrefixedObject(SimpleJson.readStringField(patchJson, "targetId"));
        if(source instanceof IArchimateConcept sourceConcept) {
            relationship.setSource(sourceConcept);
        }
        if(target instanceof IArchimateConcept targetConcept) {
            relationship.setTarget(targetConcept);
        }
        ArchiCollabPlugin.logTrace("Applied UpdateRelationship id=rel:" + relationshipId + " patch=" + summarizePatch(patchJson));
        return true;
    }

    private boolean applyDeleteRelationship(String opJson) {
        String relationshipId = stripPrefix(SimpleJson.readStringField(opJson, "relationshipId"), "rel:");
        EObject eObject = findObjectById(relationshipId);
        if(eObject == null) {
            return false;
        }
        EcoreUtil.delete(eObject, true);
        ArchiCollabPlugin.logTrace("Applied DeleteRelationship id=rel:" + relationshipId);
        return true;
    }

    private boolean applyCreateView(String opJson) {
        String viewJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "view"));
        if(viewJson == null) {
            return false;
        }

        String viewId = stripPrefix(SimpleJson.readStringField(viewJson, "id"), "view:");
        if(viewId == null || findObjectById(viewId) != null) {
            return false;
        }

        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        view.setId(viewId);
        setIfPresentName(view, viewJson, "name");
        if(view instanceof IDocumentable documentable) {
            setIfPresentDocumentation(documentable, viewJson, "documentation");
        }

        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null) {
            return false;
        }
        model.getDefaultFolderForObject(view).getElements().add(view);
        ArchiCollabPlugin.logTrace("Applied CreateView id=view:" + viewId + " name=" + getName(view));
        return true;
    }

    private boolean applyUpdateView(String opJson) {
        String viewId = stripPrefix(SimpleJson.readStringField(opJson, "viewId"), "view:");
        String patchJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "patch"));
        EObject eObject = findObjectById(viewId);
        if(!(eObject instanceof IDiagramModel view) || patchJson == null) {
            return false;
        }

        if(view instanceof INameable nameable) {
            setIfPresentName(nameable, patchJson, "name");
        }
        if(view instanceof IDocumentable documentable) {
            setIfPresentDocumentation(documentable, patchJson, "documentation");
        }
        ArchiCollabPlugin.logTrace("Applied UpdateView id=view:" + viewId + " patch=" + summarizePatch(patchJson));
        return true;
    }

    private boolean applyDeleteView(String opJson) {
        String viewId = stripPrefix(SimpleJson.readStringField(opJson, "viewId"), "view:");
        EObject eObject = findObjectById(viewId);
        if(eObject == null) {
            return false;
        }
        EcoreUtil.delete(eObject, true);
        ArchiCollabPlugin.logTrace("Applied DeleteView id=view:" + viewId);
        return true;
    }

    private boolean applyCreateViewObject(String opJson) {
        String viewObjectJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "viewObject"));
        if(viewObjectJson == null) {
            return false;
        }

        String viewObjectId = stripPrefix(SimpleJson.readStringField(viewObjectJson, "id"), "vo:");
        if(viewObjectId == null || findObjectById(viewObjectId) != null) {
            return false;
        }

        EObject viewEObject = findPrefixedObject(SimpleJson.readStringField(viewObjectJson, "viewId"));
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(viewObjectJson, "representsId"));
        if(!(viewEObject instanceof IDiagramModel view) || !(representsEObject instanceof IArchimateElement archimateElement)) {
            return false;
        }

        IDiagramModelArchimateObject viewObject = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        viewObject.setId(viewObjectId);
        viewObject.setArchimateElement(archimateElement);

        String notationJson = SimpleJson.asJsonObject(SimpleJson.readRawField(viewObjectJson, "notationJson"));
        if(notationJson != null) {
            notationDeserializer.applyViewObjectNotation(viewObject, notationJson);
        }
        // Archi edit parts expect bounds to be non-null when the object is added to the view.
        if(viewObject.getBounds() == null) {
            viewObject.setBounds(10, 10, 120, 55);
        }

        view.getChildren().add(viewObject);
        ArchiCollabPlugin.logTrace("Applied CreateViewObject id=vo:" + viewObjectId + " represents=" + SimpleJson.readStringField(viewObjectJson, "representsId"));
        return true;
    }

    private boolean applyDeleteViewObject(String opJson) {
        String viewObjectId = stripPrefix(SimpleJson.readStringField(opJson, "viewObjectId"), "vo:");
        EObject eObject = findObjectById(viewObjectId);
        if(eObject == null) {
            return false;
        }
        EcoreUtil.delete(eObject, true);
        ArchiCollabPlugin.logTrace("Applied DeleteViewObject id=vo:" + viewObjectId);
        return true;
    }

    private boolean applyCreateConnection(String opJson) {
        String connectionJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "connection"));
        if(connectionJson == null) {
            return false;
        }

        String connectionId = stripPrefix(SimpleJson.readStringField(connectionJson, "id"), "conn:");
        if(connectionId == null || findObjectById(connectionId) != null) {
            return false;
        }

        EObject viewEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "viewId"));
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "representsId"));
        EObject sourceEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "sourceViewObjectId"));
        EObject targetEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "targetViewObjectId"));
        if(!(viewEObject instanceof IDiagramModel)
                || !(representsEObject instanceof IArchimateRelationship relationship)
                || !(sourceEObject instanceof IConnectable source)
                || !(targetEObject instanceof IConnectable target)) {
            return false;
        }

        IDiagramModelArchimateConnection connection = IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        connection.setId(connectionId);
        connection.setArchimateRelationship(relationship);
        connection.connect(source, target);

        String notationJson = SimpleJson.asJsonObject(SimpleJson.readRawField(connectionJson, "notationJson"));
        if(notationJson != null) {
            notationDeserializer.applyConnectionNotation(connection, notationJson);
        }
        ArchiCollabPlugin.logTrace("Applied CreateConnection id=conn:" + connectionId + " represents=" + SimpleJson.readStringField(connectionJson, "representsId"));
        return true;
    }

    private boolean applyDeleteConnection(String opJson) {
        String connectionId = stripPrefix(SimpleJson.readStringField(opJson, "connectionId"), "conn:");
        EObject eObject = findObjectById(connectionId);
        if(eObject == null) {
            return false;
        }
        EcoreUtil.delete(eObject, true);
        ArchiCollabPlugin.logTrace("Applied DeleteConnection id=conn:" + connectionId);
        return true;
    }

    private boolean applySetProperty(String opJson) {
        String targetId = SimpleJson.readStringField(opJson, "targetId");
        String key = SimpleJson.readStringField(opJson, "key");
        if(targetId == null || key == null || key.isBlank()) {
            return false;
        }
        EObject eObject = findPrefixedObject(targetId);
        if(!(eObject instanceof IProperties properties)) {
            return false;
        }
        String value = SimpleJson.hasField(opJson, "value") ? SimpleJson.readStringField(opJson, "value") : null;

        for(IProperty property : properties.getProperties()) {
            if(key.equals(property.getKey())) {
                property.setValue(value);
                ArchiCollabPlugin.logTrace("Applied SetProperty target=" + targetId + " key=" + key + " value=" + value);
                return true;
            }
        }

        IProperty property = IArchimateFactory.eINSTANCE.createProperty();
        property.setKey(key);
        property.setValue(value);
        properties.getProperties().add(property);
        ArchiCollabPlugin.logTrace("Applied SetProperty target=" + targetId + " key=" + key + " value=" + value + " (new)");
        return true;
    }

    private boolean applyUnsetProperty(String opJson) {
        String targetId = SimpleJson.readStringField(opJson, "targetId");
        String key = SimpleJson.readStringField(opJson, "key");
        if(targetId == null || key == null || key.isBlank()) {
            return false;
        }
        EObject eObject = findPrefixedObject(targetId);
        if(!(eObject instanceof IProperties properties)) {
            return false;
        }

        IProperty match = null;
        for(IProperty property : properties.getProperties()) {
            if(key.equals(property.getKey())) {
                match = property;
                break;
            }
        }
        if(match != null) {
            properties.getProperties().remove(match);
            ArchiCollabPlugin.logTrace("Applied UnsetProperty target=" + targetId + " key=" + key);
            return true;
        }
        return false;
    }

    private boolean applyViewObjectOpaque(String opJson) {
        String viewObjectId = stripPrefix(SimpleJson.readStringField(opJson, "viewObjectId"), "vo:");
        String notationJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "notationJson"));
        if(viewObjectId == null || notationJson == null) {
            return false;
        }

        EObject eObject = findObjectById(viewObjectId);
        if(!(eObject instanceof IDiagramModelArchimateObject viewObject)) {
            return false;
        }
        notationDeserializer.applyViewObjectNotation(viewObject, notationJson);
        ArchiCollabPlugin.logTrace("Applied UpdateViewObjectOpaque id=vo:" + viewObjectId);
        return true;
    }

    private boolean applyConnectionOpaque(String opJson) {
        String connectionId = stripPrefix(SimpleJson.readStringField(opJson, "connectionId"), "conn:");
        String notationJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "notationJson"));
        if(connectionId == null || notationJson == null) {
            return false;
        }

        EObject eObject = findObjectById(connectionId);
        if(!(eObject instanceof IDiagramModelArchimateConnection connection)) {
            return false;
        }
        notationDeserializer.applyConnectionNotation(connection, notationJson);
        ArchiCollabPlugin.logTrace("Applied UpdateConnectionOpaque id=conn:" + connectionId);
        return true;
    }

    private EObject findObjectById(String id) {
        IArchimateModel model = sessionManager.getAttachedModel();
        return ArchimateModelUtils.getObjectByID(model, id);
    }

    private EObject findPrefixedObject(String prefixedId) {
        if(prefixedId == null) {
            return null;
        }
        if(prefixedId.startsWith("elem:")) {
            return findObjectById(prefixedId.substring("elem:".length()));
        }
        if(prefixedId.startsWith("rel:")) {
            return findObjectById(prefixedId.substring("rel:".length()));
        }
        if(prefixedId.startsWith("view:")) {
            return findObjectById(prefixedId.substring("view:".length()));
        }
        if(prefixedId.startsWith("vo:")) {
            return findObjectById(prefixedId.substring("vo:".length()));
        }
        if(prefixedId.startsWith("conn:")) {
            return findObjectById(prefixedId.substring("conn:".length()));
        }
        return findObjectById(prefixedId);
    }

    private EObject createEObject(String archimateType) {
        if(archimateType == null || archimateType.isBlank()) {
            return null;
        }
        EClassifier classifier = IArchimatePackage.eINSTANCE.getEClassifier(archimateType);
        if(!(classifier instanceof EClass eClass)) {
            return null;
        }
        return IArchimateFactory.eINSTANCE.create(eClass);
    }

    private void setIfPresentName(INameable nameable, String json, String key) {
        if(SimpleJson.hasField(json, key)) {
            nameable.setName(SimpleJson.readStringField(json, key));
        }
    }

    private void setIfPresentDocumentation(IDocumentable documentable, String json, String key) {
        if(SimpleJson.hasField(json, key)) {
            documentable.setDocumentation(SimpleJson.readStringField(json, key));
        }
    }

    private String getName(Object object) {
        return object instanceof INameable nameable ? nameable.getName() : "";
    }

    private String summarizePatch(String patchJson) {
        if(patchJson == null) {
            return "{}";
        }
        String name = SimpleJson.readStringField(patchJson, "name");
        String sourceId = SimpleJson.readStringField(patchJson, "sourceId");
        String targetId = SimpleJson.readStringField(patchJson, "targetId");
        return "{name=" + name + ",sourceId=" + sourceId + ",targetId=" + targetId + "}";
    }

    private String summarizeOp(String opJson) {
        if(opJson == null) {
            return "null";
        }
        String type = SimpleJson.readStringField(opJson, "type");
        StringBuilder summary = new StringBuilder("type=").append(type);
        String elementId = SimpleJson.readStringField(opJson, "elementId");
        String relationshipId = SimpleJson.readStringField(opJson, "relationshipId");
        String viewId = SimpleJson.readStringField(opJson, "viewId");
        String viewObjectId = SimpleJson.readStringField(opJson, "viewObjectId");
        String connectionId = SimpleJson.readStringField(opJson, "connectionId");
        if(elementId != null) summary.append(" elementId=").append(elementId);
        if(relationshipId != null) summary.append(" relationshipId=").append(relationshipId);
        if(viewId != null) summary.append(" viewId=").append(viewId);
        if(viewObjectId != null) summary.append(" viewObjectId=").append(viewObjectId);
        if(connectionId != null) summary.append(" connectionId=").append(connectionId);
        return summary.toString();
    }

    private String stripPrefix(String value, String prefix) {
        if(value == null) {
            return null;
        }
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private int applySnapshotArray(String snapshotJson, String arrayKey, String opType, String opFieldName) {
        List<String> items = SimpleJson.readArrayObjectElements(snapshotJson, arrayKey);
        int applied = 0;
        for(String item : items) {
            String opJson = "{\"type\":\"" + opType + "\",\"" + opFieldName + "\":" + item + "}";
            if(applyOp(opJson)) {
                applied++;
            }
            else {
                ArchiCollabPlugin.logTrace("Snapshot op ignored/failed: " + summarizeOp(opJson));
            }
        }
        return applied;
    }

    private void clearModelContents(IArchimateModel model) {
        List<EObject> views = new ArrayList<>();
        List<EObject> concepts = new ArrayList<>();

        for(var iter = model.eAllContents(); iter.hasNext();) {
            EObject object = iter.next();
            if(object instanceof IDiagramModel) {
                views.add(object);
            }
            else if(object instanceof IArchimateConcept) {
                concepts.add(object);
            }
        }

        // Delete view content first so diagram edit parts never observe dangling view objects.
        for(EObject view : views) {
            EcoreUtil.delete(view, true);
        }

        for(EObject concept : concepts) {
            EcoreUtil.delete(concept, true);
        }
    }

}
