package cc.superbaby.happy4j.application;

import cc.superbaby.happy4j.application.port.GameTransportPort;
import cc.superbaby.happy4j.domain.ClientSession;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe owner-aware registry for non-persistable live sockets and aggregates.
 */
@Component
public class ClientRuntimeRegistry implements AutoCloseable {
    private final ConcurrentHashMap<UUID, ClientSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, GameTransportPort> connections = new ConcurrentHashMap<>();

    public void add(ClientSession session) {
        if (sessions.putIfAbsent(session.id(), session) != null)
            throw new IllegalStateException("重复会话: " + session.id());
    }

    public ClientSession get(UUID id) {
        ClientSession value = sessions.get(id);
        if (value == null) throw new IllegalArgumentException("会话不存在: " + id);
        return value;
    }

    public ClientSession owned(UUID userId, UUID id) {
        ClientSession value = get(id);
        if (!value.ownerId().equals(userId))
            throw new IllegalArgumentException("会话不存在: " + id);
        return value;
    }

    public List<ClientSession.Snapshot> snapshots(UUID userId) {
        return sessions.values().stream().filter(value -> value.ownerId().equals(userId))
                .map(ClientSession::snapshot).sorted(Comparator.comparing(ClientSession.Snapshot::createdAt).reversed())
                .toList();
    }

    public synchronized void bind(UUID sessionId, GameTransportPort connection,
                                  int maxActiveUsers, int maxSessionsPerUser) {
        ClientSession session = get(sessionId);
        boolean replacing = connections.containsKey(sessionId);
        if (!replacing) assertCapacity(session.ownerId(), maxActiveUsers, maxSessionsPerUser);
        GameTransportPort previous = connections.put(sessionId, connection);
        if (previous != null && previous != connection) previous.close();
    }

    /**
     * A failed Lotus socket must not continue occupying a user's session slot.
     * Reap only disconnected transports. Healthy connections are never replaced
     * simply because another login attempt was made.
     */
    private void discardDisconnectedConnections() {
        connections.forEach((id, connection) -> {
            if (!connection.connected() && connections.remove(id, connection)) connection.close();
        });
    }

    public synchronized void assertCapacity(UUID ownerId, int maxActiveUsers, int maxSessionsPerUser) {
        discardDisconnectedConnections();
        long owned = connections.keySet().stream()
                .map(sessions::get)
                .filter(java.util.Objects::nonNull)
                .filter(session -> session.ownerId().equals(ownerId))
                .count();
        if (owned >= Math.max(1, maxSessionsPerUser)) {
            throw new IllegalStateException("当前账号已有活跃游戏连接");
        }
        if (owned == 0 && activeUserCount() >= Math.max(1, maxActiveUsers)) {
            throw new IllegalStateException("服务器活跃用户已达上限: " + maxActiveUsers);
        }
    }

    public int activeConnectionCount() {
        return connections.size();
    }

    public long activeUserCount() {
        return connections.keySet().stream().map(sessions::get).filter(java.util.Objects::nonNull)
                .map(ClientSession::ownerId).distinct().count();
    }

    public GameTransportPort connection(UUID sessionId) {
        GameTransportPort value = connections.get(sessionId);
        if (value == null) throw new IllegalStateException("大厅尚未连接");
        return value;
    }

    public boolean connected(UUID sessionId) {
        GameTransportPort value = connections.get(sessionId);
        return value != null && value.connected();
    }

    public void remove(UUID sessionId) {
        disconnect(sessionId);
        sessions.remove(sessionId);
    }

    public void disconnect(UUID sessionId) {
        GameTransportPort connection = connections.remove(sessionId);
        if (connection != null) connection.close();
    }

    @Override
    public void close() {
        connections.values().forEach(GameTransportPort::close);
        connections.clear();
    }
}
