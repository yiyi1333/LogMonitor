package com.logmonitor.agent;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.logmonitor.agent.AgentModels.LocalConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.Test;

public class AgentPreflightTest {
    @Test
    public void rejectsRemoteHttpUnlessExplicitlyAllowed() {
        assertFailureUnchecked("--allow-http", new UncheckedAction() {
            public void run() {
                AgentHttpClient.validateServerUrl("http://8.154.47.28:8080", false);
            }
        });
        AgentHttpClient.validateServerUrl("http://8.154.47.28:8080", true);
        AgentHttpClient.validateServerUrl("https://8.154.47.28:8080", false);
    }

    @Test
    public void validatesConfigurationAndAuthenticatedCenterConnection() throws Exception {
        HttpServer server = server(200, true);
        try {
            Fixture fixture = fixture(server.getAddress().getPort());
            AgentMain.check(fixture.config, fixture.data);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void rejectsMissingConfiguration() throws Exception {
        Path directory = Files.createTempDirectory("agent-preflight-missing");
        assertFailure("配置文件不存在或不可读", new CheckedAction() {
            public void run() throws Exception {
                AgentMain.validateLocalConfig(directory.resolve("agent.json"), directory);
            }
        });
    }

    @Test
    public void rejectsMalformedConfiguration() throws Exception {
        Path directory = Files.createTempDirectory("agent-preflight-malformed");
        Path config = directory.resolve("agent.json");
        Files.write(config, "{".getBytes(StandardCharsets.UTF_8));
        assertAnyFailure(new CheckedAction() {
            public void run() throws Exception {
                AgentMain.validateLocalConfig(config, directory);
            }
        });
    }

    @Test
    public void rejectsIncompleteConfigurationAndMissingRoot() throws Exception {
        Fixture fixture = fixture(8080);
        LocalConfig local = AgentFiles.read(fixture.config, LocalConfig.class);
        local.token = "short";
        AgentFiles.writeAtomic(fixture.config, local);
        assertFailure("token 长度无效", new CheckedAction() {
            public void run() throws Exception {
                AgentMain.validateLocalConfig(fixture.config, fixture.data);
            }
        });

        local.token = "01234567890123456789012345678901";
        local.allowedRoots.clear();
        local.allowedRoots.add(fixture.data.resolve("missing-root").toString());
        AgentFiles.writeAtomic(fixture.config, local);
        assertFailure("允许根目录不存在", new CheckedAction() {
            public void run() throws Exception {
                AgentMain.validateLocalConfig(fixture.config, fixture.data);
            }
        });
    }

    @Test
    public void rejectsInvalidUuidAndMissingDataDirectory() throws Exception {
        Fixture fixture = fixture(8080);
        LocalConfig local = AgentFiles.read(fixture.config, LocalConfig.class);
        local.agentUuid = "not-a-uuid";
        AgentFiles.writeAtomic(fixture.config, local);
        assertFailure("agentUuid 格式无效", new CheckedAction() {
            public void run() throws Exception {
                AgentMain.validateLocalConfig(fixture.config, fixture.data);
            }
        });

        local.agentUuid = UUID.randomUUID().toString();
        AgentFiles.writeAtomic(fixture.config, local);
        assertFailure("数据目录不存在、不可读或不可写", new CheckedAction() {
            public void run() throws Exception {
                AgentMain.validateLocalConfig(fixture.config, fixture.data.resolve("missing"));
            }
        });
    }

    @Test
    public void rejectsInvalidTokenAtCenter() throws Exception {
        HttpServer server = server(401, true);
        try {
            final Fixture fixture = fixture(server.getAddress().getPort());
            assertFailure("HTTP 401", new CheckedAction() {
                public void run() throws Exception {
                    AgentMain.check(fixture.config, fixture.data);
                }
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void rejectsUnavailableCenter() throws Exception {
        HttpServer server = server(200, false);
        int port = server.getAddress().getPort();
        server.stop(0);
        final Fixture fixture = fixture(port);
        assertFailure("Connection refused", new CheckedAction() {
            public void run() throws Exception {
                AgentMain.check(fixture.config, fixture.data);
            }
        });
    }

    private HttpServer server(final int status, final boolean start) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agent/v1/config", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                boolean authenticated = "Bearer 01234567890123456789012345678901"
                        .equals(exchange.getRequestHeaders().getFirst("Authorization"));
                int responseStatus = authenticated ? status : 401;
                byte[] body = (responseStatus == 200
                        ? "{\"revision\":1,\"sources\":[]}"
                        : "{\"code\":\"UNAUTHORIZED\",\"message\":\"HTTP 401\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, body.length);
                OutputStream output = exchange.getResponseBody();
                output.write(body);
                output.close();
            }
        });
        if (start) server.start();
        return server;
    }

    private Fixture fixture(int port) throws Exception {
        Path directory = Files.createTempDirectory("agent-preflight");
        Path data = Files.createDirectory(directory.resolve("data"));
        Path root = Files.createDirectory(directory.resolve("logs"));
        Path config = directory.resolve("agent.json");
        LocalConfig local = new LocalConfig();
        local.serverUrl = "http://127.0.0.1:" + port;
        local.agentId = 1;
        local.agentUuid = UUID.randomUUID().toString();
        local.token = "01234567890123456789012345678901";
        local.agentName = "test-agent";
        local.hostName = "test-host";
        local.allowedRoots.add(root.toString());
        AgentFiles.writeAtomic(config, local);
        return new Fixture(config, data);
    }

    private void assertFailure(String expected, CheckedAction action) throws Exception {
        try {
            action.run();
            fail("Expected failure containing: " + expected);
        } catch (Exception exception) {
            assertTrue("Unexpected message: " + exception.getMessage(),
                    exception.getMessage() != null && exception.getMessage().contains(expected));
        }
    }

    private void assertAnyFailure(CheckedAction action) throws Exception {
        try {
            action.run();
            fail("Expected validation failure");
        } catch (Exception expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    private void assertFailureUnchecked(String expected, UncheckedAction action) {
        try {
            action.run();
            fail("Expected failure containing: " + expected);
        } catch (Exception exception) {
            assertTrue("Unexpected message: " + exception.getMessage(),
                    exception.getMessage() != null && exception.getMessage().contains(expected));
        }
    }

    private interface CheckedAction { void run() throws Exception; }
    private interface UncheckedAction { void run(); }

    private static final class Fixture {
        final Path config;
        final Path data;
        Fixture(Path config, Path data) { this.config = config; this.data = data; }
    }
}
