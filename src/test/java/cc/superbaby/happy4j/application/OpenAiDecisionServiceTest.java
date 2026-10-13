package cc.superbaby.happy4j.application;

import cc.superbaby.happy4j.domain.Card;
import cc.superbaby.happy4j.domain.ClientSession;
import cc.superbaby.happy4j.domain.GameStage;
import cc.superbaby.happy4j.domain.TableAction;
import cc.superbaby.happy4j.domain.TableSnapshot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiDecisionServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsOpenAiCompatibleChatRequestAndSelectsOnlyAValidatedCandidate() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"model":"expert-ddz","choices":[{"message":{"role":"assistant","content":"{\\"choice\\":0,\\"reason\\":\\"用最小单牌压住地主\\",\\"confidence\\":0.86}"}}],"usage":{"total_tokens":321}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        UUID userId = UUID.randomUUID();
        AiConfigurationService configurations = mock(AiConfigurationService.class);
        when(configurations.provider(userId)).thenReturn(new AiConfigurationService.ProviderConfiguration(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"),
                "expert-ddz", "secret-key"));
        var service = new OpenAiDecisionService(configurations,
                HttpClient.newBuilder().build(), new JsonMapper());
        ClientSession session = new ClientSession(UUID.randomUUID(), userId);
        session.table(table());
        var payload = new JsonMapper().readTree("{\"phase\":\"PLAY\",\"candidates\":[[4],[]]}");

        var result = service.decide(userId, session, payload).get(5, TimeUnit.SECONDS);

        assertThat(result.action()).isEqualTo("PLAY");
        assertThat(result.cards()).containsExactly(4);
        assertThat(result.reason()).isEqualTo("用最小单牌压住地主");
        assertThat(result.confidence()).isEqualTo(0.86);
        assertThat(result.totalTokens()).isEqualTo(321);
        assertThat(authorization.get()).isEqualTo("Bearer secret-key");
        assertThat(requestBody.get()).contains("expert-ddz", "options", "messages").doesNotContain("secret-key");
    }

    @Test
    void retriesBusyProvidersAndReturnsAnActionableMessage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            byte[] response = """
                    {"error":{"code":"1305","message":"该模型当前访问量过大，请您稍后再试"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        UUID userId = UUID.randomUUID();
        AiConfigurationService configurations = mock(AiConfigurationService.class);
        when(configurations.provider(userId)).thenReturn(new AiConfigurationService.ProviderConfiguration(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"),
                "glm-4.7-flash", "secret-key"));
        var service = new OpenAiDecisionService(configurations,
                HttpClient.newBuilder().build(), new JsonMapper());

        assertThatThrownBy(() -> service.test(userId).get(6, TimeUnit.SECONDS))
                .hasRootCauseMessage("当前模型访问量过大，请稍后重试或更换模型");
        assertThat(requests).hasValue(3);
    }

    @Test
    void skipsProviderWhenOnlyOneLegalMoveExists() throws Exception {
        UUID userId = UUID.randomUUID();
        AiConfigurationService configurations = mock(AiConfigurationService.class);
        var service = new OpenAiDecisionService(configurations,
                HttpClient.newBuilder().build(), new JsonMapper());
        ClientSession session = new ClientSession(UUID.randomUUID(), userId);
        session.table(table());
        var payload = new JsonMapper().readTree("{\"phase\":\"PLAY\",\"candidates\":[[]]}");

        var result = service.decide(userId, session, payload).get(2, TimeUnit.SECONDS);

        assertThat(result.action()).isEqualTo("PASS");
        assertThat(result.model()).isEqualTo("RULE_ENGINE");
        assertThat(result.totalTokens()).isZero();
        assertThat(result.finishReason()).isEqualTo("not_applicable");
        org.mockito.Mockito.verifyNoInteractions(configurations);
    }

    @Test
    void acceptsNamespacedOpenRouterModelAndCapturesFinishReason() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"model":"openai/gpt-6-luna","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\\"choice\\":0,\\"reason\\":\\"拦截地主\\",\\"confidence\\":0.9}"}}],"usage":{"total_tokens":84}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        UUID userId = UUID.randomUUID();
        AiConfigurationService configurations = mock(AiConfigurationService.class);
        when(configurations.provider(userId)).thenReturn(new AiConfigurationService.ProviderConfiguration(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"),
                "openai/gpt-6-luna", "test-key"));
        var service = new OpenAiDecisionService(configurations,
                HttpClient.newBuilder().build(), new JsonMapper());
        ClientSession session = new ClientSession(UUID.randomUUID(), userId);
        session.table(table());
        var payload = new JsonMapper().readTree("{\"phase\":\"PLAY\",\"candidates\":[[4],[]]}");

        var result = service.decide(userId, session, payload).get(5, TimeUnit.SECONDS);

        assertThat(result.model()).isEqualTo("openai/gpt-6-luna");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(requestBody.get()).contains("openai/gpt-6-luna", "\"max_tokens\":1024")
                .doesNotContain("test-key");
    }

    private static TableSnapshot table() {
        List<TableSnapshot.Player> players = List.of(
                new TableSnapshot.Player(1, "我", 0, 1, 1000, false),
                new TableSnapshot.Player(2, "地主", 1, 8, 1000, false),
                new TableSnapshot.Player(3, "队友", 2, 7, 1000, false));
        return new TableSnapshot(1260, 0, 88, 0, 0, 1,
                List.of(Card.fromCode(4)), List.of(Card.fromCode(3)), 1, 1, 1,
                List.of(), players, List.of(), GameStage.PLAYING,
                List.of(TableAction.PLAY, TableAction.PASS), 1, false, "轮到你出牌");
    }
}
