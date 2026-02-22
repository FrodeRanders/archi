package com.archimatetool.collab.ws;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.emf.ModelCollaborationController;
import com.archimatetool.collab.util.SimpleJson;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * Manages a single websocket collaboration session for now.
 */
public class CollabSessionManager {
    private static final long CACHE_SAVE_DEBOUNCE_MS = 2000L;
    private static final String CACHE_DIR_NAME = "collab-cache";
    private static final int MAX_OUTBOX_SIZE = 1000;
    private static final String OUTBOX_FILE_SUFFIX = ".outbox.properties";
    private static final long OUTBOX_RETRY_INITIAL_DELAY_MS = 250L;
    private static final long OUTBOX_RETRY_MAX_DELAY_MS = 5000L;
    private static final int OUTBOX_MAX_REPLAY_ATTEMPTS = 5;
    private static final long OUTBOX_ACK_TIMEOUT_MS = 5000L;

    public interface SessionStateListener {
        void stateChanged(boolean connected, String modelId);
    }

    public interface SubmitConflictListener {
        void conflictDetected(String modelId, String opBatchId, String code, String message);
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
    private volatile boolean serverBackedSession = true;
    private volatile long lastCacheSaveEpochMillis;
    private volatile boolean pendingCacheRevisionComparison;
    private volatile long cacheRevisionAtJoin = -1;
    private volatile boolean coldSnapshotRebuildRequested;
    private volatile boolean outboxFlushInFlight;
    private volatile boolean outboxRetryScheduled;
    private volatile QueuedSubmitOp outboxAwaitingAck;
    private volatile String outboxAwaitingAckOpBatchId;
    private volatile long outboxAwaitingAckDeadlineEpochMs;
    private volatile ConflictSnapshot lastConflictSnapshot;
    private final Deque<QueuedSubmitOp> offlineOutbox = new ArrayDeque<>();
    private final CopyOnWriteArrayList<SessionStateListener> sessionStateListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SubmitConflictListener> submitConflictListeners = new CopyOnWriteArrayList<>();

    public synchronized void connect(String baseWsUrl, String modelId) {
        Objects.requireNonNull(baseWsUrl, "baseWsUrl");
        Objects.requireNonNull(modelId, "modelId");

        disconnect();

        URI uri = URI.create(baseWsUrl + "/models/" + modelId + "/stream");

        try {
            cacheRevisionAtJoin = -1;
            pendingCacheRevisionComparison = false;
            coldSnapshotRebuildRequested = false;

            CacheRejoinDecision rejoinDecision = resolveJoinDecision(modelId);
            if(rejoinDecision.discardCacheProjection()) {
                discardCacheProjection(rejoinDecision.modelId(), rejoinDecision.reason());
            }
            loadOutboxFromDisk(modelId);

            webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, new Listener())
                    .join();
            currentModelId = modelId;
            Long joinRevision = rejoinDecision.joinRevision();
            if(joinRevision != null) {
                cacheRevisionAtJoin = joinRevision;
                pendingCacheRevisionComparison = true;
            }
            sendJoin(joinRevision);
            fireStateChanged(true, modelId);
            ArchiCollabPlugin.logInfo("Connected collaboration websocket for model " + modelId
                    + " mode=" + (serverBackedSession ? "server-backed" : "local-first")
                    + " rejoin=" + rejoinDecision.reason());
        }
        catch(Exception ex) {
            ArchiCollabPlugin.logError("Failed to connect collaboration websocket", ex);
            webSocket = null;
            currentModelId = null;
            fireStateChanged(false, null);
        }
    }

    public synchronized void disconnect() {
        maybePersistCacheSnapshot("disconnect", true);

        boolean wasConnected = webSocket != null;
        if(webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect").join();
            }
            catch(Exception ex) {
                if(isExpectedCloseException(ex)) {
                    ArchiCollabPlugin.logDebug("Collaboration websocket already closed while disconnecting");
                }
                else {
                    ArchiCollabPlugin.logError("Error closing collaboration websocket", ex);
                }
            }
        }

        webSocket = null;
        currentModelId = null;
        cacheRevisionAtJoin = -1;
        pendingCacheRevisionComparison = false;
        coldSnapshotRebuildRequested = false;
        outboxAwaitingAck = null;
        outboxAwaitingAckOpBatchId = null;
        outboxAwaitingAckDeadlineEpochMs = 0L;
        if(wasConnected) {
            fireStateChanged(false, null);
        }
    }

    private boolean isExpectedCloseException(Throwable throwable) {
        Throwable cursor = throwable;
        while(cursor != null) {
            String message = cursor.getMessage();
            if(message != null && message.contains("Output closed")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    public synchronized void setActor(String userId, String sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
    }

    public void attachModel(IArchimateModel model) {
        attachedModel = model;
        modelController.attach(model);
        inboundMessageDispatcher.replayBufferedMutationsIfAny();
        maybePersistCacheSnapshot("attach", true);
    }

    public void detachModel() {
        maybePersistCacheSnapshot("detach", true);
        attachedModel = null;
        modelController.detach();
    }

    public void sendJoin(Long lastSeenRevision) {
        StringBuilder payload = new StringBuilder("{");
        payload.append("\"type\":\"Join\",");
        payload.append("\"payload\":{");
        if(lastSeenRevision != null) {
            payload.append("\"lastSeenRevision\":").append(lastSeenRevision).append(",");
        }
        payload.append("\"actor\":{");
        payload.append("\"userId\":\"").append(escape(userId)).append("\",");
        payload.append("\"sessionId\":\"").append(escape(sessionId)).append("\"");
        payload.append("}");
        payload.append("}");
        payload.append("}");
        if(lastSeenRevision == null) {
            ArchiCollabPlugin.logInfo("Sending Join without lastSeenRevision (cold start snapshot requested)");
        }
        else {
            ArchiCollabPlugin.logTrace("Sending Join with lastSeenRevision=" + lastSeenRevision);
        }
        sendRaw(payload.toString());
    }

    public void sendSubmitOps(String opBatchJson) {
        sendSubmitOpsOrQueue(opBatchJson);
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

    public synchronized void setLastKnownRevision(long lastKnownRevision) {
        long normalized = Math.max(0L, lastKnownRevision);
        evaluateCacheRevisionComparison(normalized);
        this.lastKnownRevision = Math.max(normalized, this.lastKnownRevision);
        flushOutboxIfPossible();
    }

    public boolean isServerBackedSession() {
        return serverBackedSession;
    }

    public synchronized void setServerBackedSession(boolean serverBackedSession) {
        this.serverBackedSession = serverBackedSession;
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

    public void addSubmitConflictListener(SubmitConflictListener listener) {
        if(listener != null) {
            submitConflictListeners.addIfAbsent(listener);
        }
    }

    public void removeSubmitConflictListener(SubmitConflictListener listener) {
        if(listener != null) {
            submitConflictListeners.remove(listener);
        }
    }

    public ConflictSnapshot getLastConflictSnapshot() {
        return lastConflictSnapshot;
    }

    private void fireStateChanged(boolean connected, String modelId) {
        for(SessionStateListener listener : sessionStateListeners) {
            listener.stateChanged(connected, modelId);
        }
    }

    private synchronized void sendSubmitOpsOrQueue(String submitOpsJson) {
        if(submitOpsJson == null || submitOpsJson.isBlank()) {
            return;
        }
        String modelId = extractModelId(submitOpsJson);
        if(modelId == null || modelId.isBlank()) {
            modelId = currentModelId;
        }
        if(modelId == null || modelId.isBlank()) {
            ArchiCollabPlugin.logInfo("Dropping SubmitOps without modelId");
            return;
        }

        WebSocket ws = webSocket;
        if(ws == null) {
            enqueueOutbox(modelId, submitOpsJson, "websocket-disconnected");
            return;
        }
        if(currentModelId != null && !currentModelId.isBlank() && !currentModelId.equals(modelId)) {
            enqueueOutbox(modelId, submitOpsJson, "model-mismatch");
            return;
        }
        if(outboxFlushInFlight || hasQueuedEntriesForModel(modelId)) {
            enqueueOutbox(modelId, submitOpsJson, "ordered-drain");
            flushOutboxIfPossible();
            return;
        }
        sendSubmitOverWebSocket(ws, modelId, submitOpsJson);
    }

    private void sendSubmitOverWebSocket(WebSocket ws, String modelId, String submitOpsJson) {
        String rebased = rebaseSubmitOpsBaseRevision(submitOpsJson, lastKnownRevision);
        ArchiCollabPlugin.logTrace("WS OUT " + summarizeEnvelope(rebased));
        ws.sendText(rebased, true).exceptionally(ex -> {
            enqueueOutbox(modelId, submitOpsJson, "send-failed");
            ArchiCollabPlugin.logInfo("Queued SubmitOps after send failure for modelId=" + modelId + " reason=" + ex.getClass().getSimpleName());
            return null;
        });
    }

    private synchronized void enqueueOutbox(String modelId, String submitOpsJson, String reason) {
        if(offlineOutbox.size() >= MAX_OUTBOX_SIZE) {
            QueuedSubmitOp dropped = offlineOutbox.removeFirst();
            ArchiCollabPlugin.logInfo("Outbox full; dropping oldest queued SubmitOps modelId=" + dropped.modelId);
            persistOutboxToDisk(dropped.modelId);
        }
        offlineOutbox.addLast(new QueuedSubmitOp(modelId, submitOpsJson, System.currentTimeMillis(), 0, 0L));
        persistOutboxToDisk(modelId);
        ArchiCollabPlugin.logInfo("Queued SubmitOps for offline replay modelId=" + modelId
                + " queueSize=" + offlineOutbox.size()
                + " reason=" + reason);
    }

    private synchronized void flushOutboxIfPossible() {
        if(offlineOutbox.isEmpty()) {
            return;
        }
        if(outboxFlushInFlight) {
            return;
        }
        WebSocket ws = webSocket;
        if(ws == null) {
            return;
        }
        if(currentModelId == null || currentModelId.isBlank()) {
            return;
        }
        if(lastKnownRevision < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if(outboxAwaitingAck != null) {
            if(outboxAwaitingAckDeadlineEpochMs > now) {
                scheduleOutboxRetry(outboxAwaitingAckDeadlineEpochMs - now);
                return;
            }
            QueuedSubmitOp timedOut = outboxAwaitingAck;
            outboxAwaitingAck = null;
            outboxAwaitingAckOpBatchId = null;
            outboxAwaitingAckDeadlineEpochMs = 0L;
            handleOutboxReplaySendResult(timedOut, new RuntimeException("ack-timeout"));
            return;
        }

        QueuedSubmitOp next = firstQueuedEntryForModel(currentModelId);
        if(next == null) {
            return;
        }
        if(next.nextRetryEpochMs > now) {
            scheduleOutboxRetry(next.nextRetryEpochMs - now);
            return;
        }

        outboxFlushInFlight = true;
        String rebased = rebaseSubmitOpsBaseRevision(next.submitOpsJson, lastKnownRevision);
        ArchiCollabPlugin.logInfo("Replaying queued offline op modelId=" + currentModelId
                + " rebaseRevision=" + lastKnownRevision);
        ArchiCollabPlugin.logTrace("WS OUT " + summarizeEnvelope(rebased));
        ws.sendText(rebased, true).whenComplete((ignored, ex) -> handleOutboxReplaySendResult(next, ex));
    }

    private void handleOutboxReplaySendResult(QueuedSubmitOp replayed, Throwable error) {
        synchronized(this) {
            outboxFlushInFlight = false;
            if(error == null) {
                outboxAwaitingAck = replayed;
                outboxAwaitingAckOpBatchId = extractOpBatchId(replayed.submitOpsJson);
                outboxAwaitingAckDeadlineEpochMs = System.currentTimeMillis() + OUTBOX_ACK_TIMEOUT_MS;
                scheduleOutboxRetry(OUTBOX_ACK_TIMEOUT_MS);
            }
            else {
                offlineOutbox.remove(replayed);
                int attempts = replayed.replayAttempts + 1;
                if(attempts >= OUTBOX_MAX_REPLAY_ATTEMPTS) {
                    ArchiCollabPlugin.logInfo("Dropping poison queued SubmitOps after max replay attempts modelId="
                            + replayed.modelId + " attempts=" + attempts + " reason=" + error.getClass().getSimpleName());
                    persistOutboxToDisk(replayed.modelId);
                }
                else {
                    long retryDelayMs = computeOutboxRetryDelayMs(attempts);
                    QueuedSubmitOp updated = new QueuedSubmitOp(
                            replayed.modelId,
                            replayed.submitOpsJson,
                            replayed.queuedAtEpochMs,
                            attempts,
                            System.currentTimeMillis() + retryDelayMs);
                    offlineOutbox.addFirst(updated);
                    persistOutboxToDisk(replayed.modelId);
                    ArchiCollabPlugin.logInfo("Retaining queued SubmitOps after replay send failure modelId="
                            + replayed.modelId
                            + " attempts=" + attempts
                            + " retryDelayMs=" + retryDelayMs
                            + " reason=" + error.getClass().getSimpleName());
                    scheduleOutboxRetry(retryDelayMs);
                }
            }
        }
        flushOutboxIfPossible();
    }

    private QueuedSubmitOp firstQueuedEntryForModel(String modelId) {
        for(QueuedSubmitOp queued : offlineOutbox) {
            if(modelId.equals(queued.modelId)) {
                return queued;
            }
        }
        return null;
    }

    private boolean hasQueuedEntriesForModel(String modelId) {
        return firstQueuedEntryForModel(modelId) != null;
    }

    private long computeOutboxRetryDelayMs(int attempts) {
        if(attempts <= 0) {
            return 0L;
        }
        long delay = OUTBOX_RETRY_INITIAL_DELAY_MS;
        for(int i = 1; i < attempts; i++) {
            delay = Math.min(OUTBOX_RETRY_MAX_DELAY_MS, delay * 2L);
        }
        return delay;
    }

    private void scheduleOutboxRetry(long delayMs) {
        long boundedDelayMs = Math.max(1L, Math.min(OUTBOX_RETRY_MAX_DELAY_MS, delayMs));
        synchronized(this) {
            if(outboxRetryScheduled) {
                return;
            }
            outboxRetryScheduled = true;
        }
        CompletableFuture.runAsync(
                this::retryOutboxFlushAfterDelay,
                CompletableFuture.delayedExecutor(boundedDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS))
                .exceptionally(ex -> {
                    synchronized(this) {
                        outboxRetryScheduled = false;
                    }
                    return null;
                });
    }

    private void retryOutboxFlushAfterDelay() {
        synchronized(this) {
            outboxRetryScheduled = false;
        }
        flushOutboxIfPossible();
    }

    public void onServerOpsAccepted(String opBatchId) {
        boolean advanced = false;
        synchronized(this) {
            if(outboxAwaitingAck != null && Objects.equals(opBatchId, outboxAwaitingAckOpBatchId)) {
                QueuedSubmitOp accepted = outboxAwaitingAck;
                outboxAwaitingAck = null;
                outboxAwaitingAckOpBatchId = null;
                outboxAwaitingAckDeadlineEpochMs = 0L;
                offlineOutbox.remove(accepted);
                persistOutboxToDisk(accepted.modelId);
                advanced = true;
            }
        }
        if(advanced) {
            flushOutboxIfPossible();
        }
    }

    public void onServerError(String code, String message) {
        boolean retry = false;
        boolean droppedConflict = false;
        String conflictModelId = null;
        String conflictOpBatchId = null;
        synchronized(this) {
            if(outboxAwaitingAck == null) {
                return;
            }
            QueuedSubmitOp waiting = outboxAwaitingAck;
            String waitingOpBatchId = outboxAwaitingAckOpBatchId;
            outboxAwaitingAck = null;
            outboxAwaitingAckOpBatchId = null;
            outboxAwaitingAckDeadlineEpochMs = 0L;

            if("PRECONDITION_FAILED".equals(code) || "LOCK_CONFLICT".equals(code)) {
                offlineOutbox.remove(waiting);
                persistOutboxToDisk(waiting.modelId);
                droppedConflict = true;
                conflictModelId = waiting.modelId;
                conflictOpBatchId = waitingOpBatchId;
            }
            else {
                retry = true;
            }
        }

        if(droppedConflict) {
            fireSubmitConflict(conflictModelId, conflictOpBatchId, code, message);
            flushOutboxIfPossible();
        }
        if(retry) {
            flushOutboxIfPossible();
        }
    }

    private void fireSubmitConflict(String modelId, String opBatchId, String code, String message) {
        lastConflictSnapshot = new ConflictSnapshot(modelId, opBatchId, code, message, System.currentTimeMillis());
        ArchiCollabPlugin.logInfo("Dropping stale queued SubmitOps due to server conflict modelId="
                + modelId + " opBatchId=" + opBatchId + " code=" + code + " message=" + message);
        for(SubmitConflictListener listener : submitConflictListeners) {
            listener.conflictDetected(modelId, opBatchId, code, message);
        }
    }

    private String extractModelId(String submitOpsJson) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(submitOpsJson, "payload"));
        return payload == null ? null : SimpleJson.readStringField(payload, "modelId");
    }

    private String extractOpBatchId(String submitOpsJson) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(submitOpsJson, "payload"));
        return payload == null ? null : SimpleJson.readStringField(payload, "opBatchId");
    }

    private String rebaseSubmitOpsBaseRevision(String submitOpsJson, long revision) {
        Pattern pattern = Pattern.compile("(\\\"baseRevision\\\"\\s*:\\s*)\\d+");
        Matcher matcher = pattern.matcher(submitOpsJson);
        if(!matcher.find()) {
            return submitOpsJson;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + Math.max(0L, revision)));
    }

    private void sendRaw(String payload) {
        WebSocket ws = webSocket;
        if(ws == null) {
            ArchiCollabPlugin.logTrace("sendRaw skipped: websocket not connected payload=" + summarizeEnvelope(payload));
            return;
        }

        ArchiCollabPlugin.logTrace("WS OUT " + summarizeEnvelope(payload));
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
                ArchiCollabPlugin.logTrace("WS IN " + summarizeEnvelope(message));
                if(ArchiCollabPlugin.isTraceEnabled()) {
                    String type = SimpleJson.readStringField(message, "type");
                    if("OpsBroadcast".equals(type)) {
                        ArchiCollabPlugin.logTrace("WS IN RAW OpsBroadcast " + message);
                    }
                }
                inboundMessageDispatcher.dispatch(message);
                String type = SimpleJson.readStringField(message, "type");
                if("CheckoutSnapshot".equals(type)
                        || "CheckoutDelta".equals(type)
                        || "OpsBroadcast".equals(type)
                        || "OpsAccepted".equals(type)) {
                    maybePersistCacheSnapshot(type, false);
                }
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

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            synchronized(CollabSessionManager.this) {
                if(CollabSessionManager.this.webSocket == webSocket) {
                    CollabSessionManager.this.webSocket = null;
                }
            }
            fireStateChanged(false, currentModelId);
            ArchiCollabPlugin.logInfo("Collaboration websocket closed statusCode=" + statusCode + " reason=" + reason);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CacheRejoinDecision resolveJoinDecision(String modelId) {
        if(serverBackedSession) {
            CacheMetadata metadata = readCacheMetadata(modelId);
            if(metadata == null) {
                return new CacheRejoinDecision(modelId, null, false, "no-cache-metadata");
            }
            if(!metadata.serverBacked) {
                return new CacheRejoinDecision(modelId, null, true, "cache-not-server-backed");
            }
            if(!Objects.equals(modelId, metadata.modelId)) {
                return new CacheRejoinDecision(modelId, null, true, "cache-modelId-mismatch");
            }
            if(!metadata.cacheFile.exists()) {
                return new CacheRejoinDecision(modelId, null, true, "cache-file-missing");
            }
            if(metadata.revision < 0) {
                return new CacheRejoinDecision(modelId, null, true, "cache-revision-unknown");
            }
            return new CacheRejoinDecision(modelId, metadata.revision, false, "cache-revision-rejoin");
        }
        return new CacheRejoinDecision(modelId, lastKnownRevision, false, "local-lastKnownRevision");
    }

    private void evaluateCacheRevisionComparison(long serverRevision) {
        if(!pendingCacheRevisionComparison) {
            return;
        }
        pendingCacheRevisionComparison = false;

        if(cacheRevisionAtJoin < 0) {
            return;
        }
        if(serverRevision > cacheRevisionAtJoin) {
            ArchiCollabPlugin.logInfo("Cache stale on reconnect: cacheRevision=" + cacheRevisionAtJoin
                    + " serverRevision=" + serverRevision + " (delta/snapshot refresh expected)");
            return;
        }
        if(serverRevision == cacheRevisionAtJoin) {
            ArchiCollabPlugin.logTrace("Cache revision matches server head on reconnect: revision=" + serverRevision);
            return;
        }

        ArchiCollabPlugin.logInfo("Cache revision inconsistent with server head: cacheRevision="
                + cacheRevisionAtJoin + " serverRevision=" + serverRevision
                + " (requesting cold snapshot rebuild)");
        requestColdSnapshotRebuild();
    }

    private synchronized void requestColdSnapshotRebuild() {
        if(coldSnapshotRebuildRequested) {
            return;
        }
        if(webSocket == null) {
            return;
        }
        coldSnapshotRebuildRequested = true;
        sendJoin(null);
    }

    private void maybePersistCacheSnapshot(String reason, boolean force) {
        if(!serverBackedSession) {
            return;
        }
        if(!canPersistCacheSnapshot()) {
            ArchiCollabPlugin.logTrace("Skipping collaboration cache persist while workbench is shutting down: reason=" + reason);
            return;
        }
        IArchimateModel model = attachedModel;
        String modelId = currentModelId;
        if(model == null || modelId == null || modelId.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        if(!force && now - lastCacheSaveEpochMillis < CACHE_SAVE_DEBOUNCE_MS) {
            return;
        }

        boolean executed = runOnUiThreadSync(() -> {
            try {
                if(!canPersistCacheSnapshot()) {
                    ArchiCollabPlugin.logTrace("Skipping collaboration cache persist on UI thread during shutdown: reason=" + reason);
                    return;
                }
                File cacheFile = resolveCacheFile(modelId);
                if(cacheFile == null) {
                    return;
                }

                File parent = cacheFile.getParentFile();
                if(parent != null && !parent.exists() && !parent.mkdirs()) {
                    ArchiCollabPlugin.logDebug("Could not create collaboration cache directory: " + parent);
                    return;
                }

                if(model.getFile() == null || !cacheFile.equals(model.getFile())) {
                    model.setFile(cacheFile);
                }

                boolean dirty = IEditorModelManager.INSTANCE.isModelDirty(model);
                if(dirty || force) {
                    IEditorModelManager.INSTANCE.saveModel(model);
                }
                writeCacheMetadata(cacheFile, modelId, lastKnownRevision);
                lastCacheSaveEpochMillis = System.currentTimeMillis();
                ArchiCollabPlugin.logTrace("Collaboration cache persisted reason=" + reason
                        + " modelId=" + modelId + " revision=" + lastKnownRevision + " dirty=" + dirty);
            }
            catch(Exception ex) {
                ArchiCollabPlugin.logError("Error persisting collaboration cache", ex);
            }
        });
        if(!executed) {
            ArchiCollabPlugin.logTrace("Skipping collaboration cache persist because UI thread is unavailable: reason=" + reason);
        }
    }

    private File resolveCacheFile(String modelId) {
        String safeModelId = sanitizeModelIdForFileName(modelId);
        if(safeModelId.isBlank()) {
            return null;
        }
        String userHome = System.getProperty("user.home", "");
        if(userHome.isBlank()) {
            return null;
        }
        Path path = Path.of(userHome, "Archi", CACHE_DIR_NAME, safeModelId + ".archimate");
        return path.toFile();
    }

    private String sanitizeModelIdForFileName(String modelId) {
        return modelId == null ? "" : modelId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void writeCacheMetadata(File cacheFile, String modelId, long revision) throws IOException {
        if(cacheFile == null) {
            return;
        }
        File metadataFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".meta.properties");
        Properties properties = new Properties();
        properties.setProperty("modelId", modelId == null ? "" : modelId);
        properties.setProperty("lastKnownRevision", String.valueOf(revision));
        properties.setProperty("serverBacked", String.valueOf(serverBackedSession));
        properties.setProperty("savedAtEpochMs", String.valueOf(System.currentTimeMillis()));
        try(FileOutputStream outputStream = new FileOutputStream(metadataFile)) {
            properties.store(outputStream, "Archi collaboration cache metadata");
        }
    }

    private CacheMetadata readCacheMetadata(String modelId) {
        File cacheFile = resolveCacheFile(modelId);
        if(cacheFile == null) {
            return null;
        }
        File metadataFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".meta.properties");
        if(!metadataFile.exists()) {
            return null;
        }

        Properties properties = new Properties();
        try(FileInputStream inputStream = new FileInputStream(metadataFile)) {
            properties.load(inputStream);
        }
        catch(IOException ex) {
            ArchiCollabPlugin.logInfo("Failed reading collaboration cache metadata, forcing snapshot rebuild: " + ex.getMessage());
            return new CacheMetadata(cacheFile, metadataFile, modelId, -1L, false);
        }

        String metadataModelId = properties.getProperty("modelId", "");
        long metadataRevision = parseLong(properties.getProperty("lastKnownRevision"), -1L);
        boolean metadataServerBacked = Boolean.parseBoolean(properties.getProperty("serverBacked", "false"));
        return new CacheMetadata(cacheFile, metadataFile, metadataModelId, metadataRevision, metadataServerBacked);
    }

    private long parseLong(String value, long fallback) {
        if(value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        }
        catch(NumberFormatException ex) {
            return fallback;
        }
    }

    private void discardCacheProjection(String modelId, String reason) {
        File cacheFile = resolveCacheFile(modelId);
        if(cacheFile == null) {
            return;
        }
        File metadataFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".meta.properties");
        File outboxFile = resolveOutboxFile(modelId);
        boolean removedCache = !cacheFile.exists() || cacheFile.delete();
        boolean removedMetadata = !metadataFile.exists() || metadataFile.delete();
        boolean removedOutbox = outboxFile == null || !outboxFile.exists() || outboxFile.delete();
        ArchiCollabPlugin.logInfo("Discarded collaboration cache projection reason=" + reason
                + " modelId=" + modelId
                + " cacheDeleted=" + removedCache
                + " metadataDeleted=" + removedMetadata
                + " outboxDeleted=" + removedOutbox);
    }

    private synchronized void loadOutboxFromDisk(String modelId) {
        File outboxFile = resolveOutboxFile(modelId);
        if(outboxFile == null || !outboxFile.exists()) {
            return;
        }
        // Prevent duplicates on reconnect by clearing in-memory entries for this model first.
        offlineOutbox.removeIf(queued -> modelId.equals(queued.modelId));

        Properties properties = new Properties();
        try(FileInputStream inputStream = new FileInputStream(outboxFile)) {
            properties.load(inputStream);
        }
        catch(IOException ex) {
            ArchiCollabPlugin.logInfo("Could not read durable outbox for modelId=" + modelId + " reason=" + ex.getMessage());
            return;
        }

        int count = (int)parseLong(properties.getProperty("count"), 0L);
        if(count <= 0) {
            return;
        }

        int loaded = 0;
        for(int i = 0; i < count; i++) {
            String encoded = properties.getProperty("item." + i + ".payload");
            if(encoded == null || encoded.isBlank()) {
                continue;
            }
            String json = decodeBase64Utf8(encoded);
            if(json == null || json.isBlank()) {
                continue;
            }
            long queuedAt = parseLong(properties.getProperty("item." + i + ".queuedAtEpochMs"), System.currentTimeMillis());
            int replayAttempts = (int)parseLong(properties.getProperty("item." + i + ".replayAttempts"), 0L);
            long nextRetryEpochMs = parseLong(properties.getProperty("item." + i + ".nextRetryEpochMs"), 0L);
            if(offlineOutbox.size() >= MAX_OUTBOX_SIZE) {
                offlineOutbox.removeFirst();
            }
            offlineOutbox.addLast(new QueuedSubmitOp(modelId, json, queuedAt, replayAttempts, nextRetryEpochMs));
            loaded++;
        }

        if(loaded > 0) {
            ArchiCollabPlugin.logInfo("Loaded durable outbox entries modelId=" + modelId + " count=" + loaded);
        }
    }

    private synchronized void persistOutboxToDisk(String modelId) {
        File outboxFile = resolveOutboxFile(modelId);
        if(outboxFile == null) {
            return;
        }
        File parent = outboxFile.getParentFile();
        if(parent != null && !parent.exists() && !parent.mkdirs()) {
            ArchiCollabPlugin.logDebug("Could not create outbox directory: " + parent);
            return;
        }

        List<QueuedSubmitOp> modelEntries = new ArrayList<>();
        for(QueuedSubmitOp queued : offlineOutbox) {
            if(modelId.equals(queued.modelId)) {
                modelEntries.add(queued);
            }
        }
        if(modelEntries.isEmpty()) {
            if(outboxFile.exists() && !outboxFile.delete()) {
                ArchiCollabPlugin.logDebug("Could not delete empty durable outbox file: " + outboxFile);
            }
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("modelId", modelId);
        properties.setProperty("count", String.valueOf(modelEntries.size()));
        for(int i = 0; i < modelEntries.size(); i++) {
            QueuedSubmitOp queued = modelEntries.get(i);
            properties.setProperty("item." + i + ".queuedAtEpochMs", String.valueOf(queued.queuedAtEpochMs));
            properties.setProperty("item." + i + ".replayAttempts", String.valueOf(queued.replayAttempts));
            properties.setProperty("item." + i + ".nextRetryEpochMs", String.valueOf(queued.nextRetryEpochMs));
            properties.setProperty("item." + i + ".payload", encodeBase64Utf8(queued.submitOpsJson));
        }
        try(FileOutputStream outputStream = new FileOutputStream(outboxFile)) {
            properties.store(outputStream, "Archi collaboration durable outbox");
        }
        catch(IOException ex) {
            ArchiCollabPlugin.logInfo("Could not persist durable outbox for modelId=" + modelId + " reason=" + ex.getMessage());
        }
    }

    private File resolveOutboxFile(String modelId) {
        File cacheFile = resolveCacheFile(modelId);
        if(cacheFile == null) {
            return null;
        }
        return new File(cacheFile.getParentFile(), cacheFile.getName() + OUTBOX_FILE_SUFFIX);
    }

    private String encodeBase64Utf8(String value) {
        if(value == null) {
            return "";
        }
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeBase64Utf8(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return new String(decoded, StandardCharsets.UTF_8);
        }
        catch(IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean runOnUiThreadSync(Runnable runnable) {
        Display display = getDisplay();
        if(display == null || display.isDisposed()) {
            return false;
        }
        if(Thread.currentThread() == display.getThread()) {
            runnable.run();
            return true;
        }
        try {
            display.syncExec(() -> {
                if(!display.isDisposed()) {
                    runnable.run();
                }
            });
            return true;
        }
        catch(SWTException ex) {
            ArchiCollabPlugin.logDebug("Skipping collaboration cache save due to SWT shutdown: " + ex.getMessage());
            return false;
        }
    }

    private boolean canPersistCacheSnapshot() {
        if(!isWorkbenchRunning()) {
            return false;
        }
        Display display = getDisplay();
        return display != null && !display.isDisposed();
    }

    protected boolean isWorkbenchRunning() {
        return PlatformUI.isWorkbenchRunning();
    }

    protected Display getDisplay() {
        return Display.getDefault();
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

    private record CacheMetadata(
            File cacheFile,
            File metadataFile,
            String modelId,
            long revision,
            boolean serverBacked) {
    }

    private record CacheRejoinDecision(
            String modelId,
            Long joinRevision,
            boolean discardCacheProjection,
            String reason) {
    }

    private record QueuedSubmitOp(
            String modelId,
            String submitOpsJson,
            long queuedAtEpochMs,
            int replayAttempts,
            long nextRetryEpochMs) {
    }

    public record ConflictSnapshot(
            String modelId,
            String opBatchId,
            String code,
            String message,
            long occurredAtEpochMs) {
    }
}
