package cc.superbaby.happy4j.application;

import cc.superbaby.happy4j.application.port.GameTransportPort;
import cc.superbaby.happy4j.domain.ClientSession;
import cc.superbaby.happy4j.domain.GameCredential;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientRuntimeRegistryTest {
    @Test
    void supportsConfiguredSixtyFourConcurrentUsers() {
        ClientRuntimeRegistry registry = new ClientRuntimeRegistry();
        List<ClientSession> sessions = IntStream.range(0, 64)
                .mapToObj(ignored -> add(registry, UUID.randomUUID()))
                .toList();
        var executor = Executors.newFixedThreadPool(16);
        CompletableFuture.allOf(sessions.stream()
                        .map(session -> CompletableFuture.runAsync(
                                () -> registry.bind(session.id(), new StubTransport(), 64, 1), executor))
                        .toArray(CompletableFuture[]::new))
                .join();
        executor.shutdownNow();

        assertThat(registry.activeConnectionCount()).isEqualTo(64);
        assertThat(registry.activeUserCount()).isEqualTo(64);
        ClientSession overflow = add(registry, UUID.randomUUID());
        assertThatThrownBy(() -> registry.bind(overflow.id(), new StubTransport(), 64, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64");
        registry.close();
    }

    @Test
    void enforcesGlobalAndPerUserActiveConnectionCapacity() {
        ClientRuntimeRegistry registry = new ClientRuntimeRegistry();
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        ClientSession first = add(registry, firstOwner);
        ClientSession duplicate = add(registry, firstOwner);
        ClientSession second = add(registry, secondOwner);
        ClientSession overflow = add(registry, UUID.randomUUID());

        registry.bind(first.id(), new StubTransport(), 2, 1);
        assertThatThrownBy(() -> registry.bind(duplicate.id(), new StubTransport(), 2, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("活跃游戏连接");
        registry.bind(second.id(), new StubTransport(), 2, 1);
        assertThatThrownBy(() -> registry.bind(overflow.id(), new StubTransport(), 2, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("上限");

        assertThat(registry.activeConnectionCount()).isEqualTo(2);
        assertThat(registry.activeUserCount()).isEqualTo(2);
        registry.close();
    }

    @Test
    void reclaimsDeadLotusConnectionBeforeEnforcingOneSessionLimit() {
        ClientRuntimeRegistry registry = new ClientRuntimeRegistry();
        UUID owner = UUID.randomUUID();
        ClientSession first = add(registry, owner);
        ClientSession second = add(registry, owner);
        StubTransport disconnected = new StubTransport();
        registry.bind(first.id(), disconnected, 64, 1);

        disconnected.disconnectUnexpectedly();
        registry.assertCapacityForSession(second.id(), 64, 1);
        registry.bind(second.id(), new StubTransport(), 64, 1);

        assertThat(registry.activeConnectionCount()).isEqualTo(1);
        assertThat(registry.activeUserCount()).isEqualTo(1);
        registry.close();
    }

    @Test
    void sameSessionCanReloginWithoutConsumingAnotherConnectionSlot() {
        ClientRuntimeRegistry registry = new ClientRuntimeRegistry();
        ClientSession session = add(registry, UUID.randomUUID());
        StubTransport previous = new StubTransport();
        StubTransport replacement = new StubTransport();
        registry.bind(session.id(), previous, 64, 1);

        registry.assertCapacityForSession(session.id(), 64, 1);
        registry.bind(session.id(), replacement, 64, 1);

        assertThat(previous.connected()).isFalse();
        assertThat(registry.isCurrent(session.id(), replacement)).isTrue();
        assertThat(registry.activeConnectionCount()).isEqualTo(1);
        registry.close();
    }

    @Test
    void delayedOldDisconnectCannotUnbindTheReplacementConnection() {
        ClientRuntimeRegistry registry = new ClientRuntimeRegistry();
        ClientSession session = add(registry, UUID.randomUUID());
        StubTransport previous = new StubTransport();
        StubTransport replacement = new StubTransport();
        registry.bind(session.id(), previous, 64, 1);
        registry.bind(session.id(), replacement, 64, 1);

        assertThat(registry.releaseIfCurrent(session.id(), previous)).isFalse();
        assertThat(registry.isCurrent(session.id(), replacement)).isTrue();
        assertThat(registry.releaseIfCurrent(session.id(), replacement)).isTrue();
        assertThat(registry.activeConnectionCount()).isZero();
        registry.close();
    }

    private static ClientSession add(ClientRuntimeRegistry registry, UUID ownerId) {
        ClientSession session = new ClientSession(UUID.randomUUID(), ownerId);
        registry.add(session);
        return session;
    }

    private static final class StubTransport implements GameTransportPort {
        private boolean live = true;

        void disconnectUnexpectedly() {
            live = false;
        }
        @Override
        public void connect(GameCredential credential, Consumer<ProtocolResponse> eventSink) {
        }

        @Override
        public CompletableFuture<ProtocolResponse> request(ProtocolRequest request, Duration timeout) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<GrpcResponse> grpc(GrpcRequest request, Duration timeout) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public void send(ProtocolRequest request) {
        }

        @Override
        public boolean connected() {
            return live;
        }

        @Override
        public void connectGameServer(List<GameServerEndpoint> endpoints) {
        }

        @Override
        public CompletableFuture<ProtocolResponse> gameRequest(ProtocolRequest request, Duration timeout) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public void gameSend(ProtocolRequest request) {
        }

        @Override
        public boolean gameConnected() {
            return false;
        }

        @Override
        public void close() {
            live = false;
        }
    }
}
