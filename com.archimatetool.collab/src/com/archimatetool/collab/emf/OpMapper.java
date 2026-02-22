package com.archimatetool.collab.emf;

import java.time.Instant;
import java.util.Iterator;
import java.util.UUID;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IIconic;
import com.archimatetool.model.IDocumentable;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;
import com.archimatetool.model.IProperty;

/**
 * Maps local EMF objects to collaboration operation envelopes.
 */
public class OpMapper {
    private long lamportCounter = System.currentTimeMillis();

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
                "\"notationJson\":" + notationJsonForViewObject(viewObject) +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toDeleteViewObjectSubmitOps(IDiagramModelArchimateObject viewObject, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"DeleteViewObject\"," +
                "\"viewObjectId\":\"" + escape(prefixedId("vo", viewObject)) + "\"" +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toCreateConnectionSubmitOps(IDiagramModelArchimateConnection connection, String modelId, long baseRevision, String userId, String sessionId) {
        String id = prefixedId("conn", connection);
        String viewId = prefixedId("view", connection.getDiagramModel());
        String representsId = prefixedId("rel", connection.getArchimateRelationship());
        String sourceViewObjectId = prefixedId("vo", asIdentifier(connection.getSource()));
        String targetViewObjectId = prefixedId("vo", asIdentifier(connection.getTarget()));

        String op = "{" +
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
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toUpdateConnectionOpaqueSubmitOps(IDiagramModelArchimateConnection connection, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"UpdateConnectionOpaque\"," +
                "\"connectionId\":\"" + escape(prefixedId("conn", connection)) + "\"," +
                "\"notationJson\":" + notationJsonForConnection(connection) +
                "}";
        return submitOpsEnvelope(modelId, baseRevision, userId, sessionId, op);
    }

    public String toDeleteConnectionSubmitOps(IDiagramModelArchimateConnection connection, String modelId, long baseRevision, String userId, String sessionId) {
        String op = "{" +
                "\"type\":\"DeleteConnection\"," +
                "\"connectionId\":\"" + escape(prefixedId("conn", connection)) + "\"" +
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

    private String submitOpsEnvelope(String modelId, long baseRevision, String userId, String sessionId, String opJson) {
        String opBatchId = UUID.randomUUID().toString();
        String opWithCausal = withCausal(opJson, userId, sessionId, opBatchId);
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
                "\"ops\":[" + opWithCausal + "]" +
                "}" +
                "}";
    }

    private synchronized long nextLamport() {
        long now = System.currentTimeMillis();
        lamportCounter = Math.max(lamportCounter + 1, now);
        return lamportCounter;
    }

    private String withCausal(String opJson, String userId, String sessionId, String opBatchId) {
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
                "\"opId\":\"" + escape(opBatchId + ":0") + "\"" +
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

    private String notationJsonForViewObject(IDiagramModelArchimateObject viewObject) {
        StringBuilder json = new StringBuilder("{");
        IBounds bounds = viewObject.getBounds();
        if(bounds != null) {
            json.append("\"x\":").append(bounds.getX()).append(",");
            json.append("\"y\":").append(bounds.getY()).append(",");
            json.append("\"width\":").append(bounds.getWidth()).append(",");
            json.append("\"height\":").append(bounds.getHeight()).append(",");
        }
        json.append("\"type\":").append(viewObject.getType()).append(",");
        json.append("\"alpha\":").append(viewObject.getAlpha()).append(",");
        json.append("\"lineAlpha\":").append(viewObject.getLineAlpha()).append(",");
        json.append("\"lineWidth\":").append(viewObject.getLineWidth()).append(",");
        json.append("\"lineStyle\":").append(viewObject.getLineStyle()).append(",");
        json.append("\"textAlignment\":").append(viewObject.getTextAlignment()).append(",");
        json.append("\"textPosition\":").append(viewObject.getTextPosition()).append(",");
        json.append("\"gradient\":").append(viewObject.getGradient()).append(",");
        json.append("\"iconVisibleState\":").append(viewObject.getIconVisibleState()).append(",");
        json.append("\"deriveElementLineColor\":").append(viewObject.getDeriveElementLineColor()).append(",");
        json.append("\"fillColor\":").append(jsonValue(viewObject.getFillColor())).append(",");
        json.append("\"lineColor\":").append(jsonValue(viewObject.getLineColor())).append(",");
        json.append("\"font\":").append(jsonValue(viewObject.getFont())).append(",");
        json.append("\"fontColor\":").append(jsonValue(viewObject.getFontColor())).append(",");
        json.append("\"iconColor\":").append(jsonValue(viewObject.getIconColor())).append(",");
        if(viewObject instanceof IIconic iconic) {
            json.append("\"imagePath\":").append(jsonValue(iconic.getImagePath())).append(",");
            json.append("\"imagePosition\":").append(iconic.getImagePosition()).append(",");
        }
        json.append("\"name\":").append(jsonValue(getName(viewObject))).append(",");
        json.append("\"documentation\":").append(jsonValue(getDocumentation(viewObject)));
        json.append("}");
        return json.toString();
    }

    private String notationJsonForConnection(IDiagramModelArchimateConnection connection) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"type\":").append(connection.getType()).append(",");
        json.append("\"nameVisible\":").append(connection.isNameVisible()).append(",");
        json.append("\"textAlignment\":").append(connection.getTextAlignment()).append(",");
        json.append("\"textPosition\":").append(connection.getTextPosition()).append(",");
        json.append("\"lineWidth\":").append(connection.getLineWidth()).append(",");
        json.append("\"name\":").append(jsonValue(getName(connection))).append(",");
        json.append("\"lineColor\":").append(jsonValue(connection.getLineColor())).append(",");
        json.append("\"font\":").append(jsonValue(connection.getFont())).append(",");
        json.append("\"fontColor\":").append(jsonValue(connection.getFontColor())).append(",");
        json.append("\"documentation\":").append(jsonValue(getDocumentation(connection))).append(",");
        json.append("\"bendpoints\":[");
        Iterator<IDiagramModelBendpoint> it = connection.getBendpoints().iterator();
        while(it.hasNext()) {
            IDiagramModelBendpoint bendpoint = it.next();
            json.append("{")
                    .append("\"startX\":").append(bendpoint.getStartX()).append(",")
                    .append("\"startY\":").append(bendpoint.getStartY()).append(",")
                    .append("\"endX\":").append(bendpoint.getEndX()).append(",")
                    .append("\"endY\":").append(bendpoint.getEndY())
                    .append("}");
            if(it.hasNext()) {
                json.append(",");
            }
        }
        json.append("]");
        json.append("}");
        return json.toString();
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
