package com.aisandbox.server.clients.service;

import java.nio.file.Path;

/**
 * Test-only bridge to the package-private {@link AllowlistDirectory#AllowlistDirectory(Path)}
 * constructor — used by tests outside the {@code clients.service} package
 * (notably the integration tier) to avoid plumbing a full
 * {@code ServerProperties} just to hand over a directory.
 */
public final class AllowlistDirectoryTestFactory {

    private AllowlistDirectoryTestFactory() {}

    public static AllowlistDirectory forDirectory(Path dir) {
        return new AllowlistDirectory(dir);
    }
}
