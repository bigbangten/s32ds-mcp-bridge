package com.example.s32ds.agent.bridge.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;

public final class TokenStore {
    private static final String PLUGIN_ID = "com.example.s32ds.agent.bridge";

    private final Path workspacePath;
    private final Path tokenPath;

    public TokenStore() {
        this.workspacePath = Path.of(ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString());
        this.tokenPath = workspacePath.resolve(".metadata").resolve(".plugins").resolve(PLUGIN_ID).resolve("token");
    }

    public Path getWorkspacePath() {
        return workspacePath;
    }

    public Path getTokenPath() {
        return tokenPath;
    }

    public String loadOrCreateToken() {
        String envToken = trimToNull(System.getenv("S32DS_AGENT_TOKEN"));
        if (envToken != null) {
            return envToken;
        }

        try {
            Files.createDirectories(tokenPath.getParent());
            if (Files.exists(tokenPath)) {
                String existing = trimToNull(Files.readString(tokenPath, StandardCharsets.UTF_8));
                if (existing != null) {
                    return existing;
                }
            }

            String token = generateToken();
            Files.writeString(tokenPath, token + System.lineSeparator(), StandardCharsets.UTF_8);
            applyWindowsAcl(tokenPath);
            return token;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize token file: " + tokenPath, e);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void applyWindowsAcl(Path path) {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("win")) {
            return;
        }

        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) {
                return;
            }

            UserPrincipal owner = Files.getOwner(path);
            EnumSet<AclEntryPermission> permissions = EnumSet.of(
                    AclEntryPermission.READ_DATA,
                    AclEntryPermission.WRITE_DATA,
                    AclEntryPermission.APPEND_DATA,
                    AclEntryPermission.READ_ATTRIBUTES,
                    AclEntryPermission.WRITE_ATTRIBUTES,
                    AclEntryPermission.READ_ACL,
                    AclEntryPermission.WRITE_ACL,
                    AclEntryPermission.SYNCHRONIZE);

            List<AclEntry> entries = new ArrayList<>();
            entries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(permissions)
                    .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    .build());
            view.setAcl(entries);
        } catch (IOException e) {
            // TODO(s32ds): hard-fail 여부는 실제 사용자 환경 검증 후 결정한다.
            System.err.println("[s32ds-agent-bridge] Unable to tighten token ACL: " + e.getMessage());
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
