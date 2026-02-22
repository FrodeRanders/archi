package com.archimatetool.collab.ws;

import java.util.ArrayList;
import java.util.List;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.emf.RemoteOpApplier;
import com.archimatetool.collab.util.SimpleJson;

/**
 * Dispatches server messages by envelope type.
 * JSON is handled as raw text for now to keep dependencies minimal.
 */
public class InboundMessageDispatcher {
    private static final int MAX_BUFFERED_ENVELOPES = 512;

    private final RemoteOpApplier remoteOpApplier;
    private final CollabSessionManager sessionManager;
    private final List<String> bufferedMutatingEnvelopes = new ArrayList<>();

    public InboundMessageDispatcher(CollabSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.remoteOpApplier = new RemoteOpApplier(sessionManager);
    }

    public void dispatch(String envelopeJson) {
        String type = readStringField(envelopeJson, "type");
        if(type == null) {
            ArchiCollabPlugin.logInfo("Ignoring collaboration message without type");
            return;
        }

        switch(type) {
            case "CheckoutSnapshot":
                syncRevisionHint(envelopeJson);
                if(bufferUntilModelAttached(envelopeJson, type)) {
                    break;
                }
                remoteOpApplier.applySnapshotEnvelope(envelopeJson);
                ArchiCollabPlugin.logInfo("Received checkout payload type=" + type);
                break;
            case "CheckoutDelta":
                syncRevisionHint(envelopeJson);
                if(bufferUntilModelAttached(envelopeJson, type)) {
                    break;
                }
                applyCheckoutDeltaOps(envelopeJson);
                ArchiCollabPlugin.logInfo("Received checkout payload type=" + type);
                break;
            case "OpsAccepted":
                syncRevisionHint(envelopeJson);
                ArchiCollabPlugin.logInfo("Received OpsAccepted");
                break;
            case "OpsBroadcast":
                if(bufferUntilModelAttached(envelopeJson, type)) {
                    break;
                }
                ArchiCollabPlugin.logTrace("Dispatching OpsBroadcast: " + summarizeOpsBroadcast(envelopeJson));
                remoteOpApplier.applyOpsEnvelope(envelopeJson);
                break;
            case "LockEvent":
            case "PresenceBroadcast":
                ArchiCollabPlugin.logInfo("Received collaboration event type=" + type);
                break;
            case "Error":
                ArchiCollabPlugin.logInfo("Received collaboration error payload");
                break;
            default:
                ArchiCollabPlugin.logInfo("Unhandled collaboration message type=" + type);
                break;
        }
    }

    public synchronized void replayBufferedMutationsIfAny() {
        if(sessionManager.getAttachedModel() == null || bufferedMutatingEnvelopes.isEmpty()) {
            return;
        }
        List<String> replay = new ArrayList<>(bufferedMutatingEnvelopes);
        bufferedMutatingEnvelopes.clear();
        ArchiCollabPlugin.logInfo("Replaying buffered collaboration envelopes count=" + replay.size());
        for(String envelope : replay) {
            dispatch(envelope);
        }
    }

    private boolean bufferUntilModelAttached(String envelopeJson, String type) {
        if(sessionManager.getAttachedModel() != null) {
            return false;
        }
        synchronized(this) {
            if(bufferedMutatingEnvelopes.size() >= MAX_BUFFERED_ENVELOPES) {
                bufferedMutatingEnvelopes.remove(0);
            }
            bufferedMutatingEnvelopes.add(envelopeJson);
        }
        ArchiCollabPlugin.logInfo("Buffered collaboration envelope type=" + type + " until model is attached");
        return true;
    }

    private void syncRevisionHint(String envelopeJson) {
        long revision = -1;

        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload"));
        if(payload != null) {
            // Join replies and acceptance events.
            Long headRevision = SimpleJson.readLongField(payload, "headRevision");
            if(headRevision != null) {
                revision = headRevision;
            }

            // CheckoutDelta format: payload.toRevision
            if(revision < 0) {
                Long toRevision = SimpleJson.readLongField(payload, "toRevision");
                if(toRevision != null) {
                    revision = toRevision;
                }
            }

            // OpsAccepted / OpsBroadcast format: payload.opBatch.assignedRevisionRange.to
            if(revision < 0) {
                String opBatch = SimpleJson.asJsonObject(SimpleJson.readRawField(payload, "opBatch"));
                if(opBatch != null) {
                    String assignedRange = SimpleJson.asJsonObject(SimpleJson.readRawField(opBatch, "assignedRevisionRange"));
                    if(assignedRange != null) {
                        Long to = SimpleJson.readLongField(assignedRange, "to");
                        if(to != null) {
                            revision = to;
                        }
                    }
                }
            }
        }

        // Backward-compatible fallbacks for older/simple payloads.
        if(revision < 0) {
            revision = readLongField(envelopeJson, "headRevision");
        }
        if(revision < 0) {
            revision = readLongField(envelopeJson, "to");
        }
        if(revision < 0) {
            revision = readLongField(envelopeJson, "toRevision");
        }
        if(revision >= 0) {
            sessionManager.setLastKnownRevision(revision);
        }
    }

    private void applyCheckoutDeltaOps(String envelopeJson) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload"));
        if(payload == null) {
            return;
        }
        var opBatches = SimpleJson.readArrayObjectElements(payload, "opBatches");
        if(opBatches.isEmpty()) {
            return;
        }
        for(String opBatch : opBatches) {
            // Reuse existing OpsBroadcast apply path to keep op handling centralized.
            String syntheticEnvelope = "{\"type\":\"OpsBroadcast\",\"payload\":{\"opBatch\":" + opBatch + "}}";
            remoteOpApplier.applyOpsEnvelope(syntheticEnvelope);
        }
        ArchiCollabPlugin.logTrace("Applied checkout delta op batches count=" + opBatches.size());
    }

    private String readStringField(String json, String key) {
        if(json == null) {
            return null;
        }

        String token = "\"" + key + "\"";
        int i = json.indexOf(token);
        if(i < 0) {
            return null;
        }

        int colon = json.indexOf(':', i + token.length());
        if(colon < 0) {
            return null;
        }

        int q1 = json.indexOf('"', colon + 1);
        if(q1 < 0) {
            return null;
        }
        int q2 = json.indexOf('"', q1 + 1);
        if(q2 < 0) {
            return null;
        }

        return json.substring(q1 + 1, q2);
    }

    private long readLongField(String json, String key) {
        if(json == null) {
            return -1;
        }

        String token = "\"" + key + "\"";
        int i = json.indexOf(token);
        if(i < 0) {
            return -1;
        }
        int colon = json.indexOf(':', i + token.length());
        if(colon < 0) {
            return -1;
        }

        int start = colon + 1;
        while(start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while(end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if(start == end) {
            return -1;
        }

        try {
            return Long.parseLong(json.substring(start, end));
        }
        catch(NumberFormatException ex) {
            return -1;
        }
    }

    private String summarizeOpsBroadcast(String envelopeJson) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload"));
        String opBatch = payload == null ? null : SimpleJson.asJsonObject(SimpleJson.readRawField(payload, "opBatch"));
        if(opBatch == null && payload != null && SimpleJson.readRawField(payload, "ops") != null) {
            opBatch = payload;
        }
        if(opBatch == null) {
            String rootBatch = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "opBatch"));
            if(rootBatch != null) {
                opBatch = rootBatch;
            }
        }
        if(opBatch == null) {
            return "payload/opBatch missing";
        }
        String opBatchId = SimpleJson.readStringField(opBatch, "opBatchId");
        String modelId = SimpleJson.readStringField(opBatch, "modelId");
        int opCount = SimpleJson.readArrayObjectElements(opBatch, "ops").size();
        return "modelId=" + modelId + " opBatchId=" + opBatchId + " opCount=" + opCount;
    }
}
