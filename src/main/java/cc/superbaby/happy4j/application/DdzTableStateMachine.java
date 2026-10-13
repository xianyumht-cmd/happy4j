package cc.superbaby.happy4j.application;

import cc.superbaby.happy4j.domain.Card;
import cc.superbaby.happy4j.domain.GameStage;
import cc.superbaby.happy4j.domain.TableAction;
import cc.superbaby.happy4j.domain.TableSnapshot;
import cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec.GameSoEvent;
import cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec.RoomEvent;
import cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec;
import cc.superbaby.happy4j.protocol.game.GameSoType;
import cc.superbaby.happy4j.protocol.game.RoomEventType;
import cc.superbaby.happy4j.protocol.game.ReplaySection;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, testable reducer from decoded server events to the browser-facing table state.
 */
public final class DdzTableStateMachine {
    /**
     * DouZero needs every played card in the current round for its cumulative public-state features.
     * A 50-entry UI history truncated long games and made late-round inference observe impossible cards.
     */
    static final int MAX_ACTION_HISTORY = 256;
    private final cc.superbaby.happy4j.protocol.game.MiniGameActionProtocol rules =
            new cc.superbaby.happy4j.protocol.game.MiniGameActionProtocol();

    public TableSnapshot applyRoomEvent(TableSnapshot current, RoomEvent event, long localUin) {
        int roomId = event.roomId() >= 0 ? event.roomId() : current.roomId();
        int tableId = event.tableId() >= 0 ? event.tableId() : current.tableId();
        // 15809 contains one event per player. Treating every event's SeatID as the local chair
        // made the client jump between chairs and submit actions during an opponent's turn.
        int seatId = event.playerId() == localUin && event.seatId() >= 0
                ? event.seatId() : current.seatId();
        boolean localEvent = event.playerId() == localUin
                || current.seatId() >= 0 && event.seatId() == current.seatId();
        List<TableSnapshot.Player> players = current.players();
        TableSnapshot.Settlement settlement = current.settlement();
        for (var detail : event.players()) {
            players = upsertPlayer(players, detail);
            if (detail.uin() == localUin && detail.seatId() >= 0) {
                seatId = detail.seatId();
                localEvent = true;
            }
        }
        if (event.beans() >= 0 && event.seatId() >= 0) {
            players = setPlayerBeans(players, event.playerId(), event.seatId(),
                    event.beans(), event.beanChange());
            settlement = updateSettlementBalance(settlement, event.seatId(), event.beans());
        }
        if ((event.eventType() == RoomEventType.EXIT_ROOM || event.eventType() == RoomEventType.LEAVE_TABLE)
                && event.seatId() >= 0) {
            int leavingSeat = event.seatId();
            players = players.stream().filter(player -> player.seatId() != leavingSeat).toList();
        } else if (event.eventType() == RoomEventType.READY && event.seatId() >= 0) {
            players = setPlayerReady(players, event.seatId(), true);
        }
        GameStage stage = current.stage();
        List<TableAction> actions = current.availableActions();
        List<TableSnapshot.ActionLog> history = current.actionHistory();
        int operationSeconds = current.operationSeconds();
        String prompt = current.prompt();
        switch (event.eventType()) {
            case ENTER_TABLE -> {
                if (stage == GameStage.IDLE || stage == GameStage.LOCATING || stage == GameStage.SEATED)
                    stage = GameStage.SEATED;
                prompt = localEvent ? "你已进入牌桌" : "有玩家进入牌桌";
            }
            case GAME_START -> {
                players = clearPlayerReady(players);
                history = List.of();
                stage = GameStage.DEALING;
                actions = List.of();
                operationSeconds = 0;
                prompt = "牌局开始，正在发牌";
            }
            case READY -> {
                if (localEvent) {
                    stage = GameStage.READY;
                    actions = List.of();
                    operationSeconds = 0;
                    prompt = "你已准备，等待发牌";
                } else if (stage == GameStage.SETTLED) {
                    actions = settledActions();
                    prompt = "对手已准备，你可以准备下一局";
                }
            }
            case USER_KICKED -> {
                players = clearPlayerReady(players);
                stage = GameStage.IDLE;
                actions = List.of();
                operationSeconds = 0;
                prompt = "账号已在其他设备登录";
            }
            default -> {
            }
        }
        if (event.type() >= 1 && event.type() <= 17)
            history = appendHistory(history, players, event.seatId(), roomEventName(event.type()), List.of());
        return new TableSnapshot(current.sceneId(), roomId, tableId, seatId,
                current.turnSeatId(), current.landlordSeatId(), current.hand(), current.lastPlay(),
                current.lastPlayType(), current.lastPlayRank(), current.lastPlaySeatId(),
                current.bottomCards(), players, history, stage, actions, current.multiplier(),
                current.trusted(), settlement, current.superDoubleItemId(), operationSeconds, prompt);
    }

    public TableSnapshot applyGameSo(TableSnapshot source, GameSoEvent event) {
        if (event.error() != 0) return source;
        TableSnapshot current = source;
        if ((event.type() == GameSoType.INITIAL_HAND || event.type() == GameSoType.HAND_SYNC
                || event.type() == GameSoType.RECONNECT_SNAPSHOT)
                && event.seatId() >= 0
                && (event.type() == GameSoType.RECONNECT_SNAPSHOT || !event.cards().isEmpty())
                && event.seatId() != current.seatId())
            current = copySeat(current, event.seatId());
        List<Card> hand = current.hand();
        List<Card> lastPlay = current.lastPlay();
        int lastPlayType = current.lastPlayType();
        int lastPlayRank = current.lastPlayRank();
        int lastPlaySeat = current.lastPlaySeatId();
        List<Card> bottomCards = current.bottomCards();
        GameStage stage = current.stage();
        List<TableAction> actions = current.availableActions();
        int turnSeat = current.turnSeatId();
        int landlordSeat = current.landlordSeatId();
        int multiplier = current.multiplier();
        boolean trusted = current.trusted();
        List<TableSnapshot.Player> players = current.players();
        List<TableSnapshot.ActionLog> history = current.actionHistory();
        TableSnapshot.Settlement settlement = current.settlement();
        int superDoubleItemId = current.superDoubleItemId();
        int operationSeconds = current.operationSeconds();
        String prompt = current.prompt();
        switch (event.type()) {
            case INITIAL_HAND -> {
                settlement = TableSnapshot.Settlement.empty();
                history = List.of();
                if (!event.cards().isEmpty()) hand = cards(event.cards());
                players = initialPlayers(players, current.seatId(), hand.size());
                stage = GameStage.CALLING_LANDLORD;
                actions = List.of();
                operationSeconds = 0;
                superDoubleItemId = 0;
                prompt = "已发牌，等待服务器通知叫地主";
            }
            case PLAY -> {
                if (!event.cards().isEmpty()) {
                    lastPlay = cards(event.cards());
                    lastPlayType = event.cardType();
                    lastPlayRank = event.mainRank();
                    lastPlaySeat = event.actorSeat();
                }
                if (event.actorSeat() >= 0)
                    history = appendHistory(history, players, event.actorSeat(),
                            event.cards().isEmpty() ? "不出" : "出牌", cards(event.cards()));
                if (isLocalSeat(current, event.actorSeat()) && !event.cards().isEmpty())
                    hand = removeCards(hand, event.cards());
                players = decrementCards(players, event.actorSeat(), event.cards().size());
                turnSeat = event.nextSeat();
                stage = GameStage.PLAYING;
                operationSeconds = turnSeat < 0 ? 0
                        : event.nextTimeLimit() > 0 ? event.nextTimeLimit() : 25;
                if (event.cards().isEmpty() && event.nextSeat() == lastPlaySeat) {
                    lastPlay = List.of();
                    lastPlayType = 0;
                    lastPlayRank = 0;
                    lastPlaySeat = -1;
                }
                if (isLocalSeat(current, event.nextSeat())) {
                    actions = playingActions(current, event.nextSeat(), lastPlay, trusted);
                    prompt = "轮到你出牌";
                } else {
                    actions = playingActions(current, event.nextSeat(), lastPlay, trusted);
                    prompt = event.cards().isEmpty()
                            ? (isLocalSeat(current, event.actorSeat()) ? "你选择了不出，等待下一位" : "对手不出，等待下一位")
                            : (isLocalSeat(current, event.actorSeat()) ? "你已出牌，等待下一位" : "对手已出牌，等待下一位");
                }
            }
            case SETTLEMENT, ROUND_END -> {
                if (event.type() == GameSoType.SETTLEMENT && event.settlement().available()) {
                    settlement = settlement(event.settlement(), players);
                    players = applySettlementPlayers(players, settlement);
                }
                stage = GameStage.SETTLED;
                actions = settledActions();
                turnSeat = -1;
                operationSeconds = 0;
                prompt = "本局结束：可再来一局、换桌或退出房间";
                if (current.stage() != GameStage.SETTLED)
                    history = appendHistory(history, players, -1, "本局结束", List.of());
            }
            case ORDER -> {
                turnSeat = event.nextSeat();
                stage = GameStage.CALLING_LANDLORD;
                operationSeconds = turnSeat < 0 ? 0 : 15;
                boolean rob = event.decision() != 0;
                if (event.actorSeat() >= 0)
                    history = appendHistory(history, players, event.actorSeat(),
                            event.decision() == 0 ? "不叫/不抢" : "叫/抢地主", List.of());
                if (isLocalSeat(current, event.nextSeat())) {
                    actions = rob
                            ? List.of(TableAction.ROB_LANDLORD, TableAction.PASS_ROB)
                            : List.of(TableAction.CALL_LANDLORD, TableAction.PASS_LANDLORD);
                    prompt = rob ? "轮到你抢地主" : "轮到你叫地主";
                } else {
                    actions = List.of();
                    prompt = rob ? "等待其他玩家抢地主" : "等待其他玩家叫地主";
                }
            }
            case LANDLORD_CARDS -> {
                bottomCards = cards(event.bottomCards());
                landlordSeat = event.landlordSeat();
                players = addCards(players, landlordSeat, bottomCards.size());
                if (isLocalSeat(current, landlordSeat)) hand = mergeCards(hand, bottomCards);
                stage = GameStage.DOUBLING;
                actions = List.of();
                operationSeconds = 0;
                prompt = isLocalSeat(current, landlordSeat) ? "你成为地主，等待加倍阶段" : "地主已确定，等待加倍阶段";
                history = appendHistory(history, players, landlordSeat, "获得地主和底牌", bottomCards);
            }
            case DOUBLE_OPTIONS -> {
                stage = GameStage.DOUBLING;
                if (event.seatId() >= 0) turnSeat = event.seatId();
                operationSeconds = turnSeat < 0 ? 0 : 6;
                if (event.superDoubleItemId() > 0) superDoubleItemId = event.superDoubleItemId();
                if (isLocalSeat(current, event.seatId())) {
                    actions = (event.decision() & 2) != 0
                            ? List.of(TableAction.DOUBLE, TableAction.SUPER_DOUBLE, TableAction.NO_DOUBLE)
                            : List.of(TableAction.DOUBLE, TableAction.NO_DOUBLE);
                    prompt = "请选择是否加倍";
                } else {
                    actions = List.of();
                    prompt = "等待其他玩家选择是否加倍";
                }
            }
            case DOUBLE_RESULT -> {
                if (event.multiplier() > 0) multiplier = Math.max(multiplier, event.multiplier());
                if (isLocalSeat(current, event.actorSeat())) {
                    actions = List.of();
                    turnSeat = -1;
                    operationSeconds = 0;
                }
                prompt = "加倍结果已确认，等待其他玩家";
                if (event.actorSeat() >= 0)
                    history = appendHistory(history, players, event.actorSeat(),
                            event.multiplier() > 1 ? "加倍 ×" + event.multiplier() : "不加倍", List.of());
            }
            case MULTIPLIER_SYNC -> {
                if (event.multiplier() > 0) multiplier = event.multiplier();
            }
            case TRUST -> {
                if (isLocalSeat(current, event.actorSeat()) && stage == GameStage.PLAYING) {
                    trusted = (event.decision() & 5) != 0;
                    if (trusted) {
                        actions = List.of(TableAction.CANCEL_TRUST);
                        prompt = "当前处于托管状态";
                    } else if (turnSeat == current.seatId()) {
                        actions = playingActions(current, turnSeat, lastPlay, false);
                        prompt = "托管已取消，轮到你出牌";
                    } else actions = List.of(TableAction.TRUST);
                }
                if (event.actorSeat() >= 0 && stage == GameStage.PLAYING)
                    history = appendHistory(history, players, event.actorSeat(),
                            (event.decision() & 5) != 0 ? "进入托管" : "取消托管", List.of());
            }
            case HAND_SYNC -> {
                if (!event.cards().isEmpty()) hand = cards(event.cards());
                prompt = "手牌已与服务器同步";
            }
            case LANDLORD_RESULT -> {
                landlordSeat = event.landlordSeat();
                actions = List.of();
                prompt = isLocalSeat(current, landlordSeat) ? "你成为地主" : "地主已确定";
                history = appendHistory(history, players, landlordSeat, "成为地主", List.of());
            }
            case FIRST_TURN -> {
                turnSeat = event.actorSeat();
                stage = GameStage.PLAYING;
                operationSeconds = turnSeat < 0 ? 0 : 30;
                if (isLocalSeat(current, event.actorSeat())) {
                    actions = playingActions(current, turnSeat, List.of(), trusted);
                    prompt = "你是首出玩家，请出牌";
                } else {
                    actions = playingActions(current, turnSeat, List.of(), trusted);
                    prompt = "等待首出玩家出牌";
                }
                history = appendHistory(history, players, event.actorSeat(), "获得首出", List.of());
            }
            case DEAL_START -> {
                settlement = TableSnapshot.Settlement.empty();
                history = List.of();
                stage = GameStage.DEALING;
                actions = List.of();
                trusted = false;
                operationSeconds = 0;
                superDoubleItemId = 0;
                prompt = "对局开始，正在发牌";
            }
            case TABLE_TIMEOUT -> {
                stage = GameStage.TIMED_OUT;
                actions = List.of(TableAction.CHANGE_TABLE, TableAction.LEAVE_ROOM);
                operationSeconds = 0;
                prompt = "本桌等待超时，请换桌继续";
            }
            case RECONNECT_SNAPSHOT -> {
                if (current.seatId() >= 0 && current.seatId() < event.hands().size()
                        && !event.hands().get(current.seatId()).isEmpty())
                    hand = cards(event.hands().get(current.seatId()));
                if (!event.bottomCards().isEmpty()) {
                    lastPlay = cards(event.bottomCards());
                    lastPlaySeat = event.actorSeat();
                    try {
                        var combination = rules.classifyCards(event.bottomCards());
                        lastPlayType = combination.type();
                        lastPlayRank = combination.mainRank();
                    } catch (RuntimeException ignored) {
                    }
                }
                players = playersFromHands(players, event.hands(), current.seatId());
                turnSeat = event.nextSeat();
                landlordSeat = event.landlordSeat();
                stage = ReplaySection.fromCode(event.decision()).stage();
                operationSeconds = turnSeat < 0 ? 0 : switch (stage) {
                    case CALLING_LANDLORD -> 15;
                    case DOUBLING -> 6;
                    case PLAYING -> 25;
                    default -> 0;
                };
                if (turnSeat == current.seatId()) {
                    if (stage == GameStage.PLAYING)
                        actions = playingActions(current, turnSeat, lastPlay, trusted);
                    else if (stage == GameStage.CALLING_LANDLORD)
                        actions = List.of(TableAction.CALL_LANDLORD, TableAction.PASS_LANDLORD);
                    else if (stage == GameStage.DOUBLING)
                        actions = List.of(TableAction.DOUBLE, TableAction.NO_DOUBLE);
                } else actions = stage == GameStage.PLAYING
                        ? playingActions(current, turnSeat, lastPlay, trusted) : List.of();
                prompt = actions.isEmpty() ? "断线重回状态已同步，等待其他玩家操作" : "断线重回状态已同步，轮到你操作";
            }
            default -> {
            }
        }
        return new TableSnapshot(current.sceneId(), current.roomId(), current.tableId(), current.seatId(),
                turnSeat, landlordSeat, hand, lastPlay, lastPlayType, lastPlayRank, lastPlaySeat,
                bottomCards, players, history, stage, actions,
                multiplier, trusted, settlement, superDoubleItemId, operationSeconds, prompt);
    }

    private static TableSnapshot copySeat(TableSnapshot current, int seatId) {
        return new TableSnapshot(current.sceneId(), current.roomId(), current.tableId(), seatId,
                current.turnSeatId(), current.landlordSeatId(), current.hand(), current.lastPlay(),
                current.lastPlayType(), current.lastPlayRank(), current.lastPlaySeatId(),
                current.bottomCards(), current.players(), current.actionHistory(), current.stage(), current.availableActions(),
                current.multiplier(), current.trusted(), current.settlement(), current.superDoubleItemId(),
                current.operationSeconds(),
                current.prompt());
    }

    private static List<Card> cards(List<Integer> codes) {
        return codes.stream().map(Card::fromCode)
                .sorted(java.util.Comparator.comparingInt(DdzTableStateMachine::cardOrder).reversed()
                        .thenComparingInt(Card::code)).toList();
    }

    private static int cardOrder(Card card) {
        if (card.code() == 53) return 13;
        if (card.code() == 54) return 14;
        int rank = (card.code() - 1) % 13 + 1;
        if (rank == 1) return 11;
        if (rank == 2) return 12;
        return rank - 3;
    }

    private static List<Card> removeCards(List<Card> hand, List<Integer> codes) {
        Set<Integer> removed = new HashSet<>(codes);
        return hand.stream().filter(card -> !removed.contains(card.code())).toList();
    }

    private static List<Card> mergeCards(List<Card> hand, List<Card> added) {
        return cards(java.util.stream.Stream.concat(hand.stream(), added.stream())
                .map(Card::code).distinct().toList());
    }

    private static boolean isLocalSeat(TableSnapshot table, int serverSeat) {
        return serverSeat >= 0 && table.seatId() >= 0 && serverSeat == table.seatId();
    }

    private static List<TableAction> playingActions(TableSnapshot table, int turnSeat,
                                                     List<Card> lastPlay, boolean trusted) {
        if (trusted) return List.of(TableAction.CANCEL_TRUST);
        if (turnSeat != table.seatId()) return List.of(TableAction.TRUST);
        return lastPlay.isEmpty()
                ? List.of(TableAction.PLAY, TableAction.TRUST)
                : List.of(TableAction.PLAY, TableAction.PASS, TableAction.TRUST);
    }

    private static List<TableAction> settledActions() {
        return List.of(TableAction.READY, TableAction.CHANGE_TABLE, TableAction.LEAVE_ROOM);
    }

    private static List<TableSnapshot.Player> initialPlayers(List<TableSnapshot.Player> existing,
                                                             int localSeat, int localCount) {
        return java.util.stream.IntStream.range(0, 3).mapToObj(seat -> existing.stream()
                .filter(player -> player.seatId() == seat).findFirst()
                .map(player -> new TableSnapshot.Player(player.uin(), player.nickname(), seat,
                        seat == localSeat ? localCount : 17, player.beans(), player.ready(),
                        player.avatarUrl(), 0))
                .orElseGet(() -> new TableSnapshot.Player(0,
                        seat == localSeat ? "我" : "玩家 " + (seat + 1), seat,
                        seat == localSeat ? localCount : 17, 0, true))).toList();
    }

    private static List<TableSnapshot.Player> decrementCards(List<TableSnapshot.Player> players,
                                                             int seat, int amount) {
        if (seat < 0 || amount <= 0) return players;
        return players.stream().map(player -> player.seatId() == seat
                ? new TableSnapshot.Player(player.uin(), player.nickname(), player.seatId(),
                Math.max(0, player.cardCount() - amount), player.beans(), player.ready(),
                player.avatarUrl(), player.lastBeanChange()) : player).toList();
    }

    private static List<TableSnapshot.Player> addCards(List<TableSnapshot.Player> players, int seat, int amount) {
        if (seat < 0 || amount <= 0) return players;
        return players.stream().map(player -> player.seatId() == seat
                ? new TableSnapshot.Player(player.uin(), player.nickname(), player.seatId(),
                player.cardCount() + amount, player.beans(), player.ready(), player.avatarUrl(),
                player.lastBeanChange()) : player).toList();
    }

    private static List<TableSnapshot.Player> playersFromHands(List<TableSnapshot.Player> existing,
                                                               List<List<Integer>> hands, int localSeat) {
        if (hands.size() < 3) return List.of();
        return java.util.stream.IntStream.range(0, 3).mapToObj(seat -> existing.stream()
                .filter(player -> player.seatId() == seat).findFirst()
                .map(player -> new TableSnapshot.Player(player.uin(), player.nickname(), seat,
                        hands.get(seat).size(), player.beans(), player.ready(), player.avatarUrl(),
                        player.lastBeanChange()))
                .orElseGet(() -> new TableSnapshot.Player(0,
                        seat == localSeat ? "我" : "玩家 " + (seat + 1), seat,
                        hands.get(seat).size(), 0, true))).toList();
    }

    private static List<TableSnapshot.Player> upsertPlayer(List<TableSnapshot.Player> players,
                                                           MiniGameGameSoCodec.PlayerDetail detail) {
        TableSnapshot.Player previous = players.stream()
                .filter(player -> player.seatId() == detail.seatId()
                        || detail.uin() != 0 && player.uin() == detail.uin())
                .findFirst().orElse(null);
        List<TableSnapshot.Player> result = new java.util.ArrayList<>(players);
        result.removeIf(player -> player.seatId() == detail.seatId()
                || detail.uin() != 0 && player.uin() == detail.uin());
        String nickname = detail.nickname() == null || detail.nickname().isBlank()
                ? previous == null ? "玩家 " + (detail.seatId() + 1) : previous.nickname()
                : detail.nickname();
        String avatar = detail.avatarUrl() == null || detail.avatarUrl().isBlank()
                ? previous == null ? "" : previous.avatarUrl() : detail.avatarUrl();
        long uin = detail.uin() != 0 ? detail.uin() : previous == null ? 0 : previous.uin();
        long beans = detail.beans() > 0 || previous == null ? detail.beans() : previous.beans();
        int cardCount = previous == null ? 17 : previous.cardCount();
        result.add(new TableSnapshot.Player(uin, nickname, detail.seatId(), cardCount, beans,
                detail.state() >= 5, avatar, previous == null ? 0 : previous.lastBeanChange()));
        return List.copyOf(result);
    }

    private static List<TableSnapshot.Player> setPlayerBeans(List<TableSnapshot.Player> players,
                                                             long uin, int seatId, long beans, long beanChange) {
        boolean found = players.stream().anyMatch(player -> player.seatId() == seatId);
        List<TableSnapshot.Player> result = players.stream().map(player -> player.seatId() == seatId
                ? new TableSnapshot.Player(player.uin(), player.nickname(), player.seatId(),
                player.cardCount(), beans, player.ready(), player.avatarUrl(), beanChange) : player).toList();
        if (found) return result;
        List<TableSnapshot.Player> appended = new java.util.ArrayList<>(result);
        appended.add(new TableSnapshot.Player(uin, "玩家 " + (seatId + 1), seatId,
                17, beans, false, "", beanChange));
        return List.copyOf(appended);
    }

    private static TableSnapshot.Settlement settlement(MiniGameGameSoCodec.Settlement source,
                                                        List<TableSnapshot.Player> players) {
        List<TableSnapshot.PlayerResult> results = java.util.stream.IntStream.range(0, 3)
                .mapToObj(seat -> {
                    long change = source.beanChanges().get(seat);
                    TableSnapshot.Player player = players.stream()
                            .filter(value -> value.seatId() == seat).findFirst().orElse(null);
                    boolean alreadyApplied = player != null && player.lastBeanChange() == change;
                    long balance = player == null || player.uin() == 0 && player.beans() == 0
                            ? -1 : alreadyApplied ? player.beans() : Math.max(0, player.beans() + change);
                    return new TableSnapshot.PlayerResult(seat, change, balance,
                            source.factors().size() > seat ? source.factors().get(seat) : 0,
                            source.hands().size() > seat ? cards(source.hands().get(seat)) : List.of());
                })
                .toList();
        return new TableSnapshot.Settlement(source.resultFlag(), results);
    }

    private static TableSnapshot.Settlement updateSettlementBalance(TableSnapshot.Settlement settlement,
                                                                     int seatId, long beans) {
        if (!settlement.available()) return settlement;
        return new TableSnapshot.Settlement(settlement.resultFlag(), settlement.players().stream()
                .map(result -> result.seatId() == seatId
                        ? new TableSnapshot.PlayerResult(result.seatId(), result.beanChange(), beans,
                        result.factor(), result.remainingCards()) : result)
                .toList());
    }

    private static List<TableSnapshot.Player> applySettlementPlayers(List<TableSnapshot.Player> players,
                                                                     TableSnapshot.Settlement settlement) {
        return players.stream().map(player -> settlement.players().stream()
                .filter(result -> result.seatId() == player.seatId()).findFirst()
                .map(result -> new TableSnapshot.Player(player.uin(), player.nickname(), player.seatId(),
                        result.remainingCards().size(), result.beans() >= 0 ? result.beans() : player.beans(),
                        player.ready(), player.avatarUrl(), 0))
                .orElse(player)).toList();
    }

    private static List<TableSnapshot.Player> setPlayerReady(List<TableSnapshot.Player> players,
                                                             int seatId, boolean ready) {
        return players.stream().map(player -> player.seatId() == seatId
                ? new TableSnapshot.Player(player.uin(), player.nickname(), player.seatId(),
                player.cardCount(), player.beans(), ready, player.avatarUrl(),
                player.lastBeanChange()) : player).toList();
    }

    private static List<TableSnapshot.Player> clearPlayerReady(List<TableSnapshot.Player> players) {
        return players.stream().map(player -> player.ready()
                ? new TableSnapshot.Player(player.uin(), player.nickname(), player.seatId(),
                player.cardCount(), player.beans(), false, player.avatarUrl(),
                player.lastBeanChange()) : player).toList();
    }

    private static String roomEventName(int type) {
        RoomEventType event = RoomEventType.fromCode(type);
        return event == RoomEventType.UNKNOWN ? "房间事件 #" + type : event.label();
    }

    private static List<TableSnapshot.ActionLog> appendHistory(List<TableSnapshot.ActionLog> history,
                                                               List<TableSnapshot.Player> players,
                                                               int seatId, String action, List<Card> cards) {
        String player = players.stream().filter(value -> value.seatId() == seatId)
                .map(TableSnapshot.Player::nickname).findFirst()
                .orElse(seatId < 0 ? "系统" : "玩家 " + (seatId + 1));
        List<TableSnapshot.ActionLog> result = new java.util.ArrayList<>(history);
        result.add(new TableSnapshot.ActionLog(java.time.Instant.now(), seatId, player, action, cards));
        if (result.size() > MAX_ACTION_HISTORY)
            result = result.subList(result.size() - MAX_ACTION_HISTORY, result.size());
        return List.copyOf(result);
    }
}
