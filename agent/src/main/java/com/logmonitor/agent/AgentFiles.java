package com.logmonitor.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

final class AgentFiles {
    static final ObjectMapper JSON = new ObjectMapper();

    private AgentFiles() {}

    static <T> T read(Path path, Class<T> type) throws IOException {
        return JSON.readValue(path.toFile(), type);
    }

    static void writeAtomic(Path path, Object value) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (!Files.isDirectory(parent)) Files.createDirectories(parent);
        Path temporary = path.resolveSibling(path.getFileName().toString() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        moveAtomic(temporary, path);
    }

    static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void ownerOnly(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (Exception ignored) {
            // The target platform is Linux; non-POSIX test filesystems may not expose permissions.
        }
    }
}
