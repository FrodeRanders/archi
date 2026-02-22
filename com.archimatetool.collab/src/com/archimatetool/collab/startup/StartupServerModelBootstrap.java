package com.archimatetool.collab.startup;

import java.util.UUID;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IIdentifier;

/**
 * Optional startup bootstrap that opens a model from the collaboration server.
 * This flow is opt-in via system properties or environment variables.
 */
final class StartupServerModelBootstrap {

    private static final String PROP_ENABLED = "archi.collab.startup.pull.enabled";
    private static final String PROP_WS_BASE_URL = "archi.collab.startup.pull.wsBaseUrl";
    private static final String PROP_MODEL_ID = "archi.collab.startup.pull.modelId";
    private static final String PROP_USER_ID = "archi.collab.startup.pull.userId";
    private static final String PROP_SESSION_ID = "archi.collab.startup.pull.sessionId";
    private static final String PROP_MODEL_NAME = "archi.collab.startup.pull.modelName";

    private static final String ENV_ENABLED = "ARCHI_COLLAB_STARTUP_PULL_ENABLED";
    private static final String ENV_WS_BASE_URL = "ARCHI_COLLAB_STARTUP_PULL_WS_BASE_URL";
    private static final String ENV_MODEL_ID = "ARCHI_COLLAB_STARTUP_PULL_MODEL_ID";
    private static final String ENV_USER_ID = "ARCHI_COLLAB_STARTUP_PULL_USER_ID";
    private static final String ENV_SESSION_ID = "ARCHI_COLLAB_STARTUP_PULL_SESSION_ID";
    private static final String ENV_MODEL_NAME = "ARCHI_COLLAB_STARTUP_PULL_MODEL_NAME";

    private static final String DEFAULT_WS_BASE_URL = "ws://localhost:8081";
    private static final String DEFAULT_USER_ID = "anonymous";

    void maybeBootstrap(ArchiCollabPlugin plugin) {
        StartupConfig config = StartupConfig.read();
        if(!config.enabled()) {
            return;
        }
        if(config.modelId().isBlank()) {
            ArchiCollabPlugin.logInfo("Startup server-pull enabled but modelId is missing; skipping");
            return;
        }

        Display display = resolveDisplay();
        if(display == null || display.isDisposed()) {
            ArchiCollabPlugin.logInfo("Startup server-pull requested but UI display is unavailable; skipping");
            return;
        }

        display.asyncExec(() -> bootstrapOnUiThread(plugin, config));
    }

    private void bootstrapOnUiThread(ArchiCollabPlugin plugin, StartupConfig config) {
        if(plugin == null || plugin.getSessionManager() == null) {
            return;
        }
        if(!PlatformUI.isWorkbenchRunning()) {
            ArchiCollabPlugin.logInfo("Startup server-pull skipped: workbench is not running");
            return;
        }
        if(!IEditorModelManager.INSTANCE.getModels().isEmpty()) {
            ArchiCollabPlugin.logInfo("Startup server-pull skipped: model(s) already open");
            return;
        }

        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        model.setName(config.modelName().isBlank()
                ? "Collaboration: " + config.modelId()
                : config.modelName());
        if(model instanceof IIdentifier identifier) {
            identifier.setId(config.modelId());
        }

        IEditorModelManager.INSTANCE.openModel(model);

        CollabSessionManager sessionManager = plugin.getSessionManager();
        sessionManager.setActor(config.userId(), config.sessionId());
        sessionManager.setServerBackedSession(true);
        sessionManager.connect(config.wsBaseUrl(), config.modelId());
        if(sessionManager.isConnected()) {
            sessionManager.attachModel(model);
            ArchiCollabPlugin.logInfo("Startup server-pull connected modelId=" + config.modelId());
        }
        else {
            ArchiCollabPlugin.logInfo("Startup server-pull failed to connect modelId=" + config.modelId());
        }
    }

    private Display resolveDisplay() {
        if(PlatformUI.isWorkbenchRunning()) {
            return PlatformUI.getWorkbench().getDisplay();
        }
        return Display.getDefault();
    }

    private record StartupConfig(
            boolean enabled,
            String wsBaseUrl,
            String modelId,
            String userId,
            String sessionId,
            String modelName) {

        private static StartupConfig read() {
            boolean enabled = parseBoolean(readValue(PROP_ENABLED, ENV_ENABLED), false);
            String wsBaseUrl = defaultIfBlank(readValue(PROP_WS_BASE_URL, ENV_WS_BASE_URL), DEFAULT_WS_BASE_URL);
            String modelId = defaultIfBlank(readValue(PROP_MODEL_ID, ENV_MODEL_ID), "");
            String userId = defaultIfBlank(readValue(PROP_USER_ID, ENV_USER_ID), DEFAULT_USER_ID);
            String configuredSessionId = defaultIfBlank(readValue(PROP_SESSION_ID, ENV_SESSION_ID), "");
            String sessionId = configuredSessionId.isBlank()
                    ? "archi-startup-" + UUID.randomUUID()
                    : configuredSessionId;
            String modelName = defaultIfBlank(readValue(PROP_MODEL_NAME, ENV_MODEL_NAME), "");
            return new StartupConfig(enabled, wsBaseUrl, modelId, userId, sessionId, modelName);
        }
    }

    private static String readValue(String propertyKey, String envKey) {
        String property = System.getProperty(propertyKey);
        if(property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv(envKey);
        return env == null ? "" : env.trim();
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if(value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
