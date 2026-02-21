# com.archimatetool.collab

Archi collaboration client plugin skeleton.

## Implemented in this scaffold
- Plugin activator: `ArchiCollabPlugin`
- Early startup extension: `CollabStartup`
- WebSocket session manager: `CollabSessionManager`
- Inbound envelope dispatcher: `InboundMessageDispatcher`
- Echo suppression guard: `RemoteApplyGuard`
- Remote apply placeholder: `RemoteOpApplier`
- EMF change capture adapter: `EmfChangeCapture`
- Minimal semantic op mapping: `OpMapper` (`CreateElement` only)
- Model attach/detach helper: `ModelCollaborationController`
- Notation serializer/deserializer placeholders

## Current behavior
- Can connect/disconnect websocket and send Join/SubmitOps/Lock/Presence envelopes.
- On local `Notification.ADD` of `IArchimateElement`, emits a minimal `SubmitOps` with one `CreateElement` op.
- Remote apply path is placeholder (logs only).

## Next steps
1. Replace string-based JSON handling with robust JSON serialization/parsing.
2. Wire controller attachment to active Archi model lifecycle (open/close/save/reconnect).
3. Implement semantic updates/deletes and relationship/view ops.
4. Implement notation opaque round-trip from `notation_mapping.md`.
5. Add debounce/coalesce for notation updates (150-250ms).
6. Add lock acquisition hooks on drag/resize/bendpoint edit start.
