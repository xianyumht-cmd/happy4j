package cc.superbaby.happy4j.application;

import cc.superbaby.happy4j.domain.Card;
import cc.superbaby.happy4j.domain.ClientSession;
import cc.superbaby.happy4j.domain.GameStage;
import cc.superbaby.happy4j.domain.TableAction;
import cc.superbaby.happy4j.domain.TableSnapshot;
import cc.superbaby.happy4j.protocol.game.MiniGameActionProtocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Service
public class OpenAiDecisionService {
    private static final Logger log = LoggerFactory.getLogger(OpenAiDecisionService.class);
    @Value("${happy4j.ai.max-output-tokens:${HAPPY4J_AI_MAX_OUTPUT_TOKENS:1024}}")
    private int maxOutputTokens = 1024;
    private static final String SYSTEM_PROMPT = """
            你是精通经典三人斗地主的决策模型。你的唯一目标是最大化当前玩家本局获胜概率。
            你只能使用请求里给出的公开信息，不得假设对手暗牌。农民必须考虑队友配合，避免无意义压队友；
            地主需要控制出牌权并优先形成可连续出完的牌路。明确区分自己是地主还是农民；
            农民面对地主即将出完牌时必须考虑主动压制；当队友掌握出牌权且没有迫切威胁时可以不出。
            只凭可验证的牌局信息决策，不得猜测未知手牌。必须从options中选择一个choice，禁止创造新动作。
            只返回一行JSON：{"choice":整数,"reason":"不超过80字的中文理由","confidence":0到1的小数}
            """;

    private final AiConfigurationService configurations;
    private final HttpClient http;
    private final ObjectMapper json;
    private final MiniGameActionProtocol rules = new MiniGameActionProtocol();

    public OpenAiDecisionService(AiConfigurationService configurations, HttpClient http, ObjectMapper json) {
        this.configurations = configurations;
        this.http = http;
        this.json = json;
    }

    public CompletableFuture<TestResult> test(UUID userId) {
        var provider = configurations.provider(userId);
        long started = System.nanoTime();
        return call(provider, "你是连接测试助手，只返回JSON。",
                "请返回 {\"choice\":0,\"reason\":\"连接成功\",\"confidence\":1}",
                Duration.ofSeconds(12), 3).thenApply(reply -> new TestResult(
                reply.model(), elapsedMillis(started), reply.totalTokens(), "连接成功"));
    }

    public CompletableFuture<DecisionResult> decide(UUID userId, ClientSession session, JsonNode payload) {
        TableSnapshot table = session.table();
        if (table == null) return CompletableFuture.failedFuture(new IllegalStateException("牌桌状态尚未同步"));
        String phase = payload.path("phase").asString().trim().toUpperCase(Locale.ROOT);
        List<Option> options = options(phase, session, table, payload.path("candidates"));
        if (options.size() == 1) {
            Option only = options.get(0);
            return CompletableFuture.completedFuture(new DecisionResult(0, only.action(),
                    only.cardCodes(), "当前只有一个合法选择", 1, "RULE_ENGINE", 0, 0, "not_applicable"));
        }
        if (phase.equals("PLAY") && (table.seatId() < 0 || table.seatId() > 2
                || table.landlordSeatId() < 0 || table.landlordSeatId() > 2
                || table.players().stream().noneMatch(player -> player.seatId() == table.landlordSeatId())))
            return CompletableFuture.failedFuture(new IllegalStateException("地主身份或座位状态尚未同步"));
        var provider = configurations.provider(userId);
        String prompt = prompt(phase, table, options);
        Duration timeout = switch (phase) {
            case "DOUBLE" -> Duration.ofSeconds(4);
            case "ORDER" -> Duration.ofSeconds(8);
            default -> Duration.ofSeconds(12);
        };
        long started = System.nanoTime();
        return call(provider, SYSTEM_PROMPT, prompt, timeout, 2).thenApply(reply -> {
            JsonNode decision = parseDecision(reply.content());
            int choice = decision.path("choice").asInt(-1);
            if (choice < 0 || choice >= options.size())
                throw new IllegalStateException("大模型返回了无效选项");
            Option selected = options.get(choice);
            String reason = decision.path("reason").asString("大模型选择了当前胜率最高的动作");
            if (reason.length() > 160) reason = reason.substring(0, 160);
            double confidence = Math.max(0, Math.min(1, decision.path("confidence").asDouble(0.5)));
            return new DecisionResult(choice, selected.action(), selected.cardCodes(), reason, confidence,
                    reply.model(), elapsedMillis(started), reply.totalTokens(), reply.finishReason());
        });
    }

    private List<Option> options(String phase, ClientSession session,
                                 TableSnapshot table, JsonNode candidatesNode) {
        return switch (phase) {
            case "ORDER" -> orderOptions(table);
            case "DOUBLE" -> doubleOptions(session, table);
            case "PLAY" -> playOptions(table, candidatesNode);
            default -> throw new IllegalArgumentException("未知大模型决策阶段");
        };
    }

    private static List<Option> orderOptions(TableSnapshot table) {
        boolean available = table.stage() == GameStage.CALLING_LANDLORD
                && table.availableActions().stream().anyMatch(action -> action == TableAction.CALL_LANDLORD
                || action == TableAction.ROB_LANDLORD || action == TableAction.PASS_LANDLORD
                || action == TableAction.PASS_ROB);
        if (!available) throw new IllegalStateException("当前不在叫抢地主阶段");
        return List.of(new Option("ACCEPT", List.of()), new Option("DECLINE", List.of()));
    }

    private static List<Option> doubleOptions(ClientSession session, TableSnapshot table) {
        if (table.stage() != GameStage.DOUBLING)
            throw new IllegalStateException("当前不在加倍阶段");
        List<Option> values = new ArrayList<>();
        int superItemId = table.superDoubleItemId() > 0 ? table.superDoubleItemId() : 1030108;
        if (table.availableActions().contains(TableAction.SUPER_DOUBLE) && session.itemCount(superItemId) > 0)
            values.add(new Option("SUPER_DOUBLE", List.of()));
        if (table.availableActions().contains(TableAction.DOUBLE))
            values.add(new Option("DOUBLE", List.of()));
        if (table.availableActions().contains(TableAction.NO_DOUBLE))
            values.add(new Option("NO_DOUBLE", List.of()));
        if (values.isEmpty()) throw new IllegalStateException("服务器当前未开放加倍选择");
        return List.copyOf(values);
    }

    private List<Option> playOptions(TableSnapshot table, JsonNode candidatesNode) {
        if (table.stage() != GameStage.PLAYING || table.turnSeatId() != table.seatId()
                || !table.availableActions().contains(TableAction.PLAY))
            throw new IllegalStateException("当前不是本机出牌回合");
        if (!candidatesNode.isArray() || candidatesNode.isEmpty() || candidatesNode.size() > 200)
            throw new IllegalArgumentException("合法动作候选数量异常");
        Set<String> seen = new LinkedHashSet<>();
        List<Option> options = new ArrayList<>();
        for (JsonNode candidate : candidatesNode) {
            List<Integer> cards = new ArrayList<>();
            if (candidate.isArray()) candidate.forEach(card -> cards.add(card.asInt()));
            if (cards.size() > 20 || cards.stream().distinct().count() != cards.size()) continue;
            List<Integer> immutable = List.copyOf(cards);
            String key = immutable.stream().sorted().map(String::valueOf)
                    .reduce((left, right) -> left + "," + right).orElse("PASS");
            if (!seen.add(key) || !validCandidate(table, immutable)) continue;
            options.add(new Option(immutable.isEmpty() ? "PASS" : "PLAY", immutable));
        }
        if (options.isEmpty()) throw new IllegalArgumentException("没有可供大模型选择的合法动作");
        return List.copyOf(options);
    }

    private boolean validCandidate(TableSnapshot table, List<Integer> cards) {
        if (cards.isEmpty()) return table.availableActions().contains(TableAction.PASS);
        Set<Integer> hand = table.hand().stream().map(Card::code).collect(java.util.stream.Collectors.toSet());
        if (!hand.containsAll(cards)) return false;
        try {
            var combination = rules.classifyCards(cards);
            return table.lastPlay().isEmpty() || table.lastPlaySeatId() == table.seatId()
                    || rules.canBeat(combination, cards.size(), table.lastPlayType(), table.lastPlayRank(),
                    table.lastPlay().size());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String prompt(String phase, TableSnapshot table, List<Option> options) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("phase", phase);
            state.put("mySeat", table.seatId());
            state.put("myRole", table.landlordSeatId() < 0 ? "UNDETERMINED"
                    : table.seatId() == table.landlordSeatId() ? "LANDLORD" : "FARMER");
            state.put("landlordSeat", table.landlordSeatId());
            state.put("turnSeat", table.turnSeatId());
            state.put("teammateSeats", table.landlordSeatId() < 0 || table.seatId() == table.landlordSeatId()
                    ? List.of() : table.players().stream().map(TableSnapshot.Player::seatId)
                    .filter(seat -> seat != table.landlordSeatId() && seat != table.seatId()).toList());
            state.put("multiplier", table.multiplier());
            state.put("myHand", cards(table.hand()));
            state.put("bottomCards", cards(table.bottomCards()));
            state.put("lastPlaySeat", table.lastPlaySeatId());
            state.put("lastPlayType", table.lastPlayType());
            state.put("lastPlay", cards(table.lastPlay()));
            state.put("players", table.players().stream().map(player -> Map.of(
                    "seat", player.seatId(),
                    "role", player.seatId() == table.landlordSeatId() ? "LANDLORD" : "FARMER",
                    "cardsLeft", player.cardCount())).toList());
            state.put("recentActions", table.actionHistory().stream()
                    .skip(Math.max(0, table.actionHistory().size() - 30L))
                    .map(action -> Map.of("seat", action.seatId(), "action", action.action(),
                            "cards", cards(action.cards()))).toList());
            state.put("options", java.util.stream.IntStream.range(0, options.size()).mapToObj(index -> {
                Option option = options.get(index);
                return Map.of("choice", index, "action", option.action(), "cards", cardCodes(option.cards()),
                        "ranks", option.cards().stream().map(Card::rank).toList());
            }).toList());
            return "请根据以下牌局状态选择胜率最高的choice：\n" + json.writeValueAsString(state);
        } catch (Exception failure) {
            throw new IllegalStateException("构造大模型牌局上下文失败", failure);
        }
    }

    private CompletableFuture<ModelReply> call(AiConfigurationService.ProviderConfiguration provider,
                                               String system, String user, Duration timeout,
                                               int maxAttempts) {
        return call(provider, system, user, timeout, maxAttempts, 1);
    }

    private CompletableFuture<ModelReply> call(AiConfigurationService.ProviderConfiguration provider,
                                               String system, String user, Duration timeout,
                                               int maxAttempts, int attempt) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", provider.model());
            body.put("stream", false);
            body.put("max_tokens", Math.max(256, Math.min(4096, maxOutputTokens)));
            body.put("messages", List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", user)));
            if (isBigModel(provider.endpoint()))
                body.put("thinking", Map.of("type", "disabled"));
            HttpRequest.Builder builder = HttpRequest.newBuilder(provider.endpoint())
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            if (!provider.apiKey().isBlank()) builder.header("Authorization", "Bearer " + provider.apiKey());
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            long started = System.nanoTime();
            CompletableFuture<ModelReply> requestFuture = http.sendAsync(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> parseReply(response.statusCode(), response.body(), provider.model()))
                    .whenComplete((reply, failure) -> {
                        long elapsed = elapsedMillis(started);
                        if (failure == null)
                            log.info("AI provider request succeeded: host={}, model={}, attempt={}, latencyMs={}",
                                    provider.endpoint().getHost(), provider.model(), attempt, elapsed);
                        else
                            log.warn("AI provider request failed: host={}, model={}, attempt={}, latencyMs={}, reason={}",
                                    provider.endpoint().getHost(), provider.model(), attempt, elapsed,
                                    rootCause(failure).getMessage());
                    });
            return requestFuture.handle((reply, failure) -> {
                if (failure == null) return CompletableFuture.completedFuture(reply);
                Throwable root = rootCause(failure);
                if (attempt < maxAttempts && root instanceof ProviderCallException providerFailure
                        && providerFailure.retryable()) {
                    long delay = 600L * attempt;
                    return CompletableFuture.runAsync(() -> { },
                                    CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                            .thenCompose(ignored -> call(provider, system, user, timeout,
                                    maxAttempts, attempt + 1));
                }
                return CompletableFuture.<ModelReply>failedFuture(root);
            }).thenCompose(future -> future);
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private ModelReply parseReply(int status, String body, String configuredModel) {
        try {
            JsonNode root = json.readTree(body);
            if (status < 200 || status >= 300) {
                JsonNode error = root.path("error");
                String code = error.path("code").asString();
                String message = error.path("message").asString();
                throw new ProviderCallException(status, providerMessage(status, code, message));
            }
            JsonNode message = root.path("choices").path(0).path("message").path("content");
            String content = content(message);
            String finishReason = root.path("choices").path(0).path("finish_reason").asString("");
            if (content.isBlank()) throw new IllegalStateException(
                    finishReason.equals("length") ? "大模型输出达到token上限，未返回有效决策"
                            : "大模型未返回决策内容");
            return new ModelReply(content, root.path("model").asString(configuredModel),
                    root.path("usage").path("total_tokens").asInt(), finishReason);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("解析大模型响应失败", failure);
        }
    }

    private static String providerMessage(int status, String code, String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (status == 401 || code.equals("1000")
                || normalized.contains("api key") && normalized.contains("invalid"))
            return "API Key 校验失败，请检查后重新保存";
        if (status == 403) return "当前 API Key 无权调用这个模型";
        if (status == 404) return "模型或 API URL 不存在，请检查配置";
        if (status == 429 && (code.equals("1305")
                || message != null && message.contains("访问量过大")))
            return "当前模型访问量过大，请稍后重试或更换模型";
        if (status == 429) return "大模型请求过于频繁，请稍后重试";
        if (code.equals("1113") || message != null && message.contains("余额不足"))
            return "大模型账户余额不足，请先补充额度";
        if (status >= 500) return "大模型服务暂时繁忙，请稍后重试";
        return "大模型连接失败，请检查 URL、模型和 API Key";
    }

    private static boolean isBigModel(java.net.URI endpoint) {
        String host = endpoint.getHost();
        return host != null && (host.equalsIgnoreCase("open.bigmodel.cn")
                || host.toLowerCase(Locale.ROOT).endsWith(".bigmodel.cn"));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while ((root instanceof CompletionException || root.getCause() != null)
                && root.getCause() != null) root = root.getCause();
        return root;
    }

    private static String content(JsonNode node) {
        if (!node.isArray()) return node.asString();
        StringBuilder value = new StringBuilder();
        node.forEach(part -> {
            String text = part.path("text").asString();
            if (!text.isBlank()) value.append(text);
        });
        return value.toString();
    }

    private JsonNode parseDecision(String content) {
        try {
            String value = content.trim();
            int begin = value.indexOf('{');
            int end = value.lastIndexOf('}');
            if (begin < 0 || end < begin) throw new IllegalStateException("大模型没有返回JSON决策");
            return json.readTree(value.substring(begin, end + 1));
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("大模型JSON决策格式错误", failure);
        }
    }

    private static List<Map<String, Object>> cards(List<Card> cards) {
        return cards.stream().map(card -> Map.<String, Object>of(
                "code", card.code(), "rank", card.rank(), "suit", card.suit())).toList();
    }

    private static List<Integer> cardCodes(List<Card> cards) {
        return cards.stream().map(Card::code).toList();
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private record Option(String action, List<Integer> cardCodes) {
        List<Card> cards() {
            return cardCodes.stream().map(Card::fromCode).toList();
        }
    }

    private record ModelReply(String content, String model, int totalTokens, String finishReason) {
    }

    private static final class ProviderCallException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        private ProviderCallException(int status, String message) {
            super(message);
            this.status = status;
        }

        private boolean retryable() {
            return status == 429 || status >= 500;
        }
    }

    public record DecisionResult(int choice, String action, List<Integer> cards,
                                 String reason, double confidence, String model,
                                 long latencyMs, int totalTokens, String finishReason) {
    }

    public record TestResult(String model, long latencyMs, int totalTokens, String message) {
    }
}
