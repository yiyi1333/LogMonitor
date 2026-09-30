package com.logmonitor.service;

import com.logmonitor.model.DirectoryModels.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

/** Ephemeral commands on an independent authenticated outbound Agent channel. */
@Service
public class DirectoryBrowseService implements AutoCloseable {
    private final AgentService agents;
    private final LogSourceService sources;
    private final ConcurrentMap<Long,Channel> channels=new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"directory-deadlines");t.setDaemon(true);return t;});
    private final ExecutorService readers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),r->{Thread t=new Thread(r,"directory-reader");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final class Job {
        final Request request;final DeferredResult<Listing> response;ScheduledFuture<?> deadline;
        Job(Request request,DeferredResult<Listing> response){this.request=request;this.response=response;}
    }
    private static final class Channel {
        long lastPoll;Job running;final ArrayDeque<Job> queue=new ArrayDeque<>();
        DeferredResult<ResponseEntity<Request>> poll;ScheduledFuture<?> pollDeadline;
    }
    public DirectoryBrowseService(AgentService agents,LogSourceService sources){this.agents=agents;this.sources=sources;
        timers.scheduleWithFixedDelay(()->channels.entrySet().removeIf(e->{synchronized(e.getValue()){Channel c=e.getValue();return c.running==null && c.queue.isEmpty() && c.poll==null && System.currentTimeMillis()-c.lastPoll>60000;}}),60,60,TimeUnit.SECONDS);
    }
    public DeferredResult<Listing> browse(Long agentId,String path,String query) {
        if(path!=null && path.length()>1500 || query!=null && query.length()>200)throw new IllegalArgumentException("Invalid directory request");
        List<String> roots=sources.options(agentId).allowedRoots();
        DeferredResult<Listing> response=new DeferredResult<>(9000L);
        if(agentId==null){
            try {readers.execute(()->{try{response.setResult(DirectoryReader.read(roots,path,query));}catch(Exception e){response.setErrorResult(e);}});}
            catch(RejectedExecutionException e){throw DirectoryReader.failure("DIRECTORY_BUSY",HttpStatus.TOO_MANY_REQUESTS);}
            ScheduledFuture<?> expiry=timers.schedule(()->response.setErrorResult(DirectoryReader.failure("DIRECTORY_TIMEOUT",HttpStatus.GATEWAY_TIMEOUT)),8,TimeUnit.SECONDS);
            response.onCompletion(()->expiry.cancel(false));return response;
        }
        var agent=agents.requireAgent(agentId);
        if(agent.lastSeenAt()==null || Duration.between(agent.lastSeenAt(),Instant.now()).getSeconds()>90)throw DirectoryReader.failure("DIRECTORY_AGENT_OFFLINE",HttpStatus.CONFLICT);
        if(path==null || path.isBlank()){response.setResult(new Listing(null,null,roots.stream().map(p->new Entry(p,p)).toList(),false));return response;}
        validate(roots,path);
        Channel channel=channels.get(agentId);
        if(channel==null)throw DirectoryReader.failure("DIRECTORY_UNSUPPORTED",HttpStatus.CONFLICT);
        synchronized(channel){
            if(System.currentTimeMillis()-channel.lastPoll>35000)throw DirectoryReader.failure("DIRECTORY_UNSUPPORTED",HttpStatus.CONFLICT);
            if(channel.queue.size()+(channel.running==null?0:1)>=2)throw DirectoryReader.failure("DIRECTORY_BUSY",HttpStatus.TOO_MANY_REQUESTS);
            Job job=new Job(new Request(UUID.randomUUID().toString(),path,query,System.currentTimeMillis()+8000),response);
            channel.queue.add(job);
            job.deadline=timers.schedule(()->{synchronized(channel){channel.queue.remove(job);if(channel.running==job)channel.running=null;job.response.setErrorResult(DirectoryReader.failure("DIRECTORY_TIMEOUT",HttpStatus.GATEWAY_TIMEOUT));}},8,TimeUnit.SECONDS);
            response.onCompletion(()->{synchronized(channel){channel.queue.remove(job);if(channel.running==job)channel.running=null;job.deadline.cancel(false);}});
            dispatch(channel);return response;
        }
    }
    public DeferredResult<ResponseEntity<Request>> poll(long agentId){
        agents.requireAgent(agentId);
        Channel channel=channels.computeIfAbsent(agentId,id->new Channel());
        synchronized(channel){
            channel.lastPoll=System.currentTimeMillis();
            if(channel.poll!=null || channel.running!=null)throw DirectoryReader.failure("DIRECTORY_BUSY",HttpStatus.TOO_MANY_REQUESTS);
            var response=new DeferredResult<ResponseEntity<Request>>(26000L);
            channel.poll=response;
            channel.pollDeadline=timers.schedule(()->{synchronized(channel){if(channel.poll==response){channel.poll=null;response.setResult(ResponseEntity.noContent().build());}}},25,TimeUnit.SECONDS);
            response.onCompletion(()->{synchronized(channel){if(channel.poll==response){channel.poll=null;channel.pollDeadline.cancel(false);}}});
            dispatch(channel);return response;
        }
    }
    private void dispatch(Channel channel){
        if(channel.poll==null || channel.running!=null || channel.queue.isEmpty())return;
        channel.running=channel.queue.remove();var waiting=channel.poll;channel.poll=null;channel.pollDeadline.cancel(false);
        waiting.setResult(ResponseEntity.ok(channel.running.request));
    }
    public void complete(long agentId,Result result){
        agents.requireAgent(agentId);Channel channel=channels.get(agentId);if(channel==null || result==null)return;
        synchronized(channel){
            Job job=channel.running;
            if(job==null || !job.request.requestId().equals(result.requestId()) || System.currentTimeMillis()>job.request.deadlineEpochMillis())return;
            try {
                if(result.errorCode()!=null){String code=Set.of("DIRECTORY_FORBIDDEN","DIRECTORY_UNAVAILABLE","DIRECTORY_TIMEOUT").contains(result.errorCode())?result.errorCode():"DIRECTORY_UNAVAILABLE";job.response.setErrorResult(DirectoryReader.failure(code,HttpStatus.BAD_REQUEST));}
                else {Listing listing=result.listing();List<String> roots=agents.allowedRoots(agentId);
                    if(listing==null || listing.directories()==null || listing.directories().size()>200)throw new IllegalArgumentException();
                    validate(roots,listing.path());if(listing.parentPath()!=null)validate(roots,listing.parentPath());
                    for(Entry entry:listing.directories()) {if(entry.name()==null || entry.name().length()>1500)throw new IllegalArgumentException();validate(roots,entry.path());}
                    job.response.setResult(listing);
                }
            }catch(Exception e){job.response.setErrorResult(DirectoryReader.failure("DIRECTORY_UNAVAILABLE",HttpStatus.BAD_REQUEST));}
            finally {job.deadline.cancel(false);channel.running=null;}
        }
    }
    private void validate(List<String> roots,String raw){
        try {Path p=Path.of(raw);if(raw.length()>1500 || !p.isAbsolute() || !p.equals(p.normalize()) || roots.stream().map(Path::of).noneMatch(p::startsWith))throw new IllegalArgumentException();}
        catch(Exception e){throw DirectoryReader.failure("DIRECTORY_FORBIDDEN",HttpStatus.FORBIDDEN);}
    }
    @PreDestroy public void close(){timers.shutdownNow();readers.shutdownNow();channels.values().forEach(c->{synchronized(c){if(c.running!=null)c.running.response.setErrorResult(DirectoryReader.failure("DIRECTORY_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE));new ArrayList<>(c.queue).forEach(j->j.response.setErrorResult(DirectoryReader.failure("DIRECTORY_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE)));if(c.poll!=null)c.poll.setResult(ResponseEntity.noContent().build());}});channels.clear();}
}
