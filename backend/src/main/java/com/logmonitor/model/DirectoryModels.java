package com.logmonitor.model;

import java.util.List;

public final class DirectoryModels {
    private DirectoryModels() {}
    public record Entry(String name, String path) {}
    public record Listing(String path, String parentPath, List<Entry> directories, boolean truncated) {}
    public record Request(String requestId, String path, String query, long deadlineEpochMillis) {}
    public record Result(String requestId, Listing listing, String errorCode) {}
}
