package com.logmonitor.agent;

import static org.junit.Assert.*;
import com.logmonitor.agent.AgentModels.*;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AgentPipelineTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static Object call(AgentRuntime runtime, String name) throws Exception {
        Method method = AgentRuntime.class.getDeclaredMethod(name); method.setAccessible(true);
        try { return method.invoke(runtime); }
        catch (java.lang.reflect.InvocationTargetException exception) { throw (Exception) exception.getCause(); }
    }
    @Test public void drainsMultipleBatchesAndRecoversQueuedOffsetsAfterRestart() throws Exception {
        try (Center center = new Center()) {
            center.write(1, 2048);
            try (AgentRuntime runtime = center.runtime()) { call(runtime,"pollConfiguration"); call(runtime,"scan"); }
            RuntimeState state = AgentFiles.read(center.data.resolve("state.json"), RuntimeState.class);
            assertEquals(2048, state.files.values().iterator().next().offset);
            try (AgentRuntime runtime = center.runtime()) {
                call(runtime,"pollConfiguration"); while ((Boolean) call(runtime,"uploadOne")) { }
            }
            assertTrue(center.received.size()>2);
            long offset = 0;
            for (BatchMetadata metadata : center.received) { assertEquals(offset,metadata.startOffset); offset=metadata.endOffset; }
            assertEquals(2048,offset);
            assertEquals(0,Files.list(center.data.resolve("spool")).filter(p->p.toString().endsWith(".json")).count());
        }
    }
    @Test public void slowUploadDoesNotBlockScanHeartbeatOrAnotherSourceAndDeletionWaits() throws Exception {
        try (Center center = new Center(); AgentRuntime runtime = center.runtime()) {
            center.write(1, 512); center.write(2,512);
            call(runtime,"pollConfiguration"); call(runtime,"scan");
            center.blockSource = 1;
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> upload = worker.submit(() -> { try { call(runtime,"uploadOne"); } catch(Exception e){throw new RuntimeException(e);} });
                assertTrue(center.entered.await(2,TimeUnit.SECONDS));
                long started=System.nanoTime(); call(runtime,"scan"); call(runtime,"heartbeat");
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<2000);
                assertTrue((Boolean)call(runtime,"uploadOne"));
                assertEquals(2,center.received.get(0).sourceId);
                center.remote.sources.remove(0); center.remote.revision++;
                call(runtime,"pollConfiguration"); call(runtime,"heartbeat");
                assertEquals(1,center.lastHeartbeat.sources.size());
                center.release.countDown(); upload.get(3,TimeUnit.SECONDS);
                RuntimeState state=AgentFiles.read(center.data.resolve("state.json"),RuntimeState.class);
                for(FileState file:state.files.values())assertEquals(2,file.sourceId);
            } finally {center.release.countDown(); worker.shutdownNow();}
        }
    }
    @Test public void retryAfterKeepsOriginalBatchAndOffsetConflictOnlyRewindsItsStream() throws Exception {
        try (Center center=new Center();AgentRuntime runtime=center.runtime()) {
            center.write(1,256);center.write(2,256);call(runtime,"pollConfiguration");call(runtime,"scan");
            center.rejectStatus=503;
            assertFalse((Boolean)call(runtime,"uploadOne"));
            int requests=center.requests.get();
            // Another source may proceed, but the rejected source cannot immediately retry.
            call(runtime,"uploadOne");call(runtime,"uploadOne");
            assertTrue(center.requests.get()<=requests+1);
            center.rejectStatus=0;
            java.lang.reflect.Field retry=AgentRuntime.class.getDeclaredField("retryAt");retry.setAccessible(true);
            ((Map<?,?>)retry.get(runtime)).clear();
            center.rejectStatus=409;
            assertTrue((Boolean)call(runtime,"uploadOne"));
            RuntimeState state=AgentFiles.read(center.data.resolve("state.json"),RuntimeState.class);
            long zero=0;for(FileState file:state.files.values())if(file.offset==0)zero++;
            assertEquals(1,zero);
            assertTrue(Files.list(center.data.resolve("spool")).anyMatch(p->p.toString().endsWith(".json")));
        }
    }
    @Test public void spoolAccountingFailureStopsReadingUntilSuccessfulCalibration() throws Exception {
        try(Center center=new Center();AgentRuntime runtime=center.runtime()) {
            center.write(1,256);call(runtime,"pollConfiguration");
            Path spool=center.data.resolve("spool");Path moved=center.data.resolve("spool-offline");
            Files.move(spool,moved);
            try {call(runtime,"reportMetrics");fail("Expected accounting failure");}catch(java.io.IOException expected){}
            call(runtime,"scan");
            assertFalse(Files.exists(center.data.resolve("state.json")) && Files.size(center.data.resolve("state.json"))>100);
            Files.move(moved,spool);call(runtime,"reportMetrics");call(runtime,"scan");
            assertTrue(Files.list(spool).anyMatch(p->p.toString().endsWith(".json")));
        }
    }
    @Test public void truncationWaitsForInFlightBatchBeforeResettingGeneration() throws Exception {
        try(Center center=new Center();AgentRuntime runtime=center.runtime()) {
            center.write(1,512);call(runtime,"pollConfiguration");call(runtime,"scan");
            RuntimeState before=AgentFiles.read(center.data.resolve("state.json"),RuntimeState.class);
            String generation=before.files.values().iterator().next().generation;
            center.blockSource=1;ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<?> upload=worker.submit(()->{try{call(runtime,"uploadOne");}catch(Exception failure){throw new RuntimeException(failure);}});
                assertTrue(center.entered.await(2,TimeUnit.SECONDS));center.write(1,32);call(runtime,"scan");
                assertEquals(generation,AgentFiles.read(center.data.resolve("state.json"),RuntimeState.class).files.values().iterator().next().generation);
                center.release.countDown();upload.get(3,TimeUnit.SECONDS);call(runtime,"scan");
                RuntimeState after=AgentFiles.read(center.data.resolve("state.json"),RuntimeState.class);
                assertNotEquals(generation,after.files.values().iterator().next().generation);
                assertEquals(32,after.files.values().iterator().next().offset);
            }finally{center.release.countDown();worker.shutdownNow();}
        }
    }
    @Test public void lostAckRetriesSameBatchRatherThanAdvancingOrDeletingIt() throws Exception {
        try(Center center=new Center();AgentRuntime runtime=center.runtime()) {
            center.write(1,128);call(runtime,"pollConfiguration");call(runtime,"scan");center.dropAck=true;
            assertFalse((Boolean)call(runtime,"uploadOne"));
            String batch=center.received.get(0).batchId;
            java.lang.reflect.Field retry=AgentRuntime.class.getDeclaredField("retryAt");retry.setAccessible(true);((Map<?,?>)retry.get(runtime)).clear();
            assertTrue((Boolean)call(runtime,"uploadOne"));assertEquals(batch,center.received.get(1).batchId);
        }
    }
    private final class Center implements AutoCloseable {
        final Path root=temporary.newFolder().toPath().toRealPath();
        final Path data=temporary.newFolder().toPath();
        final Path config=temporary.newFolder().toPath().resolve("agent.json");
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final ExecutorService handlers=Executors.newCachedThreadPool();
        final List<BatchMetadata> received=Collections.synchronizedList(new ArrayList<BatchMetadata>());
        final AtomicInteger requests=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final RemoteConfig remote=new RemoteConfig();
        volatile long blockSource=-1;volatile int rejectStatus;volatile boolean dropAck;volatile Heartbeat lastHeartbeat;
        Center()throws Exception {
            remote.revision=1;
            for(int i=1;i<=2;i++) {
                SourceConfig source=new SourceConfig();source.id=i;source.name="source"+i;source.path=Files.createDirectory(root.resolve("source"+i)).toString();
                source.include="*.log";source.startMode="HISTORY_180D";remote.sources.add(source);
            }
            server.setExecutor(handlers);
            server.createContext("/api/agent/v1/config", exchange->{
                byte[] body=AgentFiles.JSON.writeValueAsBytes(remote);exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);exchange.close();
            });
            server.createContext("/api/agent/v1/heartbeat",exchange->{
                lastHeartbeat=AgentFiles.JSON.readValue(exchange.getRequestBody(),Heartbeat.class);
                exchange.sendResponseHeaders(204,-1);exchange.close();
            });
            server.createContext("/api/agent/v1/batches",exchange->{
                byte[] raw=readAll(exchange.getRequestBody());String text=new String(raw,StandardCharsets.ISO_8859_1);
                int start=text.indexOf('{');int end=text.indexOf("\r\n--",start);
                BatchMetadata metadata=AgentFiles.JSON.readValue(text.substring(start,end),BatchMetadata.class);requests.incrementAndGet();
                if(metadata.sourceId==blockSource){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                int status=rejectStatus;
                Map<String,Object> reply=new HashMap<String,Object>();
                if(status==0){received.add(metadata);reply.put("status","ACK");reply.put("expectedOffset",metadata.endOffset);status=200;}
                else{reply.put("code","TEST");reply.put("message","retry");if(status==409)reply.put("expectedOffset",0);exchange.getResponseHeaders().set("Retry-After","5");}
                if(dropAck){dropAck=false;exchange.close();return;}
                byte[] body=AgentFiles.JSON.writeValueAsBytes(reply);exchange.sendResponseHeaders(status,body.length);
                exchange.getResponseBody().write(body);exchange.close();
            });
            LocalConfig local=new LocalConfig();local.serverUrl="http://127.0.0.1:"+server.getAddress().getPort();local.token="test";
            local.allowedRoots.add(root.toString());local.maxBatchBytes=128;AgentFiles.writeAtomic(config,local);server.start();
        }
        AgentRuntime runtime()throws Exception{return new AgentRuntime(config,data);}
        void write(int source,int bytes)throws Exception{byte[] data=new byte[bytes];Arrays.fill(data,(byte)'a');Files.write(root.resolve("source"+source).resolve("app.log"),data);}
        public void close(){release.countDown();server.stop(0);handlers.shutdownNow();}
    }
    private static byte[] readAll(java.io.InputStream input)throws java.io.IOException{
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
        while((count=input.read(buffer))!=-1)output.write(buffer,0,count);return output.toByteArray();
    }
}
