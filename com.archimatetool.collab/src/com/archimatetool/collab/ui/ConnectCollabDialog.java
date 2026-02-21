package com.archimatetool.collab.ui;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Dialog for collaboration connection settings.
 */
public class ConnectCollabDialog extends TitleAreaDialog {

    private String wsBaseUrl;
    private String modelId;
    private String userId;
    private String sessionId;

    private Text wsBaseUrlText;
    private Text modelIdText;
    private Text userIdText;
    private Text sessionIdText;

    public ConnectCollabDialog(Shell parentShell, String wsBaseUrl, String modelId, String userId, String sessionId) {
        super(parentShell);
        this.wsBaseUrl = wsBaseUrl;
        this.modelId = modelId;
        this.userId = userId;
        this.sessionId = sessionId;
    }

    @Override
    public void create() {
        super.create();
        setTitle("Connect Collaboration");
        setMessage("Configure websocket endpoint and actor information for model collaboration.");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite)super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        container.setLayout(new GridLayout(2, false));

        createLabel(container, "WebSocket Base URL");
        wsBaseUrlText = createText(container, wsBaseUrl, "ws://localhost:8081");

        createLabel(container, "Model ID");
        modelIdText = createText(container, modelId, "Required");

        createLabel(container, "User ID");
        userIdText = createText(container, userId, "anonymous");

        createLabel(container, "Session ID");
        sessionIdText = createText(container, sessionId, "auto-generated if empty");

        return area;
    }

    @Override
    protected void okPressed() {
        wsBaseUrl = trimOrEmpty(wsBaseUrlText.getText());
        modelId = trimOrEmpty(modelIdText.getText());
        userId = trimOrEmpty(userIdText.getText());
        sessionId = trimOrEmpty(sessionIdText.getText());

        if(wsBaseUrl.isEmpty()) {
            setErrorMessage("WebSocket base URL is required");
            return;
        }

        if(modelId.isEmpty()) {
            setErrorMessage("Model ID is required");
            return;
        }

        if(userId.isEmpty()) {
            userId = "anonymous";
        }

        if(sessionId.isEmpty()) {
            sessionId = "archi-local";
        }

        super.okPressed();
    }

    public String getWsBaseUrl() {
        return wsBaseUrl;
    }

    public String getModelId() {
        return modelId;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    private void createLabel(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
    }

    private Text createText(Composite parent, String value, String message) {
        Text text = new Text(parent, SWT.BORDER);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        text.setMessage(message);
        text.setText(value == null ? "" : value);
        return text;
    }

    private String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
