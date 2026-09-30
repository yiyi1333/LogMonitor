package com.logmonitor.agent;

import static org.junit.Assert.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AgentDirectoryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void listsOnlyChildrenAndRejectsSymlinkEscapeTraversalAndSimilarPrefix() throws Exception {
        Path root=temporary.newFolder("root").toPath().toRealPath();Path outside=temporary.newFolder("root-other").toPath().toRealPath();
        Files.createDirectory(root.resolve("a"));Files.createDirectory(root.resolve("b"));Files.write(root.resolve("file.log"),new byte[0]);Files.createSymbolicLink(root.resolve("escape"),outside);
        AgentModels.DirectoryListing result=AgentDirectoryReader.read(Arrays.asList(root.toString()),root.toString(),"");
        assertEquals(2,result.directories.size());assertEquals("a",result.directories.get(0).name);assertNull(result.parentPath);
        assertEquals(1,AgentDirectoryReader.read(Arrays.asList(root.toString()),root.toString(),"B").directories.size());
        for(String path:Arrays.asList(root.resolve("escape").toString(),root.resolve("a/..").toString(),outside.toString())) {
            try{AgentDirectoryReader.read(Arrays.asList(root.toString()),path,null);fail("escape accepted");}catch(AgentDirectoryReader.DirectoryException expected){assertEquals("DIRECTORY_FORBIDDEN",expected.code);}
        }
        for(int i=0;i<210;i++)Files.createDirectory(root.resolve("large"+i));
        result=AgentDirectoryReader.read(Arrays.asList(root.toString()),root.toString(),null);assertEquals(200,result.directories.size());assertTrue(result.truncated);
    }
    @Test public void oldCenterIsDetectedAndChannelDoesNotChangeUploadProtocol() throws Exception {
        HttpServer center=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        center.createContext("/api/agent/v1/directories/requests",exchange->{exchange.sendResponseHeaders(404,-1);exchange.close();});center.start();
        try {
            AgentHttpClient http=new AgentHttpClient("http://127.0.0.1:"+center.getAddress().getPort(),"fixture");
            try{http.directoryRequest();fail("expected unsupported");}catch(AgentHttpClient.DirectoryUnsupportedException expected){}
        }finally{center.stop(0);}
    }
}
