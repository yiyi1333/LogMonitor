package com.logmonitor.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.logmonitor.model.DirectoryModels.*;
import com.logmonitor.model.ApiModels.SourceOptions;
import com.logmonitor.model.CollectorAgent;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

class DirectoryBrowseServiceTest {
    @TempDir Path root;
    @Test void onlyDirectoriesWithinRealRootAreListedAndSearchIsBounded() throws Exception {
        Files.createDirectory(root.resolve("a"));Files.createDirectory(root.resolve("b"));Files.writeString(root.resolve("file.log"),"fixture");
        Path outside=Files.createTempDirectory("directory-outside");
        try {
            Files.createSymbolicLink(root.resolve("escape"),outside);
            var listing=DirectoryReader.read(List.of(root.toString()),root.toString(),"");
            assertThat(listing.directories()).extracting(Entry::name).containsExactly("a","b");
            assertThat(listing.parentPath()).isNull();
            assertThat(DirectoryReader.read(List.of(root.toString()),root.toString(),"B").directories()).extracting(Entry::name).containsExactly("b");
            assertThatThrownBy(()->DirectoryReader.read(List.of(root.toString()),root.resolve("escape").toString(),null)).isInstanceOf(SourceManagementException.class);
            assertThatThrownBy(()->DirectoryReader.read(List.of(root.toString()),root.resolve("a/..").toString(),null)).isInstanceOf(SourceManagementException.class);
            for(int i=0;i<210;i++)Files.createDirectory(root.resolve("large"+i));
            var bounded=DirectoryReader.read(List.of(root.toString()),root.toString(),null);
            assertThat(bounded.directories()).hasSize(200);assertThat(bounded.truncated()).isTrue();
        }finally{Files.delete(outside);}
    }
    @Test void routesRequestsToOwnerRejectsExcessAndIgnoresLateOrDuplicateResults() throws Exception {
        AgentService agents=mock(AgentService.class);LogSourceService sources=mock(LogSourceService.class);
        when(agents.requireAgent(anyLong())).thenAnswer(i->agent(i.getArgument(0),Instant.now()));
        when(sources.options(any())).thenReturn(new SourceOptions(List.of(root.toString()),"*.log",""));
        when(agents.allowedRoots(anyLong())).thenReturn(List.of(root.toString()));
        try(var service=new DirectoryBrowseService(agents,sources)) {
            var poll=service.poll(1);var response=service.browse(1L,root.toString(),null);
            Request request=((ResponseEntity<Request>)poll.getResult()).getBody();
            assertThat(request.path()).isEqualTo(root.toString());
            var queued=service.browse(1L,root.toString(),"next");
            assertThatThrownBy(()->service.browse(1L,root.toString(),"third")).isInstanceOf(SourceManagementException.class);
            var listing=new Listing(root.toString(),null,List.of(),false);
            service.complete(2,new Result(request.requestId(),listing,null));assertThat(response.hasResult()).isFalse();
            service.complete(1,new Result("wrong-id",listing,null));assertThat(response.hasResult()).isFalse();
            service.complete(1,new Result(request.requestId(),listing,null));assertThat(response.getResult()).isEqualTo(listing);
            service.complete(1,new Result(request.requestId(),null,"DIRECTORY_UNAVAILABLE"));assertThat(response.getResult()).isEqualTo(listing);
            Request next=((ResponseEntity<Request>)service.poll(1).getResult()).getBody();
            service.complete(1,new Result(next.requestId(),listing,null));assertThat(queued.getResult()).isEqualTo(listing);
        }
    }
    @Test void legacyOfflineAndEscapingPathsFailAndTimeoutReleasesQueue() throws Exception {
        AgentService agents=mock(AgentService.class);LogSourceService sources=mock(LogSourceService.class);
        when(agents.requireAgent(anyLong())).thenAnswer(i->agent(i.getArgument(0),Instant.now()));
        when(sources.options(any())).thenReturn(new SourceOptions(List.of(root.toString()),"*.log",""));
        try(var service=new DirectoryBrowseService(agents,sources)) {
            assertThatThrownBy(()->service.browse(1L,root.toString(),null)).isInstanceOf(SourceManagementException.class).extracting("code").isEqualTo("DIRECTORY_UNSUPPORTED");
            when(agents.requireAgent(2)).thenReturn(agent(2,Instant.now().minusSeconds(100)));
            assertThatThrownBy(()->service.browse(2L,root.toString(),null)).isInstanceOf(SourceManagementException.class).extracting("code").isEqualTo("DIRECTORY_AGENT_OFFLINE");
            service.poll(1);
            assertThatThrownBy(()->service.browse(1L,root.getParent().toString(),null)).isInstanceOf(SourceManagementException.class);
            var response=service.browse(1L,root.toString(),null);CompletableFuture<Object> completion=new CompletableFuture<>();response.setResultHandler(completion::complete);
            assertThat(completion.get(9,TimeUnit.SECONDS)).isInstanceOf(SourceManagementException.class).extracting("code").isEqualTo("DIRECTORY_TIMEOUT");
            assertThat(service.poll(1).hasResult()).isFalse();
        }
    }
    private CollectorAgent agent(long id,Instant seen){return new CollectorAgent(id,"uuid","test","host",null,"1.1.2","hash",1,0,100,seen,null,"admin",true,seen,null);}
}
