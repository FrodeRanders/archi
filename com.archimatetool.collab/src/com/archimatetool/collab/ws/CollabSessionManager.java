package com.archimatetool.collab.ws;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.emf.ModelCollaborationController;
import com.archimatetool.collab.util.SimpleJson;
import com.archimatetool.model.IArchimateModel;

/**
 * Manages a single websocket collaboration session for now.
 */
public class CollabSessionManager {
    public interface SessionStateListener {
        void stateChanged(boolean connected, String modelId);
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final InboundMessageDispatcher inboundMessageDispatcher = new InboundMessageDispatcher(this);
    private final ModelCollaborationController modelController = new ModelCollaborationController(this);

    private volatile WebSocket webSocket;
    private volatile String currentModelId;
    private volatile IArchimateModel attachedModel;
    private volatile String userId = "anonymous";
    private volatile String sessionId = "archi-" + UUID.randomUUID();
    private volatile long lastKnownRevision;
    private final CopyOnWriteArrayList<SessionStateListener> sessionStateListeners = new CopyOnWriteArrayList<>();

    public synchronized void connect(String baseWsUrl, String modelId) {
        Objects.requireNonNull(baseWsUrl, "baseWsUrl");
        Objects.requireNonNull(modelId, "modelId");

        disconnect();

        URI uri = URI.create(baseWsUrl + "/models/" + modelId + "/stream");

        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, new Listener())
                    .join();
            currentModelId = modelId;
            sendJoin(lastKnownRevision);
            fireStateChanged(true, modelId);
            ArchiCollabPlugin.logInfo("Connected collaboration websocket for model " + modelId);
        }
        catch(Exception ex) {
            ArchiCollabPlugin.logError("Failed to connect collaboration websocket", ex);
            webSocket = null;
            currentModelId = null;
            fireStateChanged(false, null);
        }
    }

    public synchronized void disconnect() {
        boolean wasConnected = webSocket != null;
        if(webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect").join();
            }
            catch(Exception ex) {
                ArchiCollabPlugin.logError("Error closing collaboration websocket", ex);
            }
        }

        webSocket = null;
        currentModelId = null;
        if(wasConnected) {
            fireStateChanged(false, null);
        }
    }

    public synchronized void setActor(String userId, String sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
    }

    public void attachModel(IArchimateModel model) {
        attachedModel = model;
        modelController.attach(model);
    }

    public void detachModel() {
        attachedModel = null;
        modelController.detach();
    }

    public void sendJoin(long lastSeenRevision) {
        String payload = "{" +
                "\"type\":\"Join\"," +
                "\"payload\":{" +
                "\"lastSeenRevision\":" + lastSeenRevision + "," +
                "\"actor\":{" +
                "\"userId\":\"" + escape(userId) + "\"," +
                "\"sessionId\":\"" + escape(sessionId) + "\"" +
                "}" +
                "}" +
                "}";
        sendRaw(payload);
    }

    public void sendSubmitOps(String opBatchJson) {
        sendRaw(opBatchJson);
    }

    public void sendAcquireLock(String targetsJsonArray, long ttlMs) {
        String payload = "{" +
                "\"type\":\"AcquireLock\"," +
                "\"payload\":{" +
                "\"actor\":{" +
                "\"userId\":\"" + escape(userId) + "\"," +
                "\"sessionId\":\"" + escape(sessionId) + "\"" +
                "}," +
                "\"targets\":" + targetsJsonArray + "," +
                "\"ttlMs\":" + ttlMs +
                "}" +
                "}";
        sendRaw(payload);
    }

    public void sendReleaseLock(String targetsJsonArray) {
        String payload = "{" +
                "\"type\":\"ReleaseLock\"," +
                "\"payload\":{" +
                "\"actor\":{" +
                "\"userId\":\"" + escape(userId) + "\"," +
                "\"sessionId\":\"" + escape(sessionId) + "\"" +
                "}," +
                "\"targets\":" + targetsJsonArray +
                "}" +
                "}";
        sendRaw(payload);
    }

    public void sendPresence(String viewId, String selectionJsonArray, String cursorJson) {
        String payload = "{" +
                "\"type\":\"Presence\"," +
                "\"payload\":{" +
                "\"actor\":{" +
                "\"userId\":\"" + escape(userId) + "\"," +
                "\"sessionId\":\"" + escape(sessionId) + "\"" +
                "}," +
                "\"viewId\":\"" + escape(viewId) + "\"," +
                "\"selection\":" + selectionJsonArray + "," +
                "\"cursor\":" + cursorJson +
                "}" +
                "}";
        sendRaw(payload);
    }

    public String getCurrentModelId() {
        return currentModelId;
    }

    public IArchimateModel getAttachedModel() {
        return attachedModel;
    }

    public boolean isConnected() {
        return webSocket != null;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getLastKnownRevision() {
        return lastKnownRevision;
    }

    public void setLastKnownRevision(long lastKnownRevision) {
        this.lastKnownRevision = Math.max(lastKnownRevision, this.lastKnownRevision);
    }

    public void addSessionStateListener(SessionStateListener listener) {
        if(listener != null) {
            sessionStateListeners.addIfAbsent(listener);
        }
    }

    public void removeSessionStateListener(SessionStateListener listener) {
        if(listener != null) {
            sessionStateListeners.remove(listener);
        }
    }

    private void fireStateChanged(boolean connected, String modelId) {
        for(SessionStateListener listener : sessionStateListeners) {
            listener.stateChanged(connected, modelId);
        }
    }

    private void sendRaw(String payload) {
        WebSocket ws = webSocket;
        if(ws == null) {
            ArchiCollabPlugin.logDebug("sendRaw skipped: websocket not connected payload=" + summarizeEnvelope(payload));
            return;
        }

        ArchiCollabPlugin.logDebug("WS OUT " + summarizeEnvelope(payload));
        ws.sendText(payload, true).exceptionally(ex -> {
            ArchiCollabPlugin.logError("Failed sending collaboration message", ex);
            return null;
        });
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder fragments = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if(last) {
                String message = fragments.toString();
                ArchiCollabPlugin.logDebug("WS IN " + summarizeEnvelope(message));
                if(ArchiCollabPlugin.isDebugEnabled()) {
                    String type = SimpleJson.readStringField(message, "type");
                    if("OpsBroadcast".equals(type)) {
                        ArchiCollabPlugin.logDebug("WS IN RAW OpsBroadcast " + message);
                    }
                }
                inboundMessageDispatcher.dispatch(message);
                fragments.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ArchiCollabPlugin.logError("Collaboration websocket listener error", error);
        }
    }

    private String summarizeEnvelope(String json) {
        if(json == null || json.isBlank()) {
            return "empty";
        }
        String type = SimpleJson.readStringField(json, "type");
        if(type == null) {
            return "type=?";
        }
        StringBuilder summary = new StringBuilder("type=").append(type);
        if("SubmitOps".equals(type)) {
            String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(json, "payload"));
            String modelId = payload == null ? null : SimpleJson.readStringField(payload, "modelId");
            String opBatchId = payload == null ? null : SimpleJson.readStringField(payload, "opBatchId");
            int opCount = payload == null ? 0 : SimpleJson.readArrayObjectElements(payload, "ops").size();
            summary.append(" modelId=").append(modelId).append(" opBatchId=").append(opBatchId).append(" opCount=").append(opCount);
        }
        else if("OpsBroadcast".equals(type)) {
            String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(json, "payload"));
            String opBatch = payload == null ? null : SimpleJson.asJsonObject(SimpleJson.readRawField(payload, "opBatch"));
            if(opBatch == null && payload != null && SimpleJson.readRawField(payload, "ops") != null) {
                opBatch = payload;
            }
            if(opBatch == null) {
                opBatch = SimpleJson.asJsonObject(SimpleJson.readRawField(json, "opBatch"));
            }
            String opBatchId = opBatch == null ? null : SimpleJson.readStringField(opBatch, "opBatchId");
            int opCount = opBatch == null ? 0 : SimpleJson.readArrayObjectElements(opBatch, "ops").size();
            summary.append(" opBatchId=").append(opBatchId).append(" opCount=").append(opCount);
        }
        return summary.toString();
    }
}
