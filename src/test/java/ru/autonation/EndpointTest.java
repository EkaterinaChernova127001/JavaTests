package ru.autonation;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@Epic("Тестирование AQA приложения")
@Feature("POST /endpoint")
class EndpointContractTest {
    private static final String API_KEY = "qazWSXedc";
    private static final String NO_KEY_TOKEN = "00000000000000000000000000000001";
    private static final String INVALID_KEY_TOKEN = "00000000000000000000000000000002";
    private static final String INVALID_FORMAT_TOKEN = "G123456789ABCDEF0123456789ABCDEF";
    private static final String UNKNOWN_ACTION_TOKEN = "00000000000000000000000000000003";
    private static final String ACTION_BEFORE_LOGIN_TOKEN = "00000000000000000000000000000004";
    private static final String SESSION_TOKEN = "00000000000000000000000000000005";
    private static final String FAILED_LOGIN_TOKEN = "00000000000000000000000000000006";
    private static final String FAILED_ACTION_TOKEN = "00000000000000000000000000000007";
    private static final URI ENDPOINT = URI.create("http://localhost:8080/endpoint");
    private static final Pattern OK_RESULT = Pattern.compile("\\\"result\\\"\\s*:\\s*\\\"OK\\\"");
    private static final Pattern ERROR_RESULT = Pattern.compile("\\\"result\\\"\\s*:\\s*\\\"ERROR\\\"");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static WireMockServer mockServer;
    private static Process appProcess;

    @BeforeAll
    static void startApplicationAndExternalService() throws Exception {
        if (isPortOpen(8080)) {
            throw new IllegalStateException("Порт 8080 уже занят. Останови ранее запущенное приложение (Ctrl+C) и повтори mvn test.");
        }
        mockServer = new WireMockServer(wireMockConfig().dynamicPort());
        mockServer.start();

        Path projectDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path jar = projectDir.resolve("..")
                .resolve("aqa")
                .resolve("internal-0.0.1-SNAPSHOT.jar")
                .normalize();
        if (!Files.isRegularFile(jar)) {
            mockServer.stop();
            throw new IllegalStateException("Не найден JAR приложения: " + jar);
        }

        Path target = projectDir.resolve("target");
        Files.createDirectories(target);
        Path log = target.resolve("application.log");
        appProcess = new ProcessBuilder(
                "java",
                "-Dsecret=" + API_KEY,
                "-Dmock=http://localhost:" + mockServer.port() + "/",
                "-jar",
                jar.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();

        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline && !isPortOpen(8080)) {
            if (!appProcess.isAlive()) {
                mockServer.stop();
                throw new IllegalStateException("Приложение завершилось при запуске. Лог: " + log);
            }
            Thread.sleep(250);
        }
        if (!isPortOpen(8080)) {
            appProcess.destroyForcibly();
            mockServer.stop();
            throw new IllegalStateException("Приложение не открыло порт 8080 за 40 секунд. Лог: " + log);
        }
    }

    @AfterAll
    static void stopApplicationAndExternalService() {
        if (appProcess != null && appProcess.isAlive()) {
            appProcess.destroy();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @BeforeEach
    void clearExternalServiceStubs() {
        mockServer.resetAll();
    }

    @Test
    @Story("Проверка доступа по API-ключу")
    @DisplayName("Отклоняет запрос без API-ключа")
    void rejectsRequestWithoutApiKey() throws Exception {
        HttpResponse<String> response = post(NO_KEY_TOKEN, "LOGIN", null);

        assertError(response);
        assertEquals(0, mockServer.getAllServeEvents().size(), "Без API-ключа внешний сервис не должен вызываться");
    }

    @Test
    @Story("Проверка доступа по API-ключу")
    @DisplayName("Отклоняет запрос с неверным API-ключом")
    void rejectsRequestWithInvalidApiKey() throws Exception {
        HttpResponse<String> response = post(INVALID_KEY_TOKEN, "LOGIN", "wrong-key");

        assertError(response);
        assertEquals(0, mockServer.getAllServeEvents().size(), "С неверным ключом внешний сервис не должен вызываться");
    }

    @Test
    @Story("Проверка входных данных")
    @DisplayName("Отклоняет токен, который не соответствует формату приложения")
    void rejectsInvalidToken() throws Exception {
        HttpResponse<String> response = post(INVALID_FORMAT_TOKEN, "LOGIN", API_KEY);

        assertError(response);
        assertEquals(0, mockServer.getAllServeEvents().size(), "Некорректный токен не должен отправляться наружу");
    }

    @Test
    @Story("Проверка входных данных")
    @DisplayName("Отклоняет неизвестное действие")
    void rejectsUnknownAction() throws Exception {
        HttpResponse<String> response = post(UNKNOWN_ACTION_TOKEN, "UNKNOWN", API_KEY);

        assertError(response);
        assertEquals(0, mockServer.getAllServeEvents().size(), "Неизвестное действие не должно вызывать внешний сервис");
    }

    @Test
    @Story("Управление сессией")
    @DisplayName("Не выполняет ACTION до успешного LOGIN")
    void actionRequiresLogin() throws Exception {
        HttpResponse<String> response = post(ACTION_BEFORE_LOGIN_TOKEN, "ACTION", API_KEY);

        assertError(response);
        assertEquals(0, mockServer.getAllServeEvents().size(), "Без входа запрос /doAction не должен отправляться");
    }

    @Test
    @Story("Управление сессией")
    @DisplayName("LOGIN, ACTION и LOGOUT управляют токеном сессии")
    void loginActionAndLogoutManageSession() throws Exception {
        mockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/auth")).willReturn(aResponse().withStatus(200).withBody("любое тело")));
        mockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/doAction")).willReturn(aResponse().withStatus(200).withBody("другой произвольный ответ")));

        HttpResponse<String> login = post(SESSION_TOKEN, "LOGIN", API_KEY);
        assertOk(login);
        mockServer.verify(postRequestedFor(urlEqualTo("/auth"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(equalTo("token=" + SESSION_TOKEN)));

        HttpResponse<String> action = post(SESSION_TOKEN, "ACTION", API_KEY);
        assertOk(action);
        mockServer.verify(postRequestedFor(urlEqualTo("/doAction"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(equalTo("token=" + SESSION_TOKEN)));

        HttpResponse<String> logout = post(SESSION_TOKEN, "LOGOUT", API_KEY);
        assertOk(logout);

        HttpResponse<String> actionAfterLogout = post(SESSION_TOKEN, "ACTION", API_KEY);
        assertError(actionAfterLogout);
        mockServer.verify(1, postRequestedFor(urlEqualTo("/auth")));
        mockServer.verify(1, postRequestedFor(urlEqualTo("/doAction")));
    }

    @Test
    @Story("Обработка ошибок внешнего сервиса")
    @DisplayName("Возвращает ошибку, если внешний сервис отклоняет LOGIN")
    void reportsExternalLoginFailure() throws Exception {
        mockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/auth")).willReturn(aResponse().withStatus(500)));

        HttpResponse<String> login = post(FAILED_LOGIN_TOKEN, "LOGIN", API_KEY);
        assertError(login);
        mockServer.verify(postRequestedFor(urlEqualTo("/auth")));

        mockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/doAction")).willReturn(aResponse().withStatus(200)));
        HttpResponse<String> action = post(FAILED_LOGIN_TOKEN, "ACTION", API_KEY);
        assertError(action);
        mockServer.verify(0, postRequestedFor(urlEqualTo("/doAction")));
    }

    @Test
    @Story("Обработка ошибок внешнего сервиса")
    @DisplayName("Возвращает ошибку, если внешний сервис отклоняет ACTION")
    void reportsExternalActionFailure() throws Exception {
        mockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/auth")).willReturn(aResponse().withStatus(200)));
        mockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/doAction")).willReturn(aResponse().withStatus(503)));

        assertOk(post(FAILED_ACTION_TOKEN, "LOGIN", API_KEY));
        assertError(post(FAILED_ACTION_TOKEN, "ACTION", API_KEY));
        mockServer.verify(postRequestedFor(urlEqualTo("/doAction"))
                .withRequestBody(equalTo("token=" + FAILED_ACTION_TOKEN)));
    }

    private static HttpResponse<String> post(String token, String action, String apiKey) throws IOException, InterruptedException {
        String form = "token=" + encode(token) + "&action=" + encode(action);
        HttpRequest.Builder request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (apiKey != null) {
            request.header("X-Api-Key", apiKey);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void assertOk(HttpResponse<String> response) {
        assertTrue(OK_RESULT.matcher(response.body()).find(),
                () -> "Ожидался result=OK, получен HTTP " + response.statusCode() + ": " + response.body());
    }

    private static void assertError(HttpResponse<String> response) {
        assertTrue(ERROR_RESULT.matcher(response.body()).find(),
                () -> "Ожидался result=ERROR, получен HTTP " + response.statusCode() + ": " + response.body());
    }

    private static boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 150);
            return true;
        } catch (ConnectException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
