package com.logmonitor.agent;
import static org.junit.Assert.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.Test;
public class AgentHttpDeadlineTest {
    @Test public void deadlineAbortsHungResponseAndKeepsPayloadForRetry() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        CountDownLatch release=new CountDownLatch(1);ExecutorService handlers=Executors.newCachedThreadPool();server.setExecutor(handlers);
        server.createContext("/api/agent/v1/batches",exchange->{
            while(exchange.getRequestBody().read()!=-1){}
            try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            exchange.close();
        });server.start();Path payload=Files.createTempFile("agent-deadline",".gz");Files.write(payload,new byte[]{1,2});
        try {
            AgentHttpClient http=new AgentHttpClient("http://127.0.0.1:"+server.getAddress().getPort(),"test",false,200);
            long start=System.nanoTime();
            try{http.upload(new AgentModels.BatchMetadata(),payload);fail("Expected upload timeout");}catch(java.io.IOException expected){}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000);assertTrue(Files.exists(payload));
        }finally{release.countDown();server.stop(0);handlers.shutdownNow();Files.deleteIfExists(payload);}
    }
}
