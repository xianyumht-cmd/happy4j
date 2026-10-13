package cc.superbaby.happy4j.application;

import cc.superbaby.happy4j.domain.GameStage;
import cc.superbaby.happy4j.domain.TableAction;
import cc.superbaby.happy4j.domain.TableSnapshot;
import cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec.GameSoEvent;
import cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec.RoomEvent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DdzTableStateMachineTest {
    private final DdzTableStateMachine machine = new DdzTableStateMachine();

    @Test
    void reducesDealCallDoubleAndPlayIntoServerDrivenActions() {
        TableSnapshot table = new TableSnapshot(1260, 0, 88, -1, -1, -1,
                List.of(), List.of(), List.of(), List.of(), GameStage.SEATED, List.of(), 1, "已坐下");

        table = machine.applyGameSo(table, event(1, 2, -1, -1, 0, -1,
                range(1, 17), List.of(), 0));
        assertThat(table.seatId()).isEqualTo(2);
        assertThat(table.hand()).hasSize(17);
        assertThat(table.stage()).isEqualTo(GameStage.CALLING_LANDLORD);
        assertThat(table.availableActions()).isEmpty();

        table = machine.applyGameSo(table, event(6, -1, -1, 2, 0, -1,
                List.of(), List.of(), 0));
        assertThat(table.availableActions()).containsExactly(TableAction.CALL_LANDLORD, TableAction.PASS_LANDLORD);
        assertThat(table.operationSeconds()).isEqualTo(15);

        table = machine.applyGameSo(table, event(7, -1, -1, -1, 0, 2,
                List.of(), List.of(51, 52, 53), 0));
        assertThat(table.landlordSeatId()).isEqualTo(2);
        assertThat(table.bottomCards()).hasSize(3);
        assertThat(table.hand()).hasSize(20);

        table = machine.applyGameSo(table, new GameSoEvent(0, 2, 10, 0, -1, -1, 3,
                0, 0, -1, 0, List.of(), List.of(), List.of(),
                cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec.Settlement.empty(),
                0, 30020001));
        assertThat(table.availableActions()).containsExactly(
                TableAction.DOUBLE, TableAction.SUPER_DOUBLE, TableAction.NO_DOUBLE);
        assertThat(table.operationSeconds()).isEqualTo(6);
        assertThat(table.superDoubleItemId()).isEqualTo(30020001);

        table = machine.applyGameSo(table, event(12, 2, -1, -1, 2, -1,
                List.of(), List.of(), 8));
        assertThat(table.multiplier()).isEqualTo(8);

        table = machine.applyGameSo(table, event(46, -1, 2, 0, 0, -1,
                List.of(), List.of(), 0));
        assertThat(table.availableActions()).containsExactly(TableAction.PLAY, TableAction.TRUST);
        assertThat(table.operationSeconds()).isEqualTo(30);

        table = machine.applyGameSo(table, event(4, -1, 2, 1, 0, -1,
                List.of(1), List.of(), 0));
        assertThat(table.hand()).hasSize(19);
        assertThat(table.lastPlay()).extracting("code").containsExactly(1);
        assertThat(table.availableActions()).containsExactly(TableAction.TRUST);

        table = machine.applyGameSo(table, event(4, -1, 1, 2, 0, -1,
                List.of(2), List.of(), 0));
        assertThat(table.availableActions()).containsExactly(TableAction.PLAY, TableAction.PASS, TableAction.TRUST);
        assertThat(table.operationSeconds()).isEqualTo(25);

        table = machine.applyGameSo(table, event(102, -1, -1, -1, 0, -1,
                List.of(), List.of(), 0));
        assertThat(table.stage()).isEqualTo(GameStage.SETTLED);
        assertThat(table.availableActions()).containsExactly(
                TableAction.READY, TableAction.CHANGE_TABLE, TableAction.LEAVE_ROOM);
    }

    @Test
    void opponentRoomEventsNeverOverwriteTheLocalChair() {
        TableSnapshot table = new TableSnapshot(1260, 0, 88, 0, -1, -1,
                List.of(), List.of(), List.of(), List.of(), GameStage.SEATED, List.of(), 1, "已坐下");

        table = machine.applyRoomEvent(table, new RoomEvent(3, 998L, 0, 88, 2), 397911882L);
        assertThat(table.seatId()).isZero();
        table = machine.applyRoomEvent(table, new RoomEvent(3, 397911882L, 0, 88, 1), 397911882L);
        assertThat(table.seatId()).isEqualTo(1);

        var detail = new cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec.PlayerDetail(
                42, 397911882L, 5, 88, 1, "Alice");
        table = machine.applyRoomEvent(table,
                new RoomEvent(3, 42, 0, 88, 1, List.of(detail)), 397911882L);
        assertThat(table.players()).extracting(TableSnapshot.Player::nickname).contains("Alice");
        table = machine.applyGameSo(table, event(1, 1, -1, -1, 0, -1,
                range(1, 17), List.of(), 0));
        assertThat(table.players()).filteredOn(player -> player.seatId() == 1)
                .extracting(TableSnapshot.Player::nickname).containsExactly("Alice");
    }

    @Test
    void preservesPlayerProfileAndUpdatesBeansFromRoomEvents() {
        TableSnapshot table = new TableSnapshot(1260, 0, 88, 0, -1, -1,
                List.of(), List.of(), List.of(), List.of(), GameStage.SEATED, List.of(), 1, "已坐下");
        var complete = new cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec.PlayerDetail(
                42, 998L, 5, 88, 2, "夏日橘子", "https://img.example/avatar.png", 8800);
        table = machine.applyRoomEvent(table,
                new RoomEvent(3, 42, 0, 88, 2, List.of(complete)), 397911882L);

        var partial = new cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec.PlayerDetail(
                42, 998L, 5, 88, 2, "", "", 0);
        table = machine.applyRoomEvent(table,
                new RoomEvent(7, 42, 0, 88, 2, List.of(partial)), 397911882L);
        table = machine.applyRoomEvent(table,
                new RoomEvent(11, 42, 0, 88, 2, List.of(), 9900), 397911882L);

        assertThat(table.players()).singleElement().satisfies(player -> {
            assertThat(player.nickname()).isEqualTo("夏日橘子");
            assertThat(player.avatarUrl()).isEqualTo("https://img.example/avatar.png");
            assertThat(player.beans()).isEqualTo(9900);
        });
    }

    @Test
    void exposesDetailedSettlementForEverySeat() {
        List<TableSnapshot.Player> players = List.of(
                new TableSnapshot.Player(1, "我", 0, 3, 10000, false),
                new TableSnapshot.Player(2, "玩家二", 1, 2, 9000, false),
                new TableSnapshot.Player(3, "玩家三", 2, 1, 10000, false));
        TableSnapshot table = new TableSnapshot(1260, 0, 88, 0, -1, 0,
                List.of(), List.of(), List.of(), players, GameStage.PLAYING, List.of(), 30, "牌局中");
        table = machine.applyRoomEvent(table,
                new RoomEvent(11, 2L, 0, 88, 1, List.of(), 8400, -600), 1L);
        var settlement = new cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec.Settlement(
                1, List.of(1200L, -600L, -600L), List.of(7L, 8L, 9L),
                List.of(30L, 15L, 15L), List.of(List.of(), List.of(3, 16), List.of(54)));
        var event = new GameSoEvent(0, -1, 5, 0, -1, -1, 0,
                0, 0, -1, 0, List.of(), List.of(), List.of(), settlement);

        table = machine.applyGameSo(table, event);

        assertThat(table.stage()).isEqualTo(GameStage.SETTLED);
        assertThat(table.settlement().players()).extracting("beanChange")
                .containsExactly(1200L, -600L, -600L);
        assertThat(table.players()).extracting("beans").containsExactly(11200L, 8400L, 9400L);
        assertThat(table.players()).extracting("cardCount").containsExactly(0, 2, 1);

        table = machine.applyRoomEvent(table,
                new RoomEvent(11, 2L, 0, 88, 1, List.of(), 8300), 1L);
        assertThat(table.settlement().players()).filteredOn(result -> result.seatId() == 1)
                .extracting("beans").containsExactly(8300L);
    }

    @Test
    void movesLocalTableToIdleWhenAccountIsKicked() {
        TableSnapshot table = new TableSnapshot(1260, 0, 88, 0, 0, -1,
                List.of(), List.of(), List.of(), List.of(), GameStage.PLAYING,
                List.of(TableAction.PLAY), 1, "轮到你出牌");

        table = machine.applyRoomEvent(table, new RoomEvent(12, 397911882L, 0, 88, 0), 397911882L);

        assertThat(table.stage()).isEqualTo(GameStage.IDLE);
        assertThat(table.availableActions()).isEmpty();
        assertThat(table.prompt()).isEqualTo("账号已在其他设备登录");
    }

    @Test
    void opponentReadyAfterSettlementKeepsTheLocalReadyAction() {
        TableSnapshot table = new TableSnapshot(1260, 0, 88, 1, -1, -1,
                List.of(), List.of(), List.of(), List.of(), GameStage.SETTLED,
                List.of(TableAction.READY, TableAction.CHANGE_TABLE, TableAction.LEAVE_ROOM),
                1, "等待下一局");

        table = machine.applyRoomEvent(table, new RoomEvent(6, 998L, 0, 88, 2), 397911882L);

        assertThat(table.stage()).isEqualTo(GameStage.SETTLED);
        assertThat(table.availableActions()).containsExactly(
                TableAction.READY, TableAction.CHANGE_TABLE, TableAction.LEAVE_ROOM);
        assertThat(table.prompt()).isEqualTo("对手已准备，你可以准备下一局");
    }

    @Test
    void localReadyEventMovesOnlyTheLocalPlayerToWaitingForDeal() {
        TableSnapshot table = new TableSnapshot(1260, 0, 88, 1, -1, -1,
                List.of(), List.of(), List.of(), List.of(), GameStage.SETTLED,
                List.of(TableAction.READY, TableAction.CHANGE_TABLE, TableAction.LEAVE_ROOM),
                1, "等待下一局");

        table = machine.applyRoomEvent(table,
                new RoomEvent(6, 397911882L, 0, 88, 1), 397911882L);

        assertThat(table.stage()).isEqualTo(GameStage.READY);
        assertThat(table.availableActions()).isEmpty();
        assertThat(table.prompt()).isEqualTo("你已准备，等待发牌");
    }

    @Test
    void tableTimeoutExposesDedicatedChangeTableAndLeaveState() {
        TableSnapshot table = new TableSnapshot(1260, 0, 88, 1, -1, -1,
                List.of(), List.of(), List.of(), List.of(), GameStage.SEATED, List.of(), 1, "等待玩家");

        table = machine.applyGameSo(table, event(16, -1, -1, -1, 0, -1,
                List.of(), List.of(), 0));

        assertThat(table.stage()).isEqualTo(GameStage.TIMED_OUT);
        assertThat(table.availableActions()).containsExactly(TableAction.CHANGE_TABLE, TableAction.LEAVE_ROOM);
        assertThat(table.prompt()).isEqualTo("本桌等待超时，请换桌继续");
    }

    @Test
    void rejectedGameSoDoesNotMutateHandTurnOrActions() {
        TableSnapshot table = new TableSnapshot(1260, 0, 88, 0, 0, -1,
                List.of(cc.superbaby.happy4j.domain.Card.fromCode(1)), List.of(), List.of(), List.of(),
                GameStage.PLAYING, List.of(TableAction.PLAY), 1, "轮到你出牌");
        GameSoEvent rejected = new GameSoEvent(0, 0, 4, 3, 0, 2, 0,
                0, 0, -1, 0, List.of(), List.of());

        assertThat(machine.applyGameSo(table, rejected)).isEqualTo(table);
    }

    @Test
    void sortsHandByDdzRankAndKeepsLastEffectivePlayAcrossPasses() {
        TableSnapshot table = new TableSnapshot(1260, 0, 88, 2, -1, -1,
                List.of(), List.of(), List.of(), List.of(), GameStage.DEALING, List.of(), 1, "发牌");
        table = machine.applyGameSo(table, event(1, 2, -1, -1, 0, -1,
                List.of(1, 2, 3, 16, 53), List.of(), 0));
        assertThat(table.hand()).extracting("code").containsExactly(53, 2, 1, 3, 16);

        table = machine.applyGameSo(table, event(7, -1, -1, -1, 0, 2,
                List.of(), List.of(54, 4, 17), 0));
        assertThat(table.hand()).extracting("code")
                .containsExactly(54, 53, 2, 1, 4, 17, 3, 16);

        table = machine.applyGameSo(table, new GameSoEvent(0, -1, 4, 0, 1, 2, 0,
                1, 10, -1, 0, List.of(10), List.of()));
        assertThat(table.lastPlay()).extracting("code").containsExactly(10);
        table = machine.applyGameSo(table, event(4, -1, 2, 0, 0, -1,
                List.of(), List.of(), 0));
        assertThat(table.lastPlay()).extracting("code").containsExactly(10);
        table = machine.applyGameSo(table, event(4, -1, 0, 1, 0, -1,
                List.of(), List.of(), 0));
        assertThat(table.lastPlay()).isEmpty();
    }

    @Test
    void retainsACompleteLongRoundHistoryForDouZeroFeatures() {
        TableSnapshot table = new TableSnapshot(1260, 0, 88, 0, 0, 1,
                range(1, 17).stream().map(cc.superbaby.happy4j.domain.Card::fromCode).toList(),
                List.of(), List.of(), List.of(), GameStage.PLAYING,
                List.of(TableAction.PLAY, TableAction.PASS), 1, "牌局中");

        for (int index = 0; index < 120; index++) {
            int actor = index % 3;
            int next = (actor + 1) % 3;
            table = machine.applyGameSo(table, event(4, -1, actor, next, 0, -1,
                    List.of(), List.of(), 0));
        }

        assertThat(table.actionHistory()).hasSize(120);
        assertThat(table.actionHistory()).allMatch(entry -> entry.action().equals("不出"));
    }

    @Test
    void repeatedEndMessagesProduceOneSettlementMarkerAndNextRoundClearsHistory() {
        TableSnapshot table = new TableSnapshot(1260, 0, 88, 0, 0, 1,
                range(1, 17).stream().map(cc.superbaby.happy4j.domain.Card::fromCode).toList(),
                List.of(), List.of(), List.of(), GameStage.PLAYING,
                List.of(TableAction.PLAY, TableAction.PASS), 1, "牌局中");

        table = machine.applyGameSo(table, event(4, -1, 1, 2, 0, -1,
                List.of(19), List.of(), 0));
        table = machine.applyGameSo(table, event(102, -1, -1, -1, 0, -1,
                List.of(), List.of(), 0));
        var firstEnd = table.actionHistory().get(table.actionHistory().size() - 1);
        table = machine.applyGameSo(table, event(102, -1, -1, -1, 0, -1,
                List.of(), List.of(), 0));
        var settlement = new cc.superbaby.happy4j.protocol.game.MiniGameGameSoCodec.Settlement(
                1, List.of(-100L, 200L, -100L), List.of(), List.of(),
                List.of(List.of(4), List.of(), List.of(8)));
        table = machine.applyGameSo(table, new GameSoEvent(0, -1, 5, 0, -1, -1, 0,
                0, 0, -1, 0, List.of(), List.of(), List.of(), settlement));

        assertThat(table.actionHistory()).filteredOn(entry -> entry.action().equals("本局结束"))
                .containsExactly(firstEnd);
        assertThat(table.settlement().players()).hasSize(3);

        table = machine.applyGameSo(table, event(101, -1, -1, -1, 0, -1,
                List.of(), List.of(), 0));
        assertThat(table.actionHistory()).isEmpty();
        table = machine.applyGameSo(table, event(1, 0, -1, -1, 0, -1,
                range(1, 17), List.of(), 0));
        assertThat(table.actionHistory()).isEmpty();
        assertThat(table.settlement().available()).isFalse();
    }

    private static GameSoEvent event(int subtype, int seat, int actor, int next, int decision,
                                     int landlord, List<Integer> cards, List<Integer> bottom, int multiplier) {
        return new GameSoEvent(0, seat, subtype, 0, actor, next, decision,
                0, 0, landlord, multiplier, cards, bottom);
    }

    private static List<Integer> range(int start, int end) {
        return java.util.stream.IntStream.rangeClosed(start, end).boxed().toList();
    }
}
