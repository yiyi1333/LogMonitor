package com.logmonitor.service;

import com.logmonitor.model.DirectoryModels.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.http.HttpStatus;

/** One-level metadata enumeration only; no file contents, recursive walks or persistent cache. */
public final class DirectoryReader {
    private DirectoryReader() {}
    public static Listing read(List<String> allowedRoots, String rawPath, String query) {
        try {
            List<Path> roots = new ArrayList<>();
            for (String value : allowedRoots) {
                try { Path root=Paths.get(value).toRealPath(); if(Files.isDirectory(root))roots.add(root); }
                catch(IOException ignored) { /* Unavailable roots cannot authorize a path. */ }
            }
            if(rawPath==null || rawPath.isBlank())return new Listing(null,null,roots.stream().distinct()
                    .sorted().map(p->new Entry(p.toString(),p.toString())).toList(),false);
            Path submitted=Paths.get(rawPath);
            if(!submitted.isAbsolute() || !submitted.equals(submitted.normalize()))throw failure("DIRECTORY_FORBIDDEN",HttpStatus.FORBIDDEN);
            Path path=submitted.toRealPath();
            if(roots.stream().noneMatch(path::startsWith))throw failure("DIRECTORY_FORBIDDEN",HttpStatus.FORBIDDEN);
            if(!Files.isDirectory(path) || !Files.isReadable(path))throw failure("DIRECTORY_UNAVAILABLE",HttpStatus.BAD_REQUEST);
            String search=query==null?"":query.toLowerCase(Locale.ROOT);
            TreeMap<String,Entry> entries=new TreeMap<>();boolean truncated=false;
            long deadline=System.nanoTime()+2_000_000_000L;
            try(DirectoryStream<Path> children=Files.newDirectoryStream(path)) {
                for(Path child:children) {
                    if(System.nanoTime()>deadline || Thread.currentThread().isInterrupted()){truncated=true;break;}
                    if(!child.getFileName().toString().toLowerCase(Locale.ROOT).contains(search))continue;
                    try {
                        Path real=child.toRealPath();
                        if(Files.isDirectory(real) && Files.isReadable(real) && roots.stream().anyMatch(real::startsWith)) {
                            entries.put(child.getFileName().toString(),new Entry(child.getFileName().toString(),real.toString()));
                            if(entries.size()>200){entries.pollLastEntry();truncated=true;}
                        }
                    }catch(IOException ignored){ /* A vanished/inaccessible child is not selectable. */ }
                }
            }
            Path parent=path.getParent();String parentPath=parent!=null && roots.stream().anyMatch(parent::startsWith)?parent.toString():null;
            return new Listing(path.toString(),parentPath,List.copyOf(entries.values()),truncated);
        }catch(AccessDeniedException failure){throw failure("DIRECTORY_FORBIDDEN",HttpStatus.FORBIDDEN);}
        catch(IOException | InvalidPathException failure){throw failure("DIRECTORY_UNAVAILABLE",HttpStatus.BAD_REQUEST);}
    }
    public static SourceManagementException failure(String code,HttpStatus status){return new SourceManagementException(code,status,"目录不可用，请手动输入路径或稍后重试");}
}
