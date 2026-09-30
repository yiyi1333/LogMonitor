package com.logmonitor.agent;

import com.logmonitor.agent.AgentModels.DirectoryEntry;
import com.logmonitor.agent.AgentModels.DirectoryListing;
import java.util.stream.Collectors;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;


/** One-level metadata enumeration only; no file contents, recursive walks or persistent cache. */
final class AgentDirectoryReader {
    private AgentDirectoryReader() {}
    static DirectoryListing read(List<String> allowedRoots, String rawPath, String query) {
        try {
            List<Path> roots = new ArrayList<>();
            for (String value : allowedRoots) {
                try { Path root=Paths.get(value).toRealPath(); if(Files.isDirectory(root))roots.add(root); }
                catch(IOException ignored) { /* Unavailable roots cannot authorize a path. */ }
            }
            if(rawPath==null || rawPath.trim().isEmpty())return new DirectoryListing(null,null,roots.stream().distinct()
                    .sorted().map(p->new DirectoryEntry(p.toString(),p.toString())).collect(Collectors.toList()),false);
            Path submitted=Paths.get(rawPath);
            if(!submitted.isAbsolute() || !submitted.equals(submitted.normalize()))throw failure("DIRECTORY_FORBIDDEN");
            Path path=submitted.toRealPath();
            if(roots.stream().noneMatch(path::startsWith))throw failure("DIRECTORY_FORBIDDEN");
            if(!Files.isDirectory(path) || !Files.isReadable(path))throw failure("DIRECTORY_UNAVAILABLE");
            String search=query==null?"":query.toLowerCase(Locale.ROOT);
            TreeMap<String,DirectoryEntry> entries=new TreeMap<>();boolean truncated=false;
            long deadline=System.nanoTime()+2_000_000_000L;
            try(DirectoryStream<Path> children=Files.newDirectoryStream(path)) {
                for(Path child:children) {
                    if(System.nanoTime()>deadline || Thread.currentThread().isInterrupted()){truncated=true;break;}
                    if(!child.getFileName().toString().toLowerCase(Locale.ROOT).contains(search))continue;
                    try {
                        Path real=child.toRealPath();
                        if(Files.isDirectory(real) && Files.isReadable(real) && roots.stream().anyMatch(real::startsWith)) {
                            entries.put(child.getFileName().toString(),new DirectoryEntry(child.getFileName().toString(),real.toString()));
                            if(entries.size()>200){entries.pollLastEntry();truncated=true;}
                        }
                    }catch(IOException ignored){ /* A vanished/inaccessible child is not selectable. */ }
                }
            }
            Path parent=path.getParent();String parentPath=parent!=null && roots.stream().anyMatch(parent::startsWith)?parent.toString():null;
            return new DirectoryListing(path.toString(),parentPath,new ArrayList<DirectoryEntry>(entries.values()),truncated);
        }catch(AccessDeniedException failure){throw failure("DIRECTORY_FORBIDDEN");}
        catch(IOException | InvalidPathException failure){throw failure("DIRECTORY_UNAVAILABLE");}
    }
    static DirectoryException failure(String code){return new DirectoryException(code);}
    static final class DirectoryException extends RuntimeException { final String code; DirectoryException(String code){super(code);this.code=code;} }
}
