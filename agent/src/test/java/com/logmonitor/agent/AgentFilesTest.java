package com.logmonitor.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.logmonitor.agent.AgentModels.LocalConfig;
import com.logmonitor.agent.AgentModels.BatchMetadata;
import com.logmonitor.agent.AgentModels.RuntimeState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.Test;

public class AgentFilesTest {
    @Test
    public void writesAndRestoresConfigurationAtomically() throws Exception {
        Path directory = Files.createTempDirectory("logmonitor-agent");
        Path configPath = directory.resolve("agent.json");
        LocalConfig config = new LocalConfig();
        config.serverUrl = "https://127.0.0.1:8443";
        config.allowHttp = true;
        config.token = "one-time-issued-token";
        AgentFiles.writeAtomic(configPath, config);
        AgentFiles.ownerOnly(configPath);

        LocalConfig restored = AgentFiles.read(configPath, LocalConfig.class);
        assertEquals(config.serverUrl, restored.serverUrl);
        assertTrue(restored.allowHttp);
        assertEquals(config.token, restored.token);
        assertFalse(Files.exists(directory.resolve("agent.json.tmp")));
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(configPath);
            assertEquals(2, permissions.size());
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems are outside the Linux production target.
        }
    }

    @Test
    public void recoversCursorFromAtomicallyQueuedBatch() throws Exception {
        Path directory = Files.createTempDirectory("logmonitor-agent-recovery");
        Path configPath = directory.resolve("agent.json");
        Path data = directory.resolve("data");
        Path spool = data.resolve("spool");
        Files.createDirectories(spool);
        LocalConfig config = new LocalConfig();
        config.serverUrl = "http://127.0.0.1:8080";
        config.token = "test-token";
        AgentFiles.writeAtomic(configPath, config);
        AgentFiles.writeAtomic(data.resolve("state.json"), new RuntimeState());
        BatchMetadata batch = new BatchMetadata();
        batch.batchId = "queued";
        batch.sourceId = 9;
        batch.fileKey = "file-key";
        batch.generation = "generation-1";
        batch.path = "/logs/application.log";
        batch.startOffset = 0;
        batch.endOffset = 128;
        batch.fileSize = 128;
        AgentFiles.writeAtomic(spool.resolve("0000000000000000001-queued.json"), batch);
        Files.write(spool.resolve("0000000000000000001-queued.gz"), new byte[] {1});

        AgentRuntime runtime = new AgentRuntime(configPath, data);
        runtime.close();
        RuntimeState restored = AgentFiles.read(data.resolve("state.json"), RuntimeState.class);
        assertEquals(128, restored.files.get("9|file-key").offset);
        assertEquals("generation-1", restored.files.get("9|file-key").generation);
    }

    @Test
    public void writesThroughAnExistingSymlinkedParentDirectory() throws Exception {
        Path directory = Files.createTempDirectory("logmonitor-agent-symlink");
        Path target = Files.createDirectory(directory.resolve("target"));
        Path link = Files.createSymbolicLink(directory.resolve("link"), target);
        LocalConfig config = new LocalConfig();
        config.agentName = "symlink-parent";

        Path configPath = link.resolve("agent.json");
        AgentFiles.writeAtomic(configPath, config);

        assertEquals("symlink-parent", AgentFiles.read(configPath, LocalConfig.class).agentName);
    }
}
