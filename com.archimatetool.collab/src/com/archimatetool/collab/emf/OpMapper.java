package com.archimatetool.collab.emf;

import java.time.Instant;
import java.util.UUID;

import com.archimatetool.collab.notation.NotationSerializer;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDocumentable;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;
import com.archimatetool.model.IProperty;

/**
 * Maps local EMF objects to collaboration operation envelopes.
 */
public class OpMapper {
    private long lamportCounter = System.currentTimeMillis();
    private final NotationSerializer notationSerializer = new NotationSerializer();

    public String toCreateElementSubmitOps(IArchimateElement element, String modelId, long baseRevision, String userId, String sessionId) {
        String elementId = prefixedId("elem", element);
        String archimateType = element.eClass().getName();
        String name = element instanceof INameable ? ((INameable)element).getName() : "";
        String documentation = element instanceof IDocumentable ? ((IDocumentable)element).getDocumentation() : "";

        String op = "{" +
                "\"type\":\"CreateElement\"," +
                "\"element\":{" +
                "\"id\":\"" + escape(elementId) + "\"," +
                "\"archimateType\":\"" + escape(archimateType) + "\"," +
                "\"name\":\"" + escape(name) + "\"," +
                "\"documentation\":\"" + escape(documentation) + "\"" +
                "}" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toCreateRelationshipSubmitOps(IArchimateRelationship relationship, String modelId, long baseRevision, String userId, String sessionId) {
        String relationshipId = prefixedId("rel", relationship);
        String archimateType = relationship.eClass().getName();
        String name = relationship instanceof INameable ? ((INameable)relationship).getName() : "";
        String documentation = relationship instanceof IDocumentable ? ((IDocumentable)relationship).getDocumentation() : "";
        String sourceId = prefixedId("elem", relationship.getSource());
        String targetId = prefixedId("elem", relationship.getTarget());

        String op = "{" +
                "\"type\":\"CreateRelationship\"," +
                "\"relationship\":{" +
                "\"id\":\"" + escape(relationshipId) + "\"," +
                "\"archimateType\":\"" + escape(archimateType) + "\"," +
                "\"name\":\"" + escape(name) + "\"," +
                "\"documentation\":\"" + escape(documentation) + "\"," +
                "\"sourceId\":\"" + escape(sourceId) + "\"," +
                "\"targetId\":\"" + escape(targetId) + "\"" +
                "}" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toUpdateElementSubmitOps(IArchimateElement element, String modelId, long baseRevision, String userId, String sessionId,
            boolean includeName, boolean includeDocumentation) {
        StringBuilder patch = new StringBuilder("{");
        if(includeName) {
            patch.append("\"name\":\"").append(escape(getName(element))).append("\",");
        }
        if(includeDocumentation) {
            patch.append("\"documentation\":\"").append(escape(getDocumentation(element))).append("\",");
        }
        if(patch.length() == 1) {
            return null;
        }
        patch.setLength(patch.length() - 1);
        patch.append("}");

        String op = "{" +
                "\"type\":\"UpdateElement\"," +
                "\"elementId\":\"" + escape(prefixedId("elem", element)) + "\"," +
                "\"patch\":" + patch +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toUpdateRelationshipSubmitOps(IArchimateRelationship relationship, String modelId, long baseRevision, String userId,
            String sessionId, boolean includeName, boolean includeDocumentation, boolean includeEndpoints) {
        StringBuilder patch = new StringBuilder("{");
        if(includeName) {
            patch.append("\"name\":\"").append(escape(getName(relationship))).append("\",");
        }
        if(includeDocumentation) {
            patch.append("\"documentation\":\"").append(escape(getDocumentation(relationship))).append("\",");
        }
        if(includeEndpoints) {
            patch.append("\"sourceId\":\"").append(escape(prefixedId("elem", relationship.getSource()))).append("\",");
            patch.append("\"targetId\":\"").append(escape(prefixedId("elem", relationship.getTarget()))).append("\",");
        }
        if(patch.length() == 1) {
            return null;
        }
        patch.setLength(patch.length() - 1);
        patch.append("}");

        String op = "{" +
                "\"type\":\"UpdateRelationship\"," +
                "\"relationshipId\":\"" + escape(prefixedId("rel", relationship)) + "\"," +
                "\"patch\":" + patch +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toDeleteElementSubmitOps(IArchimateElement element, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"DeleteElement\"," +
                "\"elementId\":\"" + escape(prefixedId("elem", element)) + "\"" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toDeleteRelationshipSubmitOps(IArchimateRelationship relationship, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"DeleteRelationship\"," +
                "\"relationshipId\":\"" + escape(prefixedId("rel", relationship)) + "\"" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toCreateViewSubmitOps(IArchimateDiagramModel view, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"CreateView\"," +
                "\"view\":{" +
                "\"id\":\"" + escape(prefixedId("view", view)) + "\"," +
                "\"name\":\"" + escape(getName(view)) + "\"" +
                "}" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toUpdateViewSubmitOps(IDiagramModel view, String modelId, long baseRevision, String userId, String sessionId,
            boolean includeName, boolean includeDocumentation) {
        StringBuilder patch = new StringBuilder("{");
        if(includeName) {
            patch.append("\"name\":\"").append(escape(getName(view))).append("\",");
        }
        if(includeDocumentation) {
            patch.append("\"documentation\":\"").append(escape(getDocumentation(view))).append("\",");
        }
        if(patch.length() == 1) {
            return null;
        }
        patch.setLength(patch.length() - 1);
        patch.append("}");

        String op = "{" +
                "\"type\":\"UpdateView\"," +
                "\"viewId\":\"" + escape(prefixedId("view", (IIdentifier)view)) + "\"," +
                "\"patch\":" + patch +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toDeleteViewSubmitOps(IArchimateDiagramModel view, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"DeleteView\"," +
                "\"viewId\":\"" + escape(prefixedId("view", view)) + "\"" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toCreateViewObjectSubmitOps(IDiagramModelArchimateObject viewObject, String modelId, long baseRevision, String userId, String sessionId) {
        String id = prefixedId("vo", viewObject);
        String viewId = prefixedId("view", viewObject.getDiagramModel());
        String representsId = prefixedId("elem", viewObject.getArchimateElement());
        String notationJson = notationJsonForViewObject(viewObject);

        String op = "{" +
                "\"type\":\"CreateViewObject\"," +
                "\"viewObject\":{" +
                "\"id\":\"" + escape(id) + "\"," +
                "\"viewId\":\"" + escape(viewId) + "\"," +
                "\"representsId\":\"" + escape(representsId) + "\"," +
                "\"notationJson\":" + notationJson +
                "}" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toUpdateViewObjectOpaqueSubmitOps(IDiagramModelArchimateObject viewObject, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"UpdateViewObjectOpaque\"," +
                "\"viewObjectId\":\"" + escape(prefixedId("vo", viewObject)) + "\"," +
                "\"viewId\":\"" + escape(prefixedId("view", viewObject.getDiagramModel())) + "\"," +
                "\"notationJson\":" + notationJsonForViewObject(viewObject) +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toDeleteViewObjectSubmitOps(IDiagramModelArchimateObject viewObject, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"DeleteViewObject\"," +
                "\"viewObjectId\":\"" + escape(prefixedId("vo", viewObject)) + "\"," +
                "\"viewId\":\"" + escape(prefixedId("view", viewObject.getDiagramModel())) + "\"" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toCreateConnectionSubmitOps(IDiagramModelArchimateConnection connection, String modelId, long baseRevision, String userId, String sessionId) {
        String op = createConnectionOpJson(connection);
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    /**
     * Emit relationship+connection creation in one batch so server preconditions
     * can validate representsId against createdRelationshipIds deterministically.
     */
    public String toCreateConnectionWithRelationshipSubmitOps(IDiagramModelArchimateConnection connection, String modelId,
            long baseRevision, String userId, String sessionId) {
        IArchimateRelationship relationship = connection == null ? null : connection.getArchimateRelationship();
        if(relationship == null) {
            return toCreateConnectionSubmitOps(connection, modelId, baseRevision, userId, sessionId);
        }
        if(!hasIdentifier(connection)
                || !hasIdentifier(connection.getDiagramModel())
                || !hasIdentifier(asIdentifier(connection.getSource()))
                || !hasIdentifier(asIdentifier(connection.getTarget()))
                || !hasIdentifier(relationship)
                || !hasIdentifier(relationship.getSource())
                || !hasIdentifier(relationship.getTarget())) {
            return null;
        }
        String relationshipOp = createRelationshipOpJson(relationship);
        String connectionOp = createConnectionOpJson(connection);
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, relationshipOp, connectionOp);
    }

    public String toUpdateConnectionOpaqueSubmitOps(IDiagramModelArchimateConnection connection, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"UpdateConnectionOpaque\"," +
                "\"connectionId\":\"" + escape(prefixedId("conn", connection)) + "\"," +
                "\"viewId\":\"" + escape(prefixedId("view", connection.getDiagramModel())) + "\"," +
                "\"notationJson\":" + notationJsonForConnection(connection) +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toDeleteConnectionSubmitOps(IDiagramModelArchimateConnection connection, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"DeleteConnection\"," +
                "\"connectionId\":\"" + escape(prefixedId("conn", connection)) + "\"," +
                "\"viewId\":\"" + escape(prefixedId("view", connection.getDiagramModel())) + "\"" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toSetPropertySubmitOps(String targetId, String key, Object value, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"SetProperty\"," +
                "\"targetId\":\"" + escape(targetId) + "\"," +
                "\"key\":\"" + escape(key) + "\"," +
                "\"value\":" + jsonValue(value) +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toUnsetPropertySubmitOps(String targetId, String key, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"UnsetProperty\"," +
                "\"targetId\":\"" + escape(targetId) + "\"," +
                "\"key\":\"" + escape(key) + "\"" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toAddPropertySetMemberSubmitOps(String targetId, String key, String member, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"AddPropertySetMember\"," +
                "\"targetId\":\"" + escape(targetId) + "\"," +
                "\"key\":\"" + escape(key) + "\"," +
                "\"member\":\"" + escape(member) + "\"" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toRemovePropertySetMemberSubmitOps(String targetId, String key, String member, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"RemovePropertySetMember\"," +
                "\"targetId\":\"" + escape(targetId) + "\"," +
                "\"key\":\"" + escape(key) + "\"," +
                "\"member\":\"" + escape(member) + "\"" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toAddViewObjectChildMemberSubmitOps(String parentViewObjectId, String childViewObjectId, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"AddViewObjectChildMember\"," +
                "\"parentViewObjectId\":\"" + escape(parentViewObjectId) + "\"," +
                "\"childViewObjectId\":\"" + escape(childViewObjectId) + "\"" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toRemoveViewObjectChildMemberSubmitOps(String parentViewObjectId, String childViewObjectId, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"RemoveViewObjectChildMember\"," +
                "\"parentViewObjectId\":\"" + escape(parentViewObjectId) + "\"," +
                "\"childViewObjectId\":\"" + escape(childViewObjectId) + "\"" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String targetIdForOwner(Object owner) {
        if(owner instanceof IArchimateElement element) {
            return prefixedId("elem", element);
        }
        if(owner instanceof IArchimateRelationship relationship) {
            return prefixedId("rel", relationship);
        }
        if(owner instanceof IArchimateDiagramModel view) {
            return prefixedId("view", view);
        }
        if(owner instanceof IDiagramModelArchimateObject viewObject) {
            return prefixedId("vo", viewObject);
        }
        if(owner instanceof IDiagramModelArchimateConnection connection) {
            return prefixedId("conn", connection);
        }
        return null;
    }

    public String key(IProperty property) {
        return property == null ? null : property.getKey();
    }

    public String value(IProperty property) {
        return property == null ? null : property.getValue();
    }

    private String submitOpsEnvelope(String modelId, long baseRevision, String userId, String sessionId, String... opJsons) {
        String opBatchId = UUID.randomUUID().toString();
        StringBuilder ops = new StringBuilder();
        if(opJsons != null) {
            for(int i = 0; i < opJsons.length; i++) {
                String opWithCausal = withCausal(opJsons[i], userId, sessionId, opBatchId, i);
                if(opWithCausal == null || opWithCausal.isBlank()) {
                    continue;
                }
                if(ops.length() > 0) {
                    ops.append(",");
                }
                ops.append(opWithCausal);
            }
        }
        return "{" +
                "\"type\":\"SubmitOps\"," +
                "\"payload\":{" +
                "\"modelId\":\"" + escape(modelId) + "\"," +
                "\"baseRevision\":" + baseRevision + "," +
                "\"opBatchId\":\"" + opBatchId + "\"," +
                "\"actor\":{" +
                "\"userId\":\"" + escape(userId) + "\"," +
                "\"sessionId\":\"" + escape(sessionId) + "\"" +
                "}," +
                "\"timestamp\":\"" + Instant.now() + "\"," +
                "\"ops\":[" + ops + "]" +
                "}" +
                "}";
    }

    private synchronized long nextLamport() {
        long now = System.currentTimeMillis();
        lamportCounter = Math.max(lamportCounter + 1, now);
        return lamportCounter;
    }

    private String withCausal(String opJson, String userId, String sessionId, String opBatchId, int opIndex) {
        if(opJson == null || opJson.isBlank()) {
            return opJson;
        }

        String clientId = sessionId;
        if(clientId == null || clientId.isBlank()) {
            clientId = userId;
        }
        if(clientId == null || clientId.isBlank()) {
            clientId = "anonymous-session";
        }

        long lamport = nextLamport();
        String causal = "\"causal\":{" +
                "\"clientId\":\"" + escape(clientId) + "\"," +
                "\"lamport\":" + lamport + "," +
                "\"opId\":\"" + escape(opBatchId + ":" + opIndex) + "\"" +
                "}";

        String trimmed = opJson.trim();
        if(trimmed.endsWith("}")) {
            return trimmed.substring(0, trimmed.length() - 1) + "," + causal + "}";
        }
        return trimmed;
    }

    private String getName(Object object) {
        return object instanceof INameable ? ((INameable)object).getName() : "";
    }

    private String getDocumentation(Object object) {
        return object instanceof IDocumentable ? ((IDocumentable)object).getDocumentation() : "";
    }

    private String createRelationshipOpJson(IArchimateRelationship relationship) {
        String relationshipId = prefixedId("rel", relationship);
        String archimateType = relationship.eClass().getName();
        String name = relationship instanceof INameable ? ((INameable)relationship).getName() : "";
        String documentation = relationship instanceof IDocumentable ? ((IDocumentable)relationship).getDocumentation() : "";
        String sourceId = prefixedId("elem", relationship.getSource());
        String targetId = prefixedId("elem", relationship.getTarget());

        return "{" +
                "\"type\":\"CreateRelationship\"," +
                "\"relationship\":{" +
                "\"id\":\"" + escape(relationshipId) + "\"," +
                "\"archimateType\":\"" + escape(archimateType) + "\"," +
                "\"name\":\"" + escape(name) + "\"," +
                "\"documentation\":\"" + escape(documentation) + "\"," +
                "\"sourceId\":\"" + escape(sourceId) + "\"," +
                "\"targetId\":\"" + escape(targetId) + "\"" +
                "}" +
                "}";
    }

    private String createConnectionOpJson(IDiagramModelArchimateConnection connection) {
        String id = prefixedId("conn", connection);
        String viewId = prefixedId("view", connection.getDiagramModel());
        String representsId = prefixedId("rel", connection.getArchimateRelationship());
        String sourceViewObjectId = prefixedId("vo", asIdentifier(connection.getSource()));
        String targetViewObjectId = prefixedId("vo", asIdentifier(connection.getTarget()));

        return "{" +
                "\"type\":\"CreateConnection\"," +
                "\"connection\":{" +
                "\"id\":\"" + escape(id) + "\"," +
                "\"viewId\":\"" + escape(viewId) + "\"," +
                "\"representsId\":\"" + escape(representsId) + "\"," +
                "\"sourceViewObjectId\":\"" + escape(sourceViewObjectId) + "\"," +
                "\"targetViewObjectId\":\"" + escape(targetViewObjectId) + "\"," +
                "\"notationJson\":" + notationJsonForConnection(connection) +
                "}" +
                "}";
    }

    private String prefixedId(String prefix, IIdentifier object) {
        String id = object == null ? null : object.getId();
        return prefix + ":" + (id == null ? "" : id);
    }

    private String prefixedId(String prefix, IArchimateConcept concept) {
        if(concept instanceof IIdentifier) {
            return prefixedId(prefix, (IIdentifier)concept);
        }
        return prefix + ":";
    }

    private String prefixedId(String prefix, IDiagramModel model) {
        return model instanceof IIdentifier ? prefixedId(prefix, (IIdentifier)model) : prefix + ":";
    }

    private IIdentifier asIdentifier(IConnectable connectable) {
        return connectable instanceof IIdentifier ? (IIdentifier)connectable : null;
    }

    private boolean hasIdentifier(IIdentifier value) {
        return value != null && value.getId() != null && !value.getId().isBlank();
    }

    private String notationJsonForViewObject(IDiagramModelArchimateObject viewObject) {
        return notationSerializer.serializeViewObject(viewObject);
    }

    private String notationJsonForConnection(IDiagramModelArchimateConnection connection) {
        return notationSerializer.serializeConnection(connection);
    }

    private String jsonValue(Object value) {
        if(value == null) {
            return "null";
        }
        if(value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private String escape(String value) {
        if(value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
