package com.example.s32ds.agent.bridge.auth;

import org.eclipse.core.resources.ResourcesPlugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Writes a machine-readable "discovery file" at a fixed user-profile path so that
 * MCP clients on this machine can find the bridge regardless of where the user's
 * S32DS workspace is located.
 *
 * Path: {@code %USERPROFILE%\.s32ds-mcp\bridge.json} on Windows,
 *       {@code ~/.s32ds-mcp/bridge.json} on Linux/macOS.
 *
 * Content is a minimal JSON object — we avoid bringing in a JSON library.
 */
public final class DiscoveryFile {

    public static Path path() {
        String base = System.getProperty("user.home");
        return Paths.get(base, ".s32ds-mcp", "bridge.json");
    }

    public static void write(String bridgeUrl, String bearerToken, int port) {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());

            String workspace = "";
            try {
                workspace = ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();
            } catch (Throwable ignored) {
                // Workspace may not be available during very early startup
            }

            long pid = -1;
            try { pid = ProcessHandle.current().pid(); } catch (Throwable ignored) {}

            String json = "{\n"
                    + "  \"url\": " + jsonString(bridgeUrl) + ",\n"
                    + "  \"token\": " + jsonString(bearerToken) + ",\n"
                    + "  \"port\": " + port + ",\n"
                    + "  \"workspace\": " + jsonString(workspace) + ",\n"
                    + "  \"pid\": " + pid + ",\n"
                    + "  \"createdAt\": " + jsonString(Instant.now().toString()) + "\n"
                    + "}\n";

            Files.writeString(
                    file, json,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write discovery file " + file, e);
        }
    }

    public static void deleteQuietly() {
        try {
            Files.deleteIfExists(path());
        } catch (IOException ignored) {
            // leave stale file; next startup will overwrite
        }
    }

    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    private DiscoveryFile() {}
}
