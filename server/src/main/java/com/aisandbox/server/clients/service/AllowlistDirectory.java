package com.aisandbox.server.clients.service;

import com.aisandbox.server.config.ServerProperties;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Thin filesystem wrapper over the allowlist directory ({@code ai-sandbox.server.clients.dir}).
 * Exists so unit tests can substitute a fake without touching disk.
 *
 * <p>Only operations meaningful to the watcher and the facade live here:
 * list cert files, read one as text, write a new one atomically, delete by
 * stem. No business logic.
 */
@Component
public class AllowlistDirectory {

    private final Path dir;

    @Autowired
    public AllowlistDirectory(ServerProperties props) {
        this(props.clients().dir());
    }

    /** Test constructor. */
    AllowlistDirectory(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** Lists every {@code *.crt} file. Returns sorted-by-name for determinism. */
    public List<Path> listCerts() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.crt")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    out.add(p);
                }
            }
        }
        out.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return out;
    }

    public String readCertPem(Path p) throws IOException {
        return Files.readString(p);
    }

    public FileTime mtime(Path p) throws IOException {
        return Files.getLastModifiedTime(p);
    }

    /**
     * Atomic write: tmp + rename. The {@code name} is the filename stem; we
     * always append {@code .crt}. Throws {@link IOException} if the file
     * already exists (caller responsibility to revoke first if updating).
     */
    public void write(String name, String certPem) throws IOException {
        String stem = sanitize(name);
        Path target = dir.resolve(stem + ".crt");
        if (Files.exists(target)) {
            throw new IOException("Allowlist entry already exists: " + target);
        }
        Path tmp = dir.resolve("." + stem + ".crt.tmp");
        Files.writeString(tmp, certPem);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException io) {
            Files.deleteIfExists(tmp);
            throw io;
        }
    }

    /** Returns true iff a file was actually deleted. */
    public boolean deleteByName(String name) throws IOException {
        Path target = dir.resolve(sanitize(name) + ".crt");
        return Files.deleteIfExists(target);
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Client name must not be blank");
        }
        if (!name.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Client name must match [A-Za-z0-9._-]+, got: " + name);
        }
        return name;
    }
}
