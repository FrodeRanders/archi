/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package com.archimatetool.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import com.archimatetool.collab.ws.CollabSessionManager;
import org.eclipse.swt.widgets.Display;

@SuppressWarnings("unchecked")
public class CollabSessionManagerDurableOutboxTests {

    @Test
    public void durableOutboxReloadAndReplayRebasesBaseRevision() throws Exception {
        String modelId = "durable-outbox-" + UUID.randomUUID();
        File outboxFile = outboxFileForModel(modelId);
        deleteIfExists(outboxFile);

        try {
            CollabSessionManager writer = new CollabSessionManager();
            writer.sendSubmitOps(submitOpsEnvelope(modelId, 0L, "batch-1"));
            assertTrue(outboxFile.exists(), "Expected durable outbox file to be created");

            CollabSessionManager reloaded = new CollabSessionManager();
            invokePrivate(reloaded, "loadOutboxFromDisk", new Class<?>[] { String.class }, modelId);
            Deque<Object> queueBeforeReplay = (Deque<Object>)getPrivateField(reloaded, "offlineOutbox");
            assertEquals(1, queueBeforeReplay.size(), "Expected one queued op loaded from durable outbox");

            CapturingWebSocket socket = new CapturingWebSocket();
            setPrivateField(reloaded, "currentModelId", modelId);
            setPrivateField(reloaded, "webSocket", socket);
            reloaded.setLastKnownRevision(42L);

            assertEquals(1, socket.sentTexts.size(), "Expected queued op to be replayed");
            String replayedEnvelope = socket.sentTexts.get(0);
            assertTrue(replayedEnvelope.contains("\"baseRevision\":42"), "Expected replayed op to be rebased to revision 42");

            Deque<Object> queueAfterReplay = (Deque<Object>)getPrivateField(reloaded, "offlineOutbox");
            assertEquals(0, queueAfterReplay.size(), "Expected outbox queue to be drained after replay");
            assertFalse(outboxFile.exists(), "Expected durable outbox file to be removed after replay");
        }
        finally {
            deleteIfExists(outboxFile);
        }
    }

    @Test
    public void cachePersistIsSkippedWhenWorkbenchUnavailable() throws Exception {
        TestableSessionManager manager = new TestableSessionManager(false, null);
        setPrivateField(manager, "serverBackedSession", true);
        setPrivateField(manager, "lastCacheSaveEpochMillis", 0L);

        invokePrivate(manager, "maybePersistCacheSnapshot", new Class<?>[] { String.class, boolean.class }, "test", true);

        long lastSavedAt = (long)getPrivateField(manager, "lastCacheSaveEpochMillis");
        assertEquals(0L, lastSavedAt, "Expected no cache persistence attempt while workbench is unavailable");
    }

    @Test
    public void uiThreadSyncReturnsFalseWhenDisplayUnavailable() throws Exception {
        TestableSessionManager manager = new TestableSessionManager(true, null);
        AtomicBoolean executed = new AtomicBoolean(false);

        boolean result = (boolean)invokePrivate(
                manager,
                "runOnUiThreadSync",
                new Class<?>[] { Runnable.class },
                (Runnable)(() -> executed.set(true)));

        assertFalse(result, "Expected UI sync helper to report unavailable display");
        assertFalse(executed.get(), "Runnable should not execute when display is unavailable");
    }

    @Test
    public void uiThreadSyncExecutesWhenDisplayAvailable() throws Exception {
        Display display = Display.getDefault();
        if(display == null || display.isDisposed()) {
            return;
        }

        TestableSessionManager manager = new TestableSessionManager(true, display);
        AtomicBoolean executed = new AtomicBoolean(false);

        boolean result = (boolean)invokePrivate(
                manager,
                "runOnUiThreadSync",
                new Class<?>[] { Runnable.class },
                (Runnable)(() -> executed.set(true)));

        assertTrue(result, "Expected UI sync helper to run when display is available");
        assertTrue(executed.get(), "Runnable should execute when display is available");
    }

    private String submitOpsEnvelope(String modelId, long baseRevision, String opBatchId) {
        return "{"
                + "\"type\":\"SubmitOps\","
                + "\"payload\":{"
                + "\"modelId\":\"" + modelId + "\","
                + "\"baseRevision\":" + baseRevision + ","
                + "\"opBatchId\":\"" + opBatchId + "\","
                + "\"actor\":{\"userId\":\"test\",\"sessionId\":\"test-session\"},"
                + "\"timestamp\":\"" + Instant.now() + "\","
                + "\"ops\":[{\"type\":\"UpdateElement\",\"elementId\":\"elem:id-1\",\"patch\":{\"name\":\"N\"}}]"
                + "}"
                + "}";
    }

    private File outboxFileForModel(String modelId) {
        String safeModelId = modelId.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path path = Path.of(System.getProperty("user.home"), "Archi", "collab-cache", safeModelId + ".archimate.outbox.properties");
        return path.toFile();
    }

    private void deleteIfExists(File file) {
        if(file != null && file.exists()) {
            file.delete();
        }
    }

    private Object getPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object invokePrivate(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static final class CapturingWebSocket implements WebSocket {
        private final List<String> sentTexts = new ArrayList<>();

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sentTexts.add(data.toString());
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
    }

    private static final class TestableSessionManager extends CollabSessionManager {
        private final boolean workbenchRunning;
        private final Display display;

        private TestableSessionManager(boolean workbenchRunning, Display display) {
            this.workbenchRunning = workbenchRunning;
            this.display = display;
        }

        @Override
        protected boolean isWorkbenchRunning() {
            return workbenchRunning;
        }

        @Override
        protected Display getDisplay() {
            return display;
        }
    }
}
