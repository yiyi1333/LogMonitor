package com.logmonitor.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.logmonitor.agent.AgentModels.Heartbeat;
import com.logmonitor.agent.AgentModels.LocalConfig;
import com.logmonitor.agent.AgentModels.RemoteConfig;
import com.logmonitor.agent.AgentModels.SourceConfig;
import com.logmonitor.agent.AgentModels.SourceReport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AgentRuntimeRecoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void replacesErrorsAfterDirectoryRecoveryWithoutRestartOrConfigChange() throws Exception {
        try (Center center = new Center()) {
            SourceConfig source = center.source(1, "missing");
            center.configure(source);
            try (AgentRuntime runtime = new AgentRuntime(center.config, center.data)) {
                runtime.runOnce();
                assertEquals("ERROR", center.report(1).status);
                assertTrue(center.report(1).error.contains("missing"));
                Files.createDirectory(center.root.resolve("missing"));
                runtime.runOnce();
                assertEquals("ACTIVE", center.report(1).status);
                assertNull(center.report(1).error);
            }
        }
    }

    @Test
    public void removesDeletedReportsWhenRebuildingAndRemovingAllSources() throws Exception {
        try (Center center = new Center()) {
            center.configure(center.source(1, "old-missing"));
            try (AgentRuntime runtime = new AgentRuntime(center.config, center.data)) {
                runtime.runOnce();
                assertEquals("ERROR", center.report(1).status);
                Files.createDirectory(center.root.resolve("replacement"));
                center.configure(center.source(2, "replacement"));
                runtime.runOnce();
                assertEquals(1, center.heartbeat.get().sources.size());
                assertEquals("ACTIVE", center.report(2).status);
                assertNull(center.report(2).error);
                center.configure();
                runtime.runOnce();
                assertTrue(center.heartbeat.get().sources.isEmpty());
            }
        }
    }

    @Test
    public void preservesOtherFailuresWhileSourcesRecoverOrAreRemoved() throws Exception {
        try (Center center = new Center()) {
            SourceConfig first = center.source(1, "first");
            SourceConfig second = center.source(2, "second");
            center.configure(first, second);
            try (AgentRuntime runtime = new AgentRuntime(center.config, center.data)) {
                runtime.runOnce();
                assertEquals("ERROR", center.report(1).status);
                assertEquals("ERROR", center.report(2).status);
                Files.createDirectory(center.root.resolve("first"));
                runtime.runOnce();
                assertEquals("ACTIVE", center.report(1).status);
                assertNull(center.report(1).error);
                assertEquals("ERROR", center.report(2).status);
                center.configure(second);
                runtime.runOnce();
                assertEquals(1, center.heartbeat.get().sources.size());
                assertEquals("ERROR", center.report(2).status);
                center.configure();
                runtime.runOnce();
                assertTrue(center.heartbeat.get().sources.isEmpty());
            }
        }
    }

    private final class Center implements AutoCloseable {
        final Path root = temporary.newFolder().toPath().toRealPath();
        final Path data = temporary.newFolder().toPath();
        final Path config = temporary.newFolder().toPath().resolve("agent.json");
        final AtomicReference<Heartbeat> heartbeat = new AtomicReference<Heartbeat>();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        volatile RemoteConfig remote = new RemoteConfig();

        Center() throws Exception {
            server.createContext("/api/agent/v1/config", exchange -> {
                RemoteConfig current = remote;
                if (("\"" + current.revision + "\"").equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    exchange.sendResponseHeaders(304, -1);
                } else {
                    byte[] body = AgentFiles.JSON.writeValueAsBytes(current);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.createContext("/api/agent/v1/heartbeat", exchange -> {
                heartbeat.set(AgentFiles.JSON.readValue(exchange.getRequestBody(), Heartbeat.class));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            LocalConfig local = new LocalConfig();
            local.serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            local.token = "test-token";
            local.allowedRoots.add(root.toString());
            AgentFiles.writeAtomic(config, local);
            server.start();
        }

        SourceConfig source(long id, String name) {
            SourceConfig source = new SourceConfig();
            source.id = id;
            source.name = name;
            source.path = root.resolve(name).toString();
            source.include = "*.log";
            source.startMode = "NOW";
            return source;
        }

        void configure(SourceConfig... sources) {
            RemoteConfig update = new RemoteConfig();
            update.revision = remote.revision + 1;
            update.sources.addAll(Arrays.asList(sources));
            remote = update;
        }

        SourceReport report(long sourceId) {
            for (SourceReport report : heartbeat.get().sources) if (report.sourceId == sourceId) return report;
            throw new AssertionError("Missing report for source " + sourceId);
        }

        public void close() { server.stop(0); }
    }
}
