package com.archimatetool.collab.emf;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.notation.NotationDeserializer;
import com.archimatetool.collab.util.SimpleJson;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.editor.ui.services.EditorManager;
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
import com.archimatetool.model.IDiagramModelContainer;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final Map<String, FieldClock> viewObjectNotationClocks = new HashMap<>();
    private final Map<String, FieldClock> connectionNotationClocks = new HashMap<>();
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
            case "CreateRelationship" -> shouldDeferCreateRelationship(opJson);
            case "UpdateRelationship" -> shouldDeferUpdateRelationship(opJson);
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
        IDiagramModel view = resolveViewForOp(SimpleJson.readStringField(viewObjectJson, "viewId"), false);
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(viewObjectJson, "representsId"));
        return view == null || !(representsEObject instanceof IArchimateElement);
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
        IDiagramModel view = resolveViewForOp(SimpleJson.readStringField(connectionJson, "viewId"), false);
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "representsId"));
        EObject sourceEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "sourceViewObjectId"));
        EObject targetEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "targetViewObjectId"));
        return view == null
                || !(representsEObject instanceof IArchimateRelationship)
                || !(sourceEObject instanceof IConnectable)
                || !(targetEObject instanceof IConnectable);
    }

    private boolean shouldDeferCreateRelationship(String opJson) {
        String relationshipJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "relationship"));
        if(relationshipJson == null) {
            return false;
        }

        String relationshipId = stripPrefix(SimpleJson.readStringField(relationshipJson, "id"), "rel:");
        if(relationshipId == null || findObjectById(relationshipId) != null) {
            return false;
        }

        EObject sourceEObject = findPrefixedObject(SimpleJson.readStringField(relationshipJson, "sourceId"));
        EObject targetEObject = findPrefixedObject(SimpleJson.readStringField(relationshipJson, "targetId"));
        return !(sourceEObject instanceof IArchimateConcept) || !(targetEObject instanceof IArchimateConcept);
    }

    private boolean shouldDeferUpdateRelationship(String opJson) {
        String relationshipId = stripPrefix(SimpleJson.readStringField(opJson, "relationshipId"), "rel:");
        EObject relationshipEObject = relationshipId == null ? null : findObjectById(relationshipId);
        if(!(relationshipEObject instanceof IArchimateRelationship)) {
            return relationshipId != null;
        }

        String patchJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "patch"));
        if(patchJson == null) {
            return false;
        }

        if(SimpleJson.hasField(patchJson, "sourceId")) {
            EObject sourceEObject = findPrefixedObject(SimpleJson.readStringField(patchJson, "sourceId"));
            if(!(sourceEObject instanceof IArchimateConcept)) {
                return true;
            }
        }
        if(SimpleJson.hasField(patchJson, "targetId")) {
            EObject targetEObject = findPrefixedObject(SimpleJson.readStringField(patchJson, "targetId"));
            if(!(targetEObject instanceof IArchimateConcept)) {
                return true;
            }
        }
        return false;
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
        if(eObject instanceof IArchimateConcept concept) {
            // Delete all diagram components that reference this concept first.
            // This prevents dangling view objects/connections that later fail model validation on save.
            List<EObject> referencingComponents = new ArrayList<>();
            for(var component : concept.getReferencingDiagramComponents()) {
                if(component instanceof EObject eo) {
                    referencingComponents.add(eo);
                }
            }
            for(EObject referencing : referencingComponents) {
                EcoreUtil.delete(referencing, true);
            }

            // Defensive cleanup: ensure no relationship remains with a missing endpoint.
            List<IArchimateRelationship> relationships = new ArrayList<>(ArchimateModelUtils.getAllRelationshipsForConcept(concept));
            for(IArchimateRelationship relationship : relationships) {
                deleteRelationshipAndReferences(relationship);
            }
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
        if(!(source instanceof IArchimateConcept sourceConcept) || !(target instanceof IArchimateConcept targetConcept)) {
            return false;
        }
        relationship.setSource(sourceConcept);
        relationship.setTarget(targetConcept);

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

        if(SimpleJson.hasField(patchJson, "sourceId")) {
            EObject source = findPrefixedObject(SimpleJson.readStringField(patchJson, "sourceId"));
            if(!(source instanceof IArchimateConcept sourceConcept)) {
                return false;
            }
            relationship.setSource(sourceConcept);
        }

        if(SimpleJson.hasField(patchJson, "targetId")) {
            EObject target = findPrefixedObject(SimpleJson.readStringField(patchJson, "targetId"));
            if(!(target instanceof IArchimateConcept targetConcept)) {
                return false;
            }
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
        if(eObject instanceof IArchimateRelationship relationship) {
            deleteRelationshipAndReferences(relationship);
        }
        else {
            EcoreUtil.delete(eObject, true);
        }
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
        if(eObject instanceof IDiagramModel diagramModel) {
            // Keep UI consistent: if a deleted view is open in an editor, close it.
            EditorManager.closeDiagramEditor(diagramModel);
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

        IDiagramModel view = resolveViewForOp(SimpleJson.readStringField(viewObjectJson, "viewId"), true);
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(viewObjectJson, "representsId"));
        if(view == null || !(representsEObject instanceof IArchimateElement archimateElement)) {
            return false;
        }

        IDiagramModelArchimateObject viewObject = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        viewObject.setId(viewObjectId);
        viewObject.setArchimateElement(archimateElement);

        String notationJson = SimpleJson.asJsonObject(SimpleJson.readRawField(viewObjectJson, "notationJson"));
        if(notationJson != null) {
            notationDeserializer.applyViewObjectNotation(viewObject, notationJson);
            seedGeometryClock(viewObjectId, notationJson, opJson);
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
        if(eObject instanceof IDiagramModelArchimateObject viewObject && viewObject.getArchimateElement() == null) {
            // Do not keep dangling objects in the model. Remove from parent container if possible.
            if(viewObject.eContainer() instanceof IDiagramModelContainer container) {
                container.getChildren().remove(viewObject);
            }
            else {
                EcoreUtil.delete(eObject, true);
            }
            viewObjectNotationClocks.remove(viewObjectId);
            ArchiCollabPlugin.logTrace("Removed dangling view object id=vo:" + viewObjectId);
            return true;
        }
        EcoreUtil.delete(eObject, true);
        viewObjectNotationClocks.remove(viewObjectId);
        ArchiCollabPlugin.logTrace("Applied DeleteViewObject id=vo:" + viewObjectId);
        return true;
    }

    private void deleteRelationshipAndReferences(IArchimateRelationship relationship) {
        List<IDiagramModelArchimateConnection> referencingConnections =
                new ArrayList<>(relationship.getReferencingDiagramConnections());
        for(IDiagramModelArchimateConnection connection : referencingConnections) {
            if(connection instanceof EObject eo) {
                EcoreUtil.delete(eo, true);
            }
        }
        EcoreUtil.delete(relationship, true);
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

        IDiagramModel view = resolveViewForOp(SimpleJson.readStringField(connectionJson, "viewId"), true);
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "representsId"));
        EObject sourceEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "sourceViewObjectId"));
        EObject targetEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "targetViewObjectId"));
        if(view == null
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
            seedConnectionClock(connectionId, notationJson, opJson);
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
        connectionNotationClocks.remove(connectionId);
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
        String effectiveNotation = applyViewObjectNotationLww(viewObjectId, viewObject, notationJson, opJson);
        notationDeserializer.applyViewObjectNotation(viewObject, effectiveNotation);
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
        String effectiveNotation = applyConnectionNotationLww(connectionId, connection, notationJson, opJson);
        notationDeserializer.applyConnectionNotation(connection, effectiveNotation);
        ArchiCollabPlugin.logTrace("Applied UpdateConnectionOpaque id=conn:" + connectionId);
        return true;
    }

    private EObject findObjectById(String id) {
        IArchimateModel model = sessionManager.getAttachedModel();
        return ArchimateModelUtils.getObjectByID(model, id);
    }

    private IDiagramModel resolveViewForOp(String prefixedViewId, boolean reconcileId) {
        EObject direct = findPrefixedObject(prefixedViewId);
        if(direct instanceof IDiagramModel diagramModel) {
            return diagramModel;
        }

        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null) {
            return null;
        }

        List<IDiagramModel> candidates = new ArrayList<>();
        for(var it = model.eAllContents(); it.hasNext();) {
            EObject object = it.next();
            if(object instanceof IDiagramModel diagramModel) {
                candidates.add(diagramModel);
            }
        }
        if(candidates.isEmpty()) {
            return null;
        }

        IDiagramModel candidate = null;
        if(candidates.size() == 1) {
            candidate = candidates.get(0);
        }
        else {
            IDiagramModel defaultViewCandidate = null;
            for(IDiagramModel view : candidates) {
                if(view instanceof INameable nameable && "Default View".equals(nameable.getName())) {
                    if(defaultViewCandidate != null) {
                        defaultViewCandidate = null;
                        break;
                    }
                    defaultViewCandidate = view;
                }
            }
            candidate = defaultViewCandidate;
        }
        if(candidate == null) {
            return null;
        }

        if(reconcileId && candidate instanceof IIdentifier identifier) {
            String targetId = stripPrefix(prefixedViewId, "view:");
            if(targetId != null
                    && !targetId.isBlank()
                    && !targetId.equals(identifier.getId())
                    && findObjectById(targetId) == null) {
                String oldId = identifier.getId();
                identifier.setId(targetId);
                ArchiCollabPlugin.logInfo("Reconciled local view identity old=view:" + oldId + " new=view:" + targetId);
            }
        }
        return candidate;
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

    private String getDocumentation(Object object) {
        return object instanceof IDocumentable documentable ? documentable.getDocumentation() : "";
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
        viewObjectNotationClocks.clear();
        connectionNotationClocks.clear();
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

    private void seedGeometryClock(String viewObjectId, String notationJson, String opJson) {
        FieldClock clock = viewObjectNotationClocks.computeIfAbsent(viewObjectId, id -> new FieldClock());
        CausalClock incoming = parseCausal(opJson);
        seedClockFromNotation(clock, notationJson, incoming);
    }

    private void seedConnectionClock(String connectionId, String notationJson, String opJson) {
        FieldClock clock = connectionNotationClocks.computeIfAbsent(connectionId, id -> new FieldClock());
        CausalClock incoming = parseCausal(opJson);
        seedClockFromNotation(clock, notationJson, incoming);
    }

    private void seedClockFromNotation(FieldClock clock, String notationJson, CausalClock incoming) {
        for(String field : extractNotationFields(notationJson)) {
            clock.set(field, incoming);
        }
    }

    private String applyViewObjectNotationLww(String viewObjectId,
                                              IDiagramModelArchimateObject viewObject,
                                              String notationJson,
                                              String opJson) {
        FieldClock clock = viewObjectNotationClocks.computeIfAbsent(viewObjectId, id -> new FieldClock());
        CausalClock incoming = parseCausal(opJson);
        String rewritten = notationJson;

        if(viewObject.getBounds() != null) {
            rewritten = mergeNumberField("x", viewObject.getBounds().getX(), rewritten, incoming, clock);
            rewritten = mergeNumberField("y", viewObject.getBounds().getY(), rewritten, incoming, clock);
            rewritten = mergeNumberField("width", viewObject.getBounds().getWidth(), rewritten, incoming, clock);
            rewritten = mergeNumberField("height", viewObject.getBounds().getHeight(), rewritten, incoming, clock);
        }
        rewritten = mergeNumberField("type", viewObject.getType(), rewritten, incoming, clock);
        rewritten = mergeNumberField("alpha", viewObject.getAlpha(), rewritten, incoming, clock);
        rewritten = mergeNumberField("lineAlpha", viewObject.getLineAlpha(), rewritten, incoming, clock);
        rewritten = mergeNumberField("lineWidth", viewObject.getLineWidth(), rewritten, incoming, clock);
        rewritten = mergeNumberField("lineStyle", viewObject.getLineStyle(), rewritten, incoming, clock);
        rewritten = mergeNumberField("textAlignment", viewObject.getTextAlignment(), rewritten, incoming, clock);
        rewritten = mergeNumberField("textPosition", viewObject.getTextPosition(), rewritten, incoming, clock);
        rewritten = mergeNumberField("gradient", viewObject.getGradient(), rewritten, incoming, clock);
        rewritten = mergeNumberField("iconVisibleState", viewObject.getIconVisibleState(), rewritten, incoming, clock);
        rewritten = mergeBooleanField("deriveElementLineColor", viewObject.getDeriveElementLineColor(), rewritten, incoming, clock);
        rewritten = mergeStringField("fillColor", viewObject.getFillColor(), rewritten, incoming, clock);
        rewritten = mergeStringField("lineColor", viewObject.getLineColor(), rewritten, incoming, clock);
        rewritten = mergeStringField("font", viewObject.getFont(), rewritten, incoming, clock);
        rewritten = mergeStringField("fontColor", viewObject.getFontColor(), rewritten, incoming, clock);
        rewritten = mergeStringField("iconColor", viewObject.getIconColor(), rewritten, incoming, clock);
        if(viewObject instanceof com.archimatetool.model.IIconic iconic) {
            rewritten = mergeStringField("imagePath", iconic.getImagePath(), rewritten, incoming, clock);
            rewritten = mergeNumberField("imagePosition", iconic.getImagePosition(), rewritten, incoming, clock);
        }
        rewritten = mergeStringField("name", getName(viewObject), rewritten, incoming, clock);
        rewritten = mergeStringField("documentation", getDocumentation(viewObject), rewritten, incoming, clock);
        return rewritten;
    }

    private String applyConnectionNotationLww(String connectionId,
                                              IDiagramModelArchimateConnection connection,
                                      String notationJson,
                                      String opJson) {
        FieldClock clock = connectionNotationClocks.computeIfAbsent(connectionId, id -> new FieldClock());
        CausalClock causal = parseCausal(opJson);
        String rewritten = notationJson;
        rewritten = mergeNumberField("type", connection.getType(), rewritten, causal, clock);
        rewritten = mergeBooleanField("nameVisible", connection.isNameVisible(), rewritten, causal, clock);
        rewritten = mergeNumberField("textAlignment", connection.getTextAlignment(), rewritten, causal, clock);
        rewritten = mergeNumberField("textPosition", connection.getTextPosition(), rewritten, causal, clock);
        rewritten = mergeNumberField("lineWidth", connection.getLineWidth(), rewritten, causal, clock);
        rewritten = mergeStringField("name", getName(connection), rewritten, causal, clock);
        rewritten = mergeStringField("lineColor", connection.getLineColor(), rewritten, causal, clock);
        rewritten = mergeStringField("font", connection.getFont(), rewritten, causal, clock);
        rewritten = mergeStringField("fontColor", connection.getFontColor(), rewritten, causal, clock);
        rewritten = mergeStringField("documentation", getDocumentation(connection), rewritten, causal, clock);
        if(SimpleJson.hasField(rewritten, "bendpoints")) {
            rewritten = mergeRawField("bendpoints", bendpointsJson(connection), rewritten, causal, clock);
        }
        return rewritten;
    }

    private String mergeNumberField(String field,
                                      int currentValue,
                                      String notationJson,
                                      CausalClock incoming,
                                      FieldClock clock) {
        if(!SimpleJson.hasField(notationJson, field)) {
            return notationJson;
        }

        CausalClock existing = clock.get(field);
        if(wins(incoming, existing)) {
            clock.set(field, incoming);
            return notationJson;
        }

        String rewritten = overwriteNumberField(notationJson, field, currentValue);
        ArchiCollabPlugin.logTrace("Ignored stale notation field " + field + " incomingLamport="
                + incoming.lamport + " incomingClientId=" + incoming.clientId
                + " existingLamport=" + existing.lamport + " existingClientId=" + existing.clientId);
        return rewritten;
    }

    private String mergeBooleanField(String field,
                                     boolean currentValue,
                                     String notationJson,
                                     CausalClock incoming,
                                     FieldClock clock) {
        if(!SimpleJson.hasField(notationJson, field)) {
            return notationJson;
        }
        CausalClock existing = clock.get(field);
        if(wins(incoming, existing)) {
            clock.set(field, incoming);
            return notationJson;
        }
        String rewritten = overwriteBooleanField(notationJson, field, currentValue);
        ArchiCollabPlugin.logTrace("Ignored stale notation field " + field + " incomingLamport="
                + incoming.lamport + " incomingClientId=" + incoming.clientId
                + " existingLamport=" + existing.lamport + " existingClientId=" + existing.clientId);
        return rewritten;
    }

    private String mergeStringField(String field,
                                    String currentValue,
                                    String notationJson,
                                    CausalClock incoming,
                                    FieldClock clock) {
        if(!SimpleJson.hasField(notationJson, field)) {
            return notationJson;
        }
        CausalClock existing = clock.get(field);
        if(wins(incoming, existing)) {
            clock.set(field, incoming);
            return notationJson;
        }
        String rewritten = overwriteNullableStringField(notationJson, field, currentValue);
        ArchiCollabPlugin.logTrace("Ignored stale notation field " + field + " incomingLamport="
                + incoming.lamport + " incomingClientId=" + incoming.clientId
                + " existingLamport=" + existing.lamport + " existingClientId=" + existing.clientId);
        return rewritten;
    }

    private String mergeRawField(String field,
                                 String currentRawJson,
                                 String notationJson,
                                 CausalClock incoming,
                                 FieldClock clock) {
        CausalClock existing = clock.get(field);
        if(wins(incoming, existing)) {
            clock.set(field, incoming);
            return notationJson;
        }
        String rewritten = overwriteRawField(notationJson, field, currentRawJson);
        ArchiCollabPlugin.logTrace("Ignored stale notation field " + field + " incomingLamport="
                + incoming.lamport + " incomingClientId=" + incoming.clientId
                + " existingLamport=" + existing.lamport + " existingClientId=" + existing.clientId);
        return rewritten;
    }

    private String overwriteNumberField(String notationJson, String field, int replacementValue) {
        Pattern pattern = Pattern.compile("(\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*)-?\\d+");
        Matcher matcher = pattern.matcher(notationJson);
        if(!matcher.find()) {
            return notationJson;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + replacementValue));
    }

    private String overwriteBooleanField(String notationJson, String field, boolean replacementValue) {
        Pattern pattern = Pattern.compile("(\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*)(true|false)");
        Matcher matcher = pattern.matcher(notationJson);
        if(!matcher.find()) {
            return notationJson;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + replacementValue));
    }

    private String overwriteNullableStringField(String notationJson, String field, String replacementValue) {
        Pattern pattern = Pattern.compile("(\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*)(null|\\\"(?:\\\\\\\\.|[^\\\"])*\\\")");
        Matcher matcher = pattern.matcher(notationJson);
        if(!matcher.find()) {
            return notationJson;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + jsonStringOrNull(replacementValue)));
    }

    private String overwriteRawField(String notationJson, String field, String rawJson) {
        Pattern pattern = Pattern.compile("(\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*)\\[[^\\]]*\\]");
        Matcher matcher = pattern.matcher(notationJson);
        if(!matcher.find()) {
            return notationJson;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + rawJson));
    }

    private CausalClock parseCausal(String opJson) {
        String causalJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "causal"));
        Long lamport = SimpleJson.readLongField(causalJson, "lamport");
        String clientId = SimpleJson.readStringField(causalJson, "clientId");
        return new CausalClock(lamport == null ? 0L : lamport, clientId == null ? "" : clientId);
    }

    private boolean wins(CausalClock incoming, CausalClock existing) {
        if(incoming.lamport > existing.lamport) {
            return true;
        }
        if(incoming.lamport < existing.lamport) {
            return false;
        }
        return incoming.clientId.compareTo(existing.clientId) >= 0;
    }

    private String bendpointsJson(IDiagramModelArchimateConnection connection) {
        StringBuilder json = new StringBuilder("[");
        for(int i = 0; i < connection.getBendpoints().size(); i++) {
            var bendpoint = connection.getBendpoints().get(i);
            json.append("{")
                    .append("\"startX\":").append(bendpoint.getStartX()).append(",")
                    .append("\"startY\":").append(bendpoint.getStartY()).append(",")
                    .append("\"endX\":").append(bendpoint.getEndX()).append(",")
                    .append("\"endY\":").append(bendpoint.getEndY())
                    .append("}");
            if(i < connection.getBendpoints().size() - 1) {
                json.append(",");
            }
        }
        json.append("]");
        return json.toString();
    }

    private String jsonStringOrNull(String value) {
        if(value == null) {
            return "null";
        }
        return "\"" + escapeJson(value) + "\"";
    }

    private String escapeJson(String value) {
        if(value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<String> extractNotationFields(String notationJson) {
        List<String> fields = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\\"([A-Za-z0-9_]+)\\\"\\s*:");
        Matcher matcher = pattern.matcher(notationJson == null ? "" : notationJson);
        while(matcher.find()) {
            fields.add(matcher.group(1));
        }
        return fields;
    }

    private static final class FieldClock {
        private static final CausalClock UNKNOWN = new CausalClock(-1L, "");
        private final Map<String, CausalClock> byField = new HashMap<>();

        CausalClock get(String field) {
            return byField.getOrDefault(field, UNKNOWN);
        }

        void set(String field, CausalClock clock) {
            byField.put(field, clock);
        }
    }

    private record CausalClock(long lamport, String clientId) {
    }

}
