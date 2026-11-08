# WebSocket notifications across backend instances

Clients still use STOMP over `/ws-connection` (or `WS_CONNECT_ENDPOINT`). Redis is
the internal fan-out channel between backend instances, not a replacement for
the client WebSocket protocol.

## Client contract

- Send `Authorization: Bearer <access token>` in the **STOMP CONNECT headers**.
  Anonymous, invalid, expired, disabled-user and revoked-token connections are rejected.
- Subscribe to `/user/queue/notifications`, `/user/queue/invitation` or
  `/user/queue/report`. Do not put a username in the subscription destination.
- Direct `/queue/**`, `/topic/**`, another user's destination and client STOMP
  SEND frames are rejected. There are no client SEND handlers in this notification API.
- `/user/queue/notifications` keeps its existing array of `NotificationResponse`
  objects. Invitation/report queues keep their existing plain string payloads.

Unread notifications are sent only after the local broker has processed the
notification subscription, and only to the session that just subscribed. The
application no longer sends this batch during CONNECT, before SUBSCRIBE exists.

Clients should subscribe again after reconnecting, fetch `GET /notifications`
to reconcile state, and merge by notification `id`. Unread replay and live events
can overlap; receiving a message does not mark it read. Use the existing
`PATCH /notifications/{id}/read` endpoint for that.

## Server delivery and failure behavior

1. Business-event listeners run after their originating transaction commits
   (events raised without a transaction still work).
2. `sendToUser` persists the notification. A Redis publish is registered for
   after the notification transaction commits; rollback does not publish.
3. Every backend instance subscribes to the same Redis channel and forwards each
   event to its own matching authenticated WebSocket sessions.
4. The publishing instance also receives through Redis. It does not additionally
   send locally, and subscribers do not republish.

Redis Pub/Sub is best effort: events during a subscriber outage are not replayed.
Database notifications and the REST API are the recovery path. A Redis publish
failure does not undo a committed notification. A process crash after DB commit
but before publish is also recovered through REST/unread replay, not a durable
event queue. The legacy invitation/report string methods do not persist data;
use `sendToUser` when a notification must survive an outage.

WebSocket connections stay on the instance that accepted them. On Pod restart,
the client must reconnect, authenticate and resubscribe. Shared Redis does not
move an open socket to another Pod.

## Configuration

Use the existing `REDIS_HOST` and `REDIS_PORT`. All backend instances must reach
the same Redis server and use the same channel:

```properties
app.websocket.redis-channel=mosquizto:websocket:notifications
app.websocket.redis-enabled=true
```

The channel is configurable with `APP_WEBSOCKET_REDIS_CHANNEL`; use different
channels if environments share Redis. The listener is enabled by default.
`application-test.yml` disables only the listener so ordinary tests do not need
Redis. Disabling it in a deployment disables realtime fan-out; it is not a
single-instance delivery fallback.

## Verification

Ordinary tests:

```powershell
.\mvnw.cmd test
```

For the integration test, start a separate local Redis bound to localhost, then
pass its port (example: 16389):

```powershell
.\mvnw.cmd "-Dtest=WebSocketRedisIntegrationTest" "-Dwebsocket.redis.port=16389" test
```

This starts two local embedded servers on random ports, using a unique test
channel. It checks cross-instance delivery, publisher delivery without a second
copy, user isolation, session-specific unread replay, reconnect and listener
resubscription. Database repositories and JWT validation are mocked in this
integration test; transaction commit/rollback and Redis outage fallback are
tested separately against H2. No AWS resources are needed for these tests.
