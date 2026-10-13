package cc.superbaby.happy4j.application;

import cc.superbaby.happy4j.application.port.GameLoginPort;
import cc.superbaby.happy4j.application.port.GameModeCatalogPort;
import cc.superbaby.happy4j.application.port.GameTransportFactoryPort;
import cc.superbaby.happy4j.application.port.GameTransportPort;
import cc.superbaby.happy4j.application.port.GameTransportPort.ProtocolRequest;
import cc.superbaby.happy4j.application.port.GameTransportPort.ProtocolResponse;
import cc.superbaby.happy4j.application.port.GameTransportPort.GameServerEndpoint;
import cc.superbaby.happy4j.application.port.QrAuthPort;
import cc.superbaby.happy4j.config.Happy4jProperties;
import cc.superbaby.happy4j.domain.ActivityView;
import cc.superbaby.happy4j.domain.Card;
import cc.superbaby.happy4j.domain.ClientPhase;
import cc.superbaby.happy4j.domain.ClientSession;
import cc.superbaby.happy4j.domain.GameStage;
import cc.superbaby.happy4j.domain.GameCredential;
import cc.superbaby.happy4j.domain.GameMode;
import cc.superbaby.happy4j.domain.InventoryItem;
import cc.superbaby.happy4j.domain.MissionTask;
import cc.superbaby.happy4j.domain.PlayerProfile;
import cc.superbaby.happy4j.domain.TableAction;
import cc.superbaby.happy4j.domain.TableSnapshot;
import cc.superbaby.happy4j.domain.WalletBalance;
import cc.superbaby.happy4j.protocol.game.MiniGameRoomProtocol;
import cc.superbaby.happy4j.protocol.game.MiniGameActionProtocol;
import cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec;
import cc.superbaby.happy4j.protocol.game.GameSoType;
import cc.superbaby.happy4j.protocol.game.GameMessage;
import cc.superbaby.happy4j.protocol.game.RoomEventType;
import cc.superbaby.happy4j.protocol.wire.FunctionalEntity;
import cc.superbaby.happy4j.infrastructure.transport.GameServerDirectory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Application façade. Controllers never know QR/OAuth, SafeLogin, protobuf, framing or sockets.
 */
@Service
public final class HappyDdzClientService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HappyDdzClientService.class);
    private final Map<String, QrAuthPort> qrAuth;
    private final GameLoginPort gameLogin;
    private final GameTransportFactoryPort transports;
    private final GameModeCatalogPort modes;
    private final HallApplicationService hall;
    private final GameSessionPersistenceService persistence;
    private final Happy4jProperties.Game config;
    private final ObjectMapper json;
    private final MiniGameGameSoCodec gameSoCodec = new MiniGameGameSoCodec();
    private final MiniGameActionProtocol actionProtocol = new MiniGameActionProtocol();
    private final DdzTableStateMachine tableState = new DdzTableStateMachine();
    private final ExecutorService workers;
    private final ClientRuntimeRegistry runtime;
    private final TableActionCoordinator tableActions;
    private final CredentialVaultService credentialVault;
    private final ServerConfigService serverConfig;
    private final java.util.Set<UUID> walletRefreshPending = ConcurrentHashMap.newKeySet();

    public HappyDdzClientService(List<QrAuthPort> qrProviders, GameLoginPort gameLogin,
                                 GameTransportFactoryPort transports, GameModeCatalogPort modes,
                                 HallApplicationService hall,
                                 GameSessionPersistenceService persistence,
                                 TableActionCoordinator tableActions,
                                 ClientRuntimeRegistry runtime,
                                 CredentialVaultService credentialVault,
                                 ServerConfigService serverConfig,
                                 Happy4jProperties properties, ObjectMapper json) {
        this.qrAuth = qrProviders.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                provider -> provider.provider().toLowerCase(java.util.Locale.ROOT), provider -> provider));
        this.gameLogin = gameLogin;
        this.transports = transports;
        this.modes = modes;
        this.hall = hall;
        this.persistence = persistence;
        this.tableActions = tableActions;
        this.runtime = runtime;
        this.credentialVault = credentialVault;
        this.serverConfig = serverConfig;
        this.workers = workerPool(
                serverConfig.integer(ServerConfigKeys.RUNTIME_WORKER_THREADS, 32),
                serverConfig.integer(ServerConfigKeys.RUNTIME_WORKER_QUEUE_CAPACITY, 512));
        this.config = properties.game();
        this.json = json;
    }

    public ClientSession.Snapshot createSession(UUID ownerId) {
        ClientSession session = new ClientSession(UUID.randomUUID(), ownerId);
        runtime.add(session);
        persistence.attach(session);
        return session.snapshot();
    }

    public ClientSession session(UUID id) {
        return runtime.get(id);
    }

    public ClientSession ownedSession(UUID ownerId, UUID id) {
        return runtime.owned(ownerId, id);
    }

    public List<ClientSession.Snapshot> userSessions(UUID ownerId) {
        return runtime.snapshots(ownerId);
    }

    public void assertOwner(UUID ownerId, UUID sessionId) {
        ownedSession(ownerId, sessionId);
    }

    public RuntimeCapacity runtimeCapacity() {
        return new RuntimeCapacity(
                serverConfig.integer(ServerConfigKeys.RUNTIME_MAX_ACTIVE_USERS, 64),
                serverConfig.integer(ServerConfigKeys.RUNTIME_MAX_ACTIVE_SESSIONS_PER_USER, 1),
                runtime.activeUserCount(),
                runtime.activeConnectionCount(),
                serverConfig.integer(ServerConfigKeys.RUNTIME_WORKER_THREADS, 32),
                serverConfig.integer(ServerConfigKeys.RUNTIME_WORKER_QUEUE_CAPACITY, 512),
                serverConfig.integer(ServerConfigKeys.RUNTIME_QR_POLL_THREADS, 8));
    }

    public void closeSession(UUID ownerId, UUID sessionId) {
        ClientSession session = ownedSession(ownerId, sessionId);
        tableActions.clear(sessionId);
        session.transition(ClientPhase.DISCONNECTED, "用户已关闭会话");
        runtime.remove(sessionId);
    }

    public QrAuthPort.QrChallenge startQr(UUID id, String providerName) {
        ClientSession session = session(id);
        QrAuthPort provider = provider(providerName);
        QrAuthPort.QrChallenge challenge = provider.start();
        session.qr(provider.provider(), challenge.id());
        session.transition(ClientPhase.QR_READY, (provider.provider().equals("wechat") ? "微信" : "QQ") + "二维码已生成");
        return challenge;
    }

    public byte[] qrImage(UUID id) {
        ClientSession session = session(id);
        return provider(session.qrProvider()).image(requiredQr(session));
    }

    public QrAuthPort.QrPollResult pollQr(UUID id) {
        ClientSession session = session(id);
        QrAuthPort.QrPollResult result = provider(session.qrProvider()).poll(requiredQr(session));
        if (result.status() == QrAuthPort.QrPollResult.Status.SCANNED) {
            session.transition(ClientPhase.QR_SCANNED, result.message());
        } else if (result.status() == QrAuthPort.QrPollResult.Status.EXPIRED) {
            session.transition(ClientPhase.FAILED, result.message());
        } else if (result.status() == QrAuthPort.QrPollResult.Status.CONFIRMED) {
            session.platformCredential(result.credential());
            session.transition(ClientPhase.PLATFORM_AUTHENTICATED, result.message());
            workers.submit(() -> openHall(session));
        }
        return result;
    }

    private void openHall(ClientSession session) {
        try {
            session.transition(ClientPhase.GAME_AUTHENTICATING, "正在执行 TSDK SafeLogin");
            GameCredential credential = gameLogin.login(session.platformCredential());
            credentialVault.store(session.ownerId(), credential);
            connectHall(session, credential, "已连接欢乐斗地主 Lotus 大厅");
        } catch (Exception failure) {
            session.transition(ClientPhase.FAILED, rootMessage(failure));
        }
    }

    public CompletableFuture<ClientSession.Snapshot> resumeSavedLogin(UUID ownerId) {
        return CompletableFuture.supplyAsync(() -> {
            if (!serverConfig.bool(ServerConfigKeys.SESSION_RESTORE_ENABLED, true)) {
                throw new IllegalStateException("服务器已关闭游戏登录恢复");
            }
            GameCredential credential = credentialVault.restore(ownerId)
                    .orElseThrow(() -> new IllegalStateException("没有可恢复的游戏登录状态"));
            ClientSession.Snapshot created = createSession(ownerId);
            ClientSession session = runtime.get(created.id());
            try {
                connectHall(session, credential, "已从数据库恢复游戏登录状态");
                return session.snapshot();
            } catch (RuntimeException failure) {
                session.transition(ClientPhase.FAILED, rootMessage(failure));
                throw failure;
            }
        }, workers);
    }

    private void connectHall(ClientSession session, GameCredential credential, String detail) {
        session.gameCredential(credential);
        int maxActiveUsers = serverConfig.integer(ServerConfigKeys.RUNTIME_MAX_ACTIVE_USERS, 64);
        int maxSessionsPerUser = serverConfig.integer(
                ServerConfigKeys.RUNTIME_MAX_ACTIVE_SESSIONS_PER_USER, 1);
        runtime.assertCapacityForSession(session.id(), maxActiveUsers, maxSessionsPerUser);
        GameTransportPort connection = transports.create();
        try {
            connection.connect(credential, event -> onRemoteEvent(session, connection, event));
            hall.loginMissionSystem(connection, credential);
            runtime.bind(session.id(), connection, maxActiveUsers, maxSessionsPerUser);
        } catch (RuntimeException failure) {
            connection.close();
            throw failure;
        }
        session.transition(ClientPhase.HALL_CONNECTED, detail);
        hall.refreshWallet(session.id()).exceptionally(failure -> {
            session.publish("WALLET", "余额自动刷新失败: " + rootMessage(failure), null);
            return null;
        });
        hall.refreshActivities(session.id()).exceptionally(failure -> {
            session.publish("ACTIVITY", "活动自动刷新失败: " + rootMessage(failure), null);
            return null;
        });
        hall.refreshProfile(session.id()).exceptionally(failure -> {
            session.publish("PROFILE", "玩家资料自动刷新失败: " + rootMessage(failure), null);
            return null;
        });
        hall.refreshMissions(session.id()).exceptionally(failure -> {
            session.publish("MISSIONS", "任务自动刷新失败: " + rootMessage(failure), null);
            return null;
        });
        hall.refreshInventory(session.id()).exceptionally(failure -> {
            session.publish("INVENTORY", "道具自动刷新失败: " + rootMessage(failure), null);
            return null;
        });
    }

    public List<GameMode> gameModes() {
        return modes.modes();
    }

    public CompletableFuture<WalletBalance> refreshWallet(UUID id) {
        return hall.refreshWallet(id);
    }

    public CompletableFuture<PlayerProfile> refreshProfile(UUID id) {
        return hall.refreshProfile(id);
    }

    public CompletableFuture<List<MissionTask>> refreshMissions(UUID id) {
        return hall.refreshMissions(id);
    }

    public CompletableFuture<List<InventoryItem>> refreshInventory(UUID id) {
        return hall.refreshInventory(id);
    }

    public CompletableFuture<ActivityView> claimMission(UUID id, int missionId) {
        return hall.claimMission(id, missionId);
    }

    public CompletableFuture<List<ActivityView>> refreshActivities(UUID id) {
        return hall.refreshActivities(id);
    }

    public CompletableFuture<ActivityView> claimSignIn(UUID id, int command, int prizeIndex) {
        return hall.claimSignIn(id, command, prizeIndex);
    }

    public CompletableFuture<ActivityView> openTreasure(UUID id, int itemId, int itemSeq) {
        return hall.openTreasure(id, itemId, itemSeq);
    }

    public CompletableFuture<TableSnapshot> enterMode(UUID id, int sceneId) {
        ClientSession session = requireHall(id);
        GameMode mode = modes.modes().stream().filter(item -> item.sceneId() == sceneId && item.enabled())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("场次未开启: " + sceneId));
        session.transition(ClientPhase.ENTERING_ROOM, "正在进入" + mode.name());
        Map<String, Object> requestBody = Map.of(
                "uin", session.gameCredential().uin(),
                "openId", session.gameCredential().openId(),
                "transplatomLength", 0,
                "searchInfo", Map.of(
                        "clientVersion", config.clientVersion(), "clientType", config.clientType(),
                        "appId", 0, "gameId", config.id(), "miniOnlinePercent", 0, "maxOnlinePercent", 100,
                        "searchType", 3, "searchConditionLen", 1,
                        "searchTypeDefinedCondition", Map.of("searchCondition", Map.of(
                                "sceneId", sceneId, "expectAppId", 0, "svrId", 0, "roomId", 0))),
                "len", 0);
        ProtocolRequest quick = request(6100, FunctionalEntity.QUICK_GAME_SERVER,
                "Tdrpb.AppQuickGameSvr.CReqRecommandRoom", "Tdrpb.AppQuickGameSvr.CRspRecommandRoom", requestBody);
        return runtime.connection(id).request(quick, config.requestTimeout().multipliedBy(3)).thenCompose(response -> {
            try {
                JsonNode quickBody = json.readTree(response.json());
                JsonNode result = quickBody.path("searchResultInfo");
                int code = result.path("resultId").asInt();
                boolean locked = quickBody.path("isLocked").asInt() != 0;
                int selectedServerId = result.path("svrId").asInt();
                int selectedRoomId = result.path("roomId").asInt();
                int lockServerId = quickBody.path("lockSvrId").asInt(-1);
                int lockRoomId = quickBody.path("lockRoomId").asInt(-1);
                int serverId = locked && lockServerId > 0 ? lockServerId : selectedServerId;
                int roomId = locked && lockRoomId >= 0 ? lockRoomId : selectedRoomId;
                int effectiveSceneId = result.path("sceneId").asInt(sceneId);
                if (effectiveSceneId <= 0) effectiveSceneId = sceneId;
                EntryRoute route = new EntryRoute(effectiveSceneId, serverId, roomId, locked);
                LOG.info("QuickGame result scene={} code={} server={} room={} locked={} lockServer={} lockRoom={}",
                        sceneId, code, selectedServerId, selectedRoomId, locked, lockServerId, lockRoomId);
                if (code != 0) throw new IllegalStateException("场次 " + sceneId
                        + " 当前未开放，QuickGame 返回 " + code);
                TableSnapshot locating = new TableSnapshot(route.sceneId(), route.roomId(), 0, -1, -1, -1,
                        List.of(), List.of(), List.of(), List.of(), GameStage.LOCATING, List.of(), 1, route.locked()
                        ? "检测到进行中的对局，正在返回" : "已定位服务器 " + route.serverId() + "，正在登录房间");
                session.table(locating);
                if (route.serverId() <= 0)
                    throw new IllegalStateException("快速入场未返回有效游戏服务器");
                GameTransportPort connection = runtime.connection(id);
                return resolveGameServers(connection, route.serverId()).thenCompose(endpoints -> {
                    connection.connectGameServer(endpoints);
                    GameCredential credential = session.gameCredential();
                    byte[] openId = credential.openId().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    byte[] nickText = rawText(credential, "nick", "nickname", "strNick")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    byte[] nick = java.util.Arrays.copyOf(nickText, nickText.length + 1); // native includes NUL
                    byte[] headUrl = rawText(credential, "headurl", "head_url", "strHeadUrl")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    Map<String, Object> clientInfo = Map.ofEntries(
                            Map.entry("NickLen", nick.length), Map.entry("Nick", nick),
                            Map.entry("Age", rawInt(credential, "age", "nAge")),
                            Map.entry("Gender", rawInt(credential, "gender", "sex", "nGender")),
                            Map.entry("AccountType", credential.accountType()),
                            Map.entry("ClientType", config.clientType()),
                            Map.entry("PFKey", 0), Map.entry("Network", 1), Map.entry("Operator", 0),
                            Map.entry("Channel", config.channelId()),
                            Map.entry("ClientVersion", config.clientVersion()),
                            Map.entry("OpenIDLen", openId.length), Map.entry("OpenID", openId),
                            Map.entry("HeadUrlLen", headUrl.length), Map.entry("HeadUrl", headUrl));
                    Map<String, Object> loginBody = Map.of("Uin", credential.uin(), "RequestSrc", 0,
                            "LoginLifeStyle", 0, "RoomID", route.roomId(), "ClientInfo", clientInfo, "TLVNum", 0);
                    ProtocolRequest login = request(GameMessage.LOGIN.id(), FunctionalEntity.SERVER,
                            "Native.HuanLeGameSvr.CRequestLoginHGPara", "Native.HuanLeGameSvr.CResponseLoginHGPara", loginBody);
                    return connection.gameRequest(login, config.requestTimeout().multipliedBy(3))
                            .thenApply(value -> new GameLoginResult(route, value));
                });
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }).thenCompose(loginResult -> {
            try {
                JsonNode body = json.readTree(loginResult.response().json());
                checkResult(body, "登录房间");
                int roomId = body.path("RoomID").asInt(session.table().roomId());
                List<MiniGameGameSoCodec.PlayerDetail> loginPlayers = playerDetails(body);
                long localUin = session.gameCredential() == null ? 0 : session.gameCredential().uin();
                MiniGameGameSoCodec.PlayerDetail loginPlayer = loginPlayers.stream()
                        .filter(player -> player.uin() == localUin).findFirst().orElse(null);
                if (lockedRouteNeedsRecovery(loginResult.route().locked(), loginPlayer)) {
                    return recoverOngoingGame(session, runtime.connection(id), loginResult.route().sceneId(), roomId,
                            body);
                }
                // wxd0e404d795ea6f80_unpacked/src/GameBase/game.js calls
                // qAa(3, roomId, -1, -1, []) for an ordinary quick-match sit-down.  The previous
                // implementation appended Android/Unity-only 20012/20017 values by embedding
                // protobuf-union bytes inside a native TDR TTLVUNIT.  GameSvr kept answering
                // 15812 heartbeats but silently discarded that 15803, hence the local timeout.
                ProtocolRequest sit = request(GameMessage.SIT_DOWN.id(), FunctionalEntity.SERVER,
                        "Native.HuanLeGameSvr.CRequestSitDownHGPara", "Native.HuanLeGameSvr.CResponseSitDownHGPara",
                        MiniGameRoomProtocol.quickMatchSitDown(roomId));
                return runtime.connection(id).gameRequest(sit, config.requestTimeout().multipliedBy(3))
                        .thenCompose(value -> {
                            try {
                                JsonNode sitBody = json.readTree(value.json());
                                if (sitBody.path("ResultID").asInt() == 11007) {
                                    return recoverOngoingGame(session, runtime.connection(id),
                                            loginResult.route().sceneId(), roomId, body);
                                }
                                checkResult(sitBody, "坐下匹配");
                                int seatId = sitBody.path("SeatID").asInt();
                                List<MiniGameGameSoCodec.PlayerDetail> details = Stream
                                        .concat(loginPlayers.stream(), playerDetails(sitBody).stream()).toList();
                                List<TableSnapshot.Player> players = ensureLocalProfile(session,
                                        mergePlayerDetails(session.table().players(), details), seatId);
                                TableSnapshot table = new TableSnapshot(loginResult.route().sceneId(),
                                        sitBody.path("RoomID").asInt(), sitBody.path("TableID").asInt(),
                                        seatId, -1, -1,
                                        List.of(), List.of(), List.of(), players, GameStage.SEATED,
                                        List.of(), 1,
                                        "已坐下，等待发牌");
                                session.table(table);
                                session.transition(ClientPhase.READY, "已坐下，等待发牌");
                                return CompletableFuture.completedFuture(table);
                            } catch (Exception failure) {
                                return CompletableFuture.failedFuture(new IllegalStateException(failure));
                            }
                        });
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }).whenComplete((value, failure) -> {
            if (failure != null) {
                String message = rootMessage(failure);
                TableSnapshot current = session.table();
                if (current != null)
                    session.table(new TableSnapshot(current.sceneId(), current.roomId(),
                            current.tableId(), current.seatId(), current.turnSeatId(), current.landlordSeatId(),
                            current.hand(), current.lastPlay(), current.bottomCards(), current.players(),
                            current.stage(), current.availableActions(), current.multiplier(), "进入失败：" + message));
                session.transition(ClientPhase.FAILED, message);
            }
        });
    }

    private record EntryRoute(int sceneId, int serverId, int roomId, boolean locked) {
    }

    private record GameLoginResult(EntryRoute route, ProtocolResponse response) {
    }

    static boolean lockedRouteNeedsRecovery(boolean locked, MiniGameGameSoCodec.PlayerDetail player) {
        // The original room model sets state 2 after a successful 15804 stand-up. QuickGame can
        // briefly retain the previous lock, but that standing player must sit down again rather
        // than being presented as a completed round. States 3+ still belong to a table flow.
        return locked && (player == null || player.state() >= 3);
    }

    private CompletableFuture<TableSnapshot> recoverOngoingGame(ClientSession session,
                                                                GameTransportPort connection,
                                                                int sceneId, int roomId,
                                                                JsonNode loginBody) {
        MiniGameGameSoCodec.PlayerDetail loginPlayer = findLoginPlayer(loginBody,
                session.gameCredential() == null ? 0 : session.gameCredential().uin());
        if (loginPlayer != null) {
            LOG.info("GameSvr login player state={} table={} seat={} uin={}", loginPlayer.state(),
                    loginPlayer.tableId(), loginPlayer.seatId(), loginPlayer.uin());
            session.publish("GAME_RECOVERY", "已读取登录玩家状态",
                    Map.of("playerState", loginPlayer.state(), "seatId", loginPlayer.seatId(),
                            "tableId", loginPlayer.tableId()));
        }
        int loginSeat = loginPlayer == null ? -1 : loginPlayer.seatId();
        List<TableSnapshot.Player> loginProfiles = ensureLocalProfile(session,
                mergePlayerDetails(session.table().players(), playerDetails(loginBody)), loginSeat);
        TableSnapshot waiting = new TableSnapshot(sceneId, roomId, 0, loginSeat, -1, -1,
                List.of(), List.of(), List.of(), loginProfiles, GameStage.RECOVERING, List.of(), 1,
                "已登录进行中的房间，正在请求 15806 断线重回数据");
        session.table(waiting);
        session.transition(ClientPhase.READY, "正在执行 JS Qqa(InfoType=2) 断线重回流程");
        ProtocolRequest replay = request(GameMessage.QUERY_REPLAY.id(), FunctionalEntity.SERVER,
                "Native.HuanLeGameSvr.CRequestSvrInfoHGPara",
                "Native.HuanLeGameSvr.CResponseSvrInfoHGPara", Map.of("InfoType", 2));
        return connection.gameRequest(replay, config.requestTimeout().multipliedBy(3)).thenApply(response -> {
            try {
                JsonNode body = json.readTree(response.json());
                checkResult(body, "查询断线重回数据");
                LOG.info("GameSvr replay query response={}", body);
                session.publish("GAME_RECOVERY", "15806 断线重回查询完成", body);
                int replayEvents = 0;
                JsonNode tlvs = body.path("TLVInfo");
                if (tlvs.isArray()) for (JsonNode tlv : tlvs) {
                    if (tlv.path("Type").asInt() != 10014) continue;
                    try {
                        MiniGameGameSoCodec.GameSoEvent event = gameSoCodec.decodeReplayTlv(
                                Base64.getDecoder().decode(tlv.path("Value").asString()));
                        if (event.error() == 0) {
                            session.updateTable(table -> tableState.applyGameSo(table, event));
                            replayEvents++;
                            session.publish("GAME_RECOVERY", "已应用回放 subtype=" + event.subtype(),
                                    Map.of("subtype", event.subtype(), "seatId", event.seatId(),
                                            "cards", event.cards(), "hands", event.hands()));
                        }
                    } catch (RuntimeException decodeFailure) {
                        session.publish("GAME_DECODE", "回放 TLV 10014 解码失败: "
                                + rootMessage(decodeFailure), null);
                    }
                }
                int applied = replayEvents;
                TableSnapshot current = session.updateTable(table -> applied == 0
                        ? withState(table, table.hand(), table.lastPlay(), table.bottomCards(), table.stage(),
                        table.availableActions(), table.turnSeatId(), table.landlordSeatId(), table.multiplier(),
                        "断线重回查询成功，正在等待服务器牌局快照") : table);
                CompletableFuture.delayedExecutor(3, java.util.concurrent.TimeUnit.SECONDS, workers).execute(() ->
                        session.updateTable(table -> table.hand().isEmpty() && table.stage() == GameStage.RECOVERING
                                ? withState(table, table.hand(), table.lastPlay(), table.bottomCards(),
                                GameStage.TIMED_OUT, List.of(TableAction.CHANGE_TABLE, TableAction.LEAVE_ROOM),
                                table.turnSeatId(), table.landlordSeatId(), table.multiplier(),
                                "服务器未返回可恢复牌局，可换桌或退出房间") : table));
                return current;
            } catch (Exception failure) {
                throw new IllegalStateException("恢复进行中牌局失败: " + rootMessage(failure), failure);
            }
        });
    }

    private MiniGameGameSoCodec.PlayerDetail findLoginPlayer(JsonNode loginBody, long localUin) {
        return playerDetails(loginBody).stream().filter(detail -> detail.uin() == localUin)
                .findFirst().orElse(null);
    }

    private List<MiniGameGameSoCodec.PlayerDetail> playerDetails(JsonNode root) {
        List<MiniGameGameSoCodec.PlayerDetail> players = new java.util.ArrayList<>();
        JsonNode tlvs = root.path("TLVInfo");
        if (!tlvs.isArray()) return List.of();
        for (JsonNode tlv : tlvs) {
            if (tlv.path("Type").asInt() != 10001) continue;
            try {
                players.add(gameSoCodec.decodePlayerDetailTlv(
                        Base64.getDecoder().decode(tlv.path("Value").asString())));
            } catch (RuntimeException failure) {
                LOG.warn("GameSvr player TLV decode failed: {}", rootMessage(failure));
            }
        }
        return List.copyOf(players);
    }

    static List<TableSnapshot.Player> mergePlayerDetails(List<TableSnapshot.Player> existing,
                                                          List<MiniGameGameSoCodec.PlayerDetail> details) {
        Map<Integer, TableSnapshot.Player> players = new LinkedHashMap<>();
        existing.forEach(player -> players.put(player.seatId(), player));
        for (MiniGameGameSoCodec.PlayerDetail detail : details) {
            if (detail.seatId() < 0) continue;
            TableSnapshot.Player previous = players.get(detail.seatId());
            if (previous == null && detail.uin() != 0)
                previous = players.values().stream().filter(player -> player.uin() == detail.uin())
                        .findFirst().orElse(null);
            String nickname = detail.nickname() == null || detail.nickname().isBlank()
                    ? previous == null ? "玩家 " + (detail.seatId() + 1) : previous.nickname()
                    : detail.nickname();
            String avatar = detail.avatarUrl() == null || detail.avatarUrl().isBlank()
                    ? previous == null ? "" : previous.avatarUrl() : detail.avatarUrl();
            long uin = detail.uin() != 0 ? detail.uin() : previous == null ? 0 : previous.uin();
            long beans = detail.beans() > 0 || previous == null ? detail.beans() : previous.beans();
            int cardCount = previous == null ? 0 : previous.cardCount();
            players.values().removeIf(player -> detail.uin() != 0 && player.uin() == detail.uin()
                    && player.seatId() != detail.seatId());
            players.put(detail.seatId(), new TableSnapshot.Player(uin, nickname, detail.seatId(),
                    cardCount, beans, detail.state() >= 5, avatar,
                    previous == null ? 0 : previous.lastBeanChange()));
        }
        return players.values().stream().sorted(java.util.Comparator.comparingInt(TableSnapshot.Player::seatId))
                .toList();
    }

    private static List<TableSnapshot.Player> ensureLocalProfile(ClientSession session,
                                                                  List<TableSnapshot.Player> players,
                                                                  int seatId) {
        if (seatId < 0 || session.gameCredential() == null) return players;
        List<TableSnapshot.Player> result = new java.util.ArrayList<>(players);
        TableSnapshot.Player previous = result.stream().filter(player -> player.seatId() == seatId)
                .findFirst().orElse(null);
        result.removeIf(player -> player.seatId() == seatId);
        String nickname = previous != null && !previous.nickname().startsWith("玩家 ")
                ? previous.nickname() : session.profile().nickname();
        if (nickname == null || nickname.isBlank()) nickname = "我";
        String avatar = previous != null && !previous.avatarUrl().isBlank()
                ? previous.avatarUrl() : session.profile().avatarUrl();
        long beans = previous != null && previous.beans() > 0 ? previous.beans() : session.wallet().beans();
        result.add(new TableSnapshot.Player(session.gameCredential().uin(), nickname, seatId,
                previous == null ? 0 : previous.cardCount(), beans,
                previous != null && previous.ready(), avatar,
                previous == null ? 0 : previous.lastBeanChange()));
        return result.stream().sorted(java.util.Comparator.comparingInt(TableSnapshot.Player::seatId)).toList();
    }

    public CompletableFuture<ProtocolResponse> play(UUID id, List<Integer> cards, boolean pass, String payloadBase64) {
        ClientSession session = session(id);
        if (session.phase() != ClientPhase.PLAYING && session.phase() != ClientPhase.READY) {
            throw new IllegalStateException("当前牌桌状态不可出牌: " + session.phase());
        }
        List<Integer> selected = pass || cards == null ? List.of() : List.copyOf(cards);
        if (!pass) {
            ensureCardsInHand(session.table(), selected);
            ensureCanBeat(session.table(), actionProtocol, actionProtocol.classifyCards(selected), selected.size());
        }
        byte[] gamePayload = payloadBase64 == null || payloadBase64.isBlank()
                ? actionProtocol.give(selected, pass)
                : Base64.getDecoder().decode(payloadBase64);
        TableAction action = pass ? TableAction.PASS : TableAction.PLAY;
        TableActionCoordinator.PendingAction pending = tableActions.claim(session, action, pass ? "不出" : "出牌");
        return sendGameAction(session, gamePayload, pass ? "不出" : "出牌",
                Map.of("cards", selected), pending);
    }

    public CompletableFuture<ProtocolResponse> ready(UUID id) {
        ClientSession session = session(id);
        TableSnapshot table = requireTable(session);
        requireAction(table, TableAction.READY);
        GameTransportPort connection = requireGameConnection(id);
        Map<String, Object> body = Map.of("ReadyMode", 0, "RoomID", table.roomId(),
                "TableID", table.tableId(), "TLVNum", 0, "TLVInfo", List.of());
        ProtocolRequest request = request(GameMessage.READY.id(), FunctionalEntity.SERVER,
                "Native.HuanLeGameSvr.CRequestReadyHGPara", "Native.HuanLeGameSvr.CResponseReadyHGPara", body);
        session.updateTable(current -> {
            requireAction(current, TableAction.READY);
            tableActions.clear(session.id());
            return new TableSnapshot(current.sceneId(), current.roomId(), current.tableId(), current.seatId(),
                    -1, -1, List.of(), List.of(), List.of(), List.of(), GameStage.READY, List.of(), 1,
                    "准备提交中…");
        });
        return connection.gameRequest(request, config.requestTimeout()).thenApply(response -> {
            checkGameActionResponse(response, "准备");
            session.updateTable(current -> withState(current, current.hand(), current.lastPlay(),
                    current.bottomCards(), GameStage.READY, List.of(), current.turnSeatId(),
                    current.landlordSeatId(), current.multiplier(), "已准备，等待服务器发牌"));
            session.publish("PLAY", "准备", Map.of());
            return response;
        }).whenComplete((response, failure) -> {
            if (failure != null) session.updateTable(current -> withState(current, current.hand(),
                    current.lastPlay(), current.bottomCards(), table.stage(), table.availableActions(),
                    current.turnSeatId(), current.landlordSeatId(), current.multiplier(),
                    "准备失败: " + rootMessage(failure)));
        });
    }

    public CompletableFuture<ProtocolResponse> order(UUID id, boolean accept) {
        ClientSession session = session(id);
        TableSnapshot table = requireTable(session);
        boolean rob = table.availableActions().contains(TableAction.ROB_LANDLORD);
        TableAction action = accept
                ? (rob ? TableAction.ROB_LANDLORD : TableAction.CALL_LANDLORD)
                : (rob ? TableAction.PASS_ROB : TableAction.PASS_LANDLORD);
        TableActionCoordinator.PendingAction pending = tableActions.claim(session, action,
                accept ? (rob ? "抢地主" : "叫地主") : (rob ? "不抢" : "不叫"));
        return sendGameAction(session, actionProtocol.order(accept ? 1 : 0),
                accept ? (rob ? "抢地主" : "叫地主") : (rob ? "不抢" : "不叫"),
                Map.of("accept", accept), pending);
    }

    public CompletableFuture<ProtocolResponse> multiple(UUID id, boolean accept) {
        ClientSession session = session(id);
        TableSnapshot table = requireTable(session);
        TableAction action = accept ? TableAction.DOUBLE : TableAction.NO_DOUBLE;
        TableActionCoordinator.PendingAction pending = tableActions.claim(session, action, accept ? "加倍" : "不加倍");
        // GameBase P1b: default MultiCalcMode=0 uses multiple(1,1) for no-double;
        // ordinary double is multiple(2,1).
        return sendGameAction(session, actionProtocol.multiple(accept ? 2 : 1, 1),
                accept ? "加倍" : "不加倍", Map.of("accept", accept), pending);
    }

    public CompletableFuture<ProtocolResponse> superMultiple(UUID id) {
        ClientSession session = session(id);
        TableSnapshot table = requireTable(session);
        requireAction(table, TableAction.SUPER_DOUBLE);
        int itemId = table.superDoubleItemId() > 0
                ? table.superDoubleItemId() : HallApplicationService.SUPER_DOUBLE_ITEM_ID;
        requireSuperDoubleCard(session.itemCount(itemId));
        TableActionCoordinator.PendingAction pending = tableActions.claim(session, TableAction.SUPER_DOUBLE, "超级加倍");
        CompletableFuture<ProtocolResponse> operation = sendGameAction(session,
                actionProtocol.multiple(4, 2), "超级加倍", Map.of("itemId", itemId), pending);
        operation.thenRun(() -> CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS, workers)
                .execute(() -> hall.refreshInventory(id).exceptionally(failure -> null)));
        return operation;
    }

    static void requireSuperDoubleCard(long count) {
        if (count <= 0) throw new IllegalStateException("超级加倍卡数量不足");
    }

    public CompletableFuture<ProtocolResponse> trust(UUID id, boolean enabled) {
        ClientSession session = session(id);
        TableAction action = enabled ? TableAction.TRUST : TableAction.CANCEL_TRUST;
        TableActionCoordinator.PendingAction pending = tableActions.claim(session, action, enabled ? "托管" : "取消托管");
        return sendGameAction(session, actionProtocol.trust(enabled), enabled ? "托管" : "取消托管",
                Map.of("enabled", enabled), pending);
    }

    public CompletableFuture<TableSnapshot> changeTable(UUID id) {
        ClientSession session = session(id);
        TableSnapshot table = requireTable(session);
        requireAction(table, TableAction.CHANGE_TABLE);
        GameTransportPort connection = requireGameConnection(id);
        ProtocolRequest request = request(GameMessage.SIT_DOWN.id(), FunctionalEntity.SERVER,
                "Native.HuanLeGameSvr.CRequestSitDownHGPara", "Native.HuanLeGameSvr.CResponseSitDownHGPara",
                MiniGameRoomProtocol.quickMatchSitDown(table.roomId()));
        session.updateTable(current -> {
            requireAction(current, TableAction.CHANGE_TABLE);
            tableActions.clear(session.id());
            return new TableSnapshot(current.sceneId(), current.roomId(), current.tableId(), current.seatId(),
                    -1, -1, List.of(), List.of(), List.of(), List.of(), GameStage.SEATED, List.of(), 1,
                    "正在按 JS changeTable 流程重新匹配牌桌");
        });
        CompletableFuture<TableSnapshot> operation = connection.gameRequest(request,
                config.requestTimeout().multipliedBy(3)).thenApply(response -> {
            try {
                JsonNode body = json.readTree(response.json());
                checkResult(body, "换桌");
                return session.updateTable(current -> new TableSnapshot(current.sceneId(),
                        body.path("RoomID").asInt(current.roomId()), body.path("TableID").asInt(),
                        body.path("SeatID").asInt(-1), -1, -1, List.of(), List.of(), List.of(), List.of(),
                        GameStage.SEATED, List.of(), 1, "已换桌，等待匹配和发牌"));
            } catch (Exception failure) {
                throw new IllegalStateException("换桌失败: " + rootMessage(failure), failure);
            }
        });
        operation.whenComplete((response, failure) -> {
            if (failure != null)
                session.updateTable(current -> new TableSnapshot(table.sceneId(), table.roomId(),
                        table.tableId(), table.seatId(), table.turnSeatId(), table.landlordSeatId(), table.hand(),
                        table.lastPlay(), table.bottomCards(), table.players(), table.stage(), table.availableActions(),
                        table.multiplier(), "换桌失败: " + rootMessage(failure)));
        });
        return operation;
    }

    public CompletableFuture<TableSnapshot> leaveRoom(UUID id) {
        ClientSession session = session(id);
        TableSnapshot table = requireTable(session);
        requireAction(table, TableAction.LEAVE_ROOM);
        ProtocolRequest request = request(GameMessage.STAND_UP.id(), FunctionalEntity.SERVER,
                "Native.HuanLeGameSvr.CRequestStandUpHGPara", "Native.HuanLeGameSvr.CResponseStandUpHGPara",
                Map.of("StandUpFlag", 1, "RoomID", table.roomId()));
        return requireGameConnection(id).gameRequest(request, config.requestTimeout()).thenApply(response -> {
            try {
                checkResult(json.readTree(response.json()), "退出房间");
                session.table(TableSnapshot.empty());
                session.transition(ClientPhase.HALL_CONNECTED, "已退出房间，可重新选择场次");
                return session.table();
            } catch (Exception failure) {
                throw new IllegalStateException("退出房间失败: " + rootMessage(failure), failure);
            }
        });
    }

    private CompletableFuture<ProtocolResponse> sendGameAction(ClientSession session, byte[] gamePayload,
                                                               String label, Map<String, ?> detail,
                                                               TableActionCoordinator.PendingAction pendingAction) {
        GameTransportPort connection = requireGameConnection(session.id());
        Map<String, Object> body = Map.of("Uin", session.gameCredential().uin(),
                "Time", Instant.now().getEpochSecond(), "PlayerDataLength", gamePayload.length,
                "PlayData", Base64.getEncoder().encodeToString(gamePayload));
        // wxpkg g.nra sends mra/CRequestPlayHGPara with message id 15813. 15875 belonged to an
        // older guessed table and produced no matching response on the current GameSvr.
        ProtocolRequest play = request(GameMessage.PLAY.id(), FunctionalEntity.SERVER,
                "Native.HuanLeGameSvr.CRequestPlayHGPara", "Native.HuanLeGameSvr.CResponsePlayHGPara", body);
        CompletableFuture<ProtocolResponse> operation = connection.gameRequest(play, config.requestTimeout()).thenApply(response -> {
            checkGameActionResponse(response, label);
            session.publish("PLAY", label, detail);
            return response;
        });
        operation.whenComplete((response, failure) -> {
            if (failure != null)
                tableActions.restore(session, pendingAction, label + "失败: " + rootMessage(failure));
            else
                CompletableFuture.delayedExecutor(800, java.util.concurrent.TimeUnit.MILLISECONDS, workers)
                        .execute(() -> tableActions.restore(session, pendingAction, label + "已接收，等待服务器状态"));
        });
        return operation;
    }

    private void checkGameActionResponse(ProtocolResponse response, String label) {
        try {
            checkResult(json.readTree(response.json()), label);
        } catch (Exception failure) {
            throw new IllegalStateException(label + "失败: " + rootMessage(failure), failure);
        }
    }

    private static void ensureCardsInHand(TableSnapshot table, List<Integer> cards) {
        if (cards.isEmpty()) throw new IllegalArgumentException("请先选择要出的牌");
        List<Integer> hand = table.hand().stream().map(Card::code).toList();
        if (!hand.containsAll(cards))
            throw new IllegalArgumentException("选择中包含当前手牌里没有的牌");
    }

    private static void ensureCanBeat(TableSnapshot table, MiniGameActionProtocol protocol,
                                      MiniGameActionProtocol.Combination candidate, int cardCount) {
        if (table.lastPlay().isEmpty() || table.lastPlayType() == 0
                || table.lastPlaySeatId() == table.seatId()) return;
        if (!protocol.canBeat(candidate, cardCount, table.lastPlayType(), table.lastPlayRank(),
                table.lastPlay().size()))
            throw new IllegalArgumentException("所选牌型压不过上一手，请重新选择或不出");
    }

    private static TableSnapshot requireTable(ClientSession session) {
        TableSnapshot table = session.table();
        if (table == null || table.stage() == GameStage.IDLE || table.stage() == GameStage.LOCATING)
            throw new IllegalStateException("尚未进入牌桌");
        return table;
    }

    private GameTransportPort requireGameConnection(UUID id) {
        GameTransportPort connection = runtime.connection(id);
        if (connection == null || !connection.gameConnected())
            throw new IllegalStateException("游戏服务器尚未连接");
        return connection;
    }

    private static void requireAction(TableSnapshot table, TableAction action) {
        if (!table.availableActions().contains(action))
            throw new IllegalStateException("服务器当前未开放操作 " + action);
    }

    private CompletableFuture<List<GameServerEndpoint>> resolveGameServers(GameTransportPort connection, int serverId) {
        CompletableFuture<List<GameServerEndpoint>> normal = fetchGameDirectory(connection, 400, serverId)
                .exceptionally(failure -> List.of());
        CompletableFuture<List<GameServerEndpoint>> freeIp = fetchGameDirectory(connection, 10106, serverId)
                .exceptionally(failure -> List.of());
        return normal.thenCombine(freeIp, (first, second) -> {
            List<GameServerEndpoint> endpoints = Stream.concat(first.stream(), second.stream()).distinct().toList();
            if (endpoints.isEmpty())
                throw new IllegalStateException("AppDir 未返回可用的游戏服务器地址");
            return endpoints;
        });
    }

    private CompletableFuture<List<GameServerEndpoint>> fetchGameDirectory(GameTransportPort connection,
                                                                           int stuffId, int serverId) {
        Map<String, Object> body = Map.of("stuffId", stuffId, "gameId", config.id(),
                "clientType", config.clientType(), "accountType", config.accountType());
        ProtocolRequest request = request(10401, FunctionalEntity.APP_DIRECTORY,
                "Native.AppDir.CReqAppDirStuffPara", "Native.AppDir.CRspAppDirStuffPara", body);
        return connection.request(request, config.requestTimeout().multipliedBy(3)).thenApply(response -> {
            try {
                JsonNode root = json.readTree(response.json());
                int result = root.path("resultId").asInt();
                if (result != 0)
                    throw new IllegalStateException("AppDir stuff " + stuffId + " 返回 " + result);
                byte[] content = Base64.getDecoder().decode(root.path("contentBase64").asString());
                return GameServerDirectory.parse(content).resolve(serverId);
            } catch (Exception failure) {
                throw new IllegalStateException("解析 AppDir GameSvrList(" + stuffId + ") 失败", failure);
            }
        });
    }

    private static String rawText(GameCredential credential, String... names) {
        for (String name : names) {
            Object value = credential.raw().get(name);
            if (value != null && !value.toString().isBlank()) return value.toString();
        }
        return "";
    }

    private static int rawInt(GameCredential credential, String... names) {
        String value = rawText(credential, names);
        try {
            return value.isBlank() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private ProtocolRequest request(int messageId, int destination, String requestType, String responseType, Object body) {
        try {
            return new ProtocolRequest(messageId, destination, (short) -1, requestType, responseType, json.writeValueAsString(body), true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void onRemoteEvent(ClientSession session, GameTransportPort source, ProtocolResponse event) {
        // Transport failures from an old socket must not affect a newer session.
        if (event.messageId() == 0 && !runtime.isCurrent(session.id(), source)) return;
        session.publish("REMOTE", "收到服务器消息 " + event.messageId(), Map.of(
                "messageId", event.messageId(), "messageType", event.messageType(), "json", event.json(),
                "payload", Base64.getEncoder().encodeToString(event.payload())));
        if (event.messageId() == 0) {
            if (event.json().contains("gameTransportError")) {
                if (runtime.isCurrent(session.id(), source))
                    session.transition(ClientPhase.FAILED, "游戏服务器连接断开");
            } else if (runtime.releaseIfCurrent(session.id(), source)) {
                session.transition(ClientPhase.DISCONNECTED, "Lotus 连接断开");
            }
            return;
        }
        if (GameMessage.READY.matches(event.messageId()))
            updateTablePrompt(session, "已准备，等待服务器发牌");
        else if (GameMessage.ROOM_EVENT.matches(event.messageId()))
            applyRoomEvents(session, event.payload());
        else if (GameMessage.GAME_SO.matches(event.messageId()))
            applyGameSo(session, event.payload());
    }

    private void applyRoomEvents(ClientSession session, byte[] payload) {
        try {
            for (MiniGameGameSoCodec.RoomEvent event : gameSoCodec.decodeRoomEvents(payload)) {
                long localUin = session.gameCredential() == null ? 0 : session.gameCredential().uin();
                LOG.info("RoomEvent decoded type={} player={} room={} table={} seat={} localUin={}",
                        event.type(), event.playerId(), event.roomId(), event.tableId(), event.seatId(), localUin);
                TableSnapshot next = session.updateTable(current -> tableState.applyRoomEvent(current, event, localUin));
                if (event.eventType() == RoomEventType.USER_KICKED
                        && (event.playerId() == localUin || event.seatId() == next.seatId())) {
                    tableActions.clear(session.id());
                    session.table(TableSnapshot.empty());
                    session.transition(ClientPhase.EVICTED, "账号已在其他设备登录，本设备已退出游戏账号");
                    credentialVault.invalidate(session.ownerId());
                    runtime.disconnect(session.id());
                    return;
                }
                if (event.eventType() == RoomEventType.BALANCE_CHANGED && event.seatId() == next.seatId())
                    scheduleWalletRefresh(session, "本机余额变更事件");
                if (event.eventType() == RoomEventType.ITEM_CHANGED && event.seatId() == next.seatId())
                    event.items().forEach(item -> session.inventoryItem(item.itemId(),
                            HallApplicationService.itemName(item.itemId()), item.currentCount()));
                if (next.stage() == GameStage.DEALING && session.phase() != ClientPhase.PLAYING)
                    session.transition(ClientPhase.PLAYING, "牌局已开始");
            }
        } catch (RuntimeException failure) {
            session.publish("GAME_DECODE", "房间事件解码失败: " + rootMessage(failure), null);
        }
    }

    private void applyGameSo(ClientSession session, byte[] payload) {
        try {
            MiniGameGameSoCodec.GameSoEvent event = gameSoCodec.decodeGameSo(payload);
            LOG.info("GameSo decoded subtype={} errno={} seat={} actor={} next={} cards={} multiplier={} metadata={}",
                    event.subtype(), event.error(), event.seatId(), event.actorSeat(), event.nextSeat(),
                    event.cards().size(), event.multiplier(), event.metadata());
            TableActionCoordinator.PendingAction pending = tableActions.current(session.id());
            if (event.error() != 0) {
                session.publish("GAME_ERROR", "游戏操作被服务器拒绝 subtype=" + event.subtype()
                        + " errno=" + event.error(), Map.of("subtype", event.subtype(), "errno", event.error()));
                tableActions.restore(session, pending, "服务器拒绝操作，错误码 " + event.error());
                return;
            }
            TableSnapshot next = session.updateTable(current -> tableState.applyGameSo(current, event));
            tableActions.confirm(session.id(), pending, event, next);
            if (event.type() == GameSoType.SETTLEMENT || event.type() == GameSoType.ROUND_END)
                scheduleWalletRefresh(session, "牌局结算");
            LOG.info("Table reduced localSeat={} turn={} landlord={} stage={} actions={} hand={} lastPlay={} multiplier={}",
                    next.seatId(), next.turnSeatId(), next.landlordSeatId(), next.stage(),
                    next.availableActions(), next.hand().size(), next.lastPlay().size(), next.multiplier());
            session.publish("GAME_SO", "游戏消息 subtype=" + event.subtype() + " seat=" + event.seatId(),
                    Map.of("subtype", event.subtype(), "seatId", event.seatId(),
                            "actorSeat", event.actorSeat(), "nextSeat", event.nextSeat(),
                            "cards", event.cards(), "bottomCards", event.bottomCards()));
            if (next.stage() != GameStage.SETTLED && next.stage() != GameStage.IDLE
                    && session.phase() != ClientPhase.PLAYING)
                session.transition(ClientPhase.PLAYING, "牌局已开始");
        } catch (RuntimeException failure) {
            session.publish("GAME_DECODE", "游戏消息解码失败: " + rootMessage(failure), null);
        }
    }

    private static void updateTablePrompt(ClientSession session, String prompt) {
        TableSnapshot current = session.table();
        session.table(copyTable(current, current.roomId(), current.tableId(), current.seatId(), current.hand(), prompt));
    }

    private void scheduleWalletRefresh(ClientSession session, String reason) {
        if (!walletRefreshPending.add(session.id())) return;
        CompletableFuture.delayedExecutor(300, java.util.concurrent.TimeUnit.MILLISECONDS, workers).execute(() ->
                refreshWallet(session.id()).whenComplete((balance, failure) -> {
                    walletRefreshPending.remove(session.id());
                    if (failure != null)
                        session.publish("WALLET", reason + "后刷新失败: " + rootMessage(failure), null);
                    else session.publish("WALLET", reason + "后已同步", balance);
                }));
    }

    private static TableSnapshot copyTable(TableSnapshot current, int roomId, int tableId, int seatId,
                                           List<cc.superbaby.happy4j.domain.Card> hand, String prompt) {
        return new TableSnapshot(current.sceneId(), roomId, tableId, seatId,
                current.turnSeatId(), current.landlordSeatId(), hand, current.lastPlay(),
                current.lastPlayType(), current.lastPlayRank(), current.lastPlaySeatId(), current.bottomCards(),
                current.players(), current.actionHistory(), current.stage(), current.availableActions(),
                current.multiplier(), current.trusted(), current.settlement(), current.superDoubleItemId(),
                current.operationSeconds(), prompt);
    }

    private static TableSnapshot withState(TableSnapshot current, List<Card> hand, List<Card> lastPlay,
                                           List<Card> bottomCards, GameStage stage, List<TableAction> actions,
                                           int turnSeat, int landlordSeat, int multiplier, String prompt) {
        return new TableSnapshot(current.sceneId(), current.roomId(), current.tableId(), current.seatId(),
                turnSeat, landlordSeat, hand, lastPlay, current.lastPlayType(), current.lastPlayRank(),
                current.lastPlaySeatId(), bottomCards, current.players(), current.actionHistory(), stage, actions,
                multiplier, current.trusted(), current.settlement(), current.superDoubleItemId(), prompt);
    }

    private ClientSession requireHall(UUID id) {
        ClientSession session = session(id);
        if (session.gameCredential() == null || !runtime.connected(id))
            throw new IllegalStateException("大厅尚未连接");
        return session;
    }

    private static void checkResult(JsonNode node, String operation) {
        if (node.has("decodeError")) throw new IllegalStateException(operation + "响应解码失败: "
                + node.path("decodeError").asString());
        if (!node.has("ResultID") && !node.has("resultId"))
            throw new IllegalStateException(operation + "响应缺少 ResultID");
        int value = node.path("ResultID").asInt(node.path("resultId").asInt());
        if (value != 0) {
            String message = node.path("ResultStr").asString(node.path("resultStr").asString()).replace("\0", "").trim();
            String action = value == 10621 || value == 10622 ? "（需要先完成人脸识别）" : "";
            LOG.warn("{} rejected, full response: {}", operation, node);
            throw new IllegalStateException(operation + "失败，游戏服务器返回 " + value + action
                    + (message.isBlank() ? "" : "：" + message));
        }
    }

    private static String requiredQr(ClientSession session) {
        if (session.qrId() == null) throw new IllegalStateException("尚未创建二维码");
        return session.qrId();
    }

    private QrAuthPort provider(String name) {
        String key = name == null || name.isBlank() ? "qq" : name.toLowerCase(java.util.Locale.ROOT);
        QrAuthPort result = qrAuth.get(key);
        if (result == null) throw new IllegalArgumentException("未知扫码渠道: " + name);
        return result;
    }

    private static String rootMessage(Throwable source) {
        Throwable value = source;
        while (value.getCause() != null) value = value.getCause();
        return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
    }

    private static ExecutorService workerPool(int configuredThreads, int configuredQueueCapacity) {
        int threads = Math.max(4, Math.min(64, configuredThreads));
        int queueCapacity = Math.max(64, Math.min(4096, configuredQueueCapacity));
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
            Thread thread = new Thread(runnable, "game-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public record RuntimeCapacity(int maxActiveUsers,
                                  int maxActiveSessionsPerUser,
                                  long activeUsers,
                                  int activeConnections,
                                  int workerThreads,
                                  int workerQueueCapacity,
                                  int qrPollThreads) {
    }

    @Override
    public void close() {
        runtime.close();
        workers.shutdownNow();
    }

}
