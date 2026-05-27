package com.example.s32ds.agent.bridge;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.example.s32ds.agent.bridge.auth.DiscoveryFile;
import com.example.s32ds.agent.bridge.auth.TokenStore;
import com.example.s32ds.agent.bridge.http.Router;
import com.example.s32ds.agent.bridge.util.UiThread;
import com.sun.net.httpserver.HttpServer;

public final class BridgeServer {
    public static final String BRIDGE_VERSION = "0.4.2";
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 39231;

    private static final BridgeServer INSTANCE = new BridgeServer();

    private final AtomicBoolean startRequested = new AtomicBoolean(false);
    private volatile HttpServer httpServer;
    private volatile Router router;
    private volatile TokenStore tokenStore;
    private volatile String token;
    private volatile int port;

    private BridgeServer() {
    }

    public static BridgeServer getInstance() {
        return INSTANCE;
    }

    public void startAsync() {
        if (!startRequested.compareAndSet(false, true)) {
            return;
        }

        Thread starter = new Thread(() -> {
            try {
                startInternal();
            } catch (Throwable t) {
                log("Failed to start bridge", t);
            }
        }, "s32ds-agent-bridge-starter");
        starter.setDaemon(true);
        starter.start();
    }

    public int getPort() {
        return port;
    }

    public String getToken() {
        return token;
    }

    public TokenStore getTokenStore() {
        return tokenStore;
    }

    public Map<String, Object> buildHealthData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bridgeVersion", BRIDGE_VERSION);
        data.put("bindAddress", DEFAULT_HOST);
        data.put("port", Integer.valueOf(port));
        data.put("workspace", tokenStore != null ? tokenStore.getWorkspacePath().toString() : null);
        data.put("pid", Long.valueOf(resolvePid()));

        if (PlatformUI.isWorkbenchRunning() && Display.getDefault() != null) {
            Map<String, Object> uiData = UiThread.sync(() -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("s32dsProduct", Platform.getProduct() != null ? Platform.getProduct().getName() : null);
                values.put("productId", Platform.getProduct() != null ? Platform.getProduct().getId() : null);
                values.put("eclipseVersion", Platform.getBundle("org.eclipse.ui") != null
                        ? String.valueOf(Platform.getBundle("org.eclipse.ui").getVersion())
                        : null);
                values.put("displayAvailable", Boolean.valueOf(Display.getDefault() != null));
                values.put("workbenchRunning", Boolean.TRUE);
                return values;
            });
            data.putAll(uiData);
        } else {
            data.put("s32dsProduct", Platform.getProduct() != null ? Platform.getProduct().getName() : null);
            data.put("productId", Platform.getProduct() != null ? Platform.getProduct().getId() : null);
            data.put("eclipseVersion", Platform.getBundle("org.eclipse.ui") != null
                    ? String.valueOf(Platform.getBundle("org.eclipse.ui").getVersion())
                    : null);
            data.put("displayAvailable", Boolean.FALSE);
            data.put("workbenchRunning", Boolean.valueOf(PlatformUI.isWorkbenchRunning()));
        }
        return data;
    }

    private void startInternal() throws IOException {
        this.port = resolvePort();
        this.tokenStore = new TokenStore();
        this.token = tokenStore.loadOrCreateToken();
        this.router = new Router(this, token);

        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(DEFAULT_HOST), port);
        HttpServer server = HttpServer.create(address, 0);
        server.createContext("/", router::handle);
        server.setExecutor(Executors.newCachedThreadPool(new ThreadFactory() {
            private int index = 0;

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "s32ds-agent-bridge-http-" + (++index));
                thread.setDaemon(true);
                return thread;
            }
        }));
        server.start();
        this.httpServer = server;
        log("Bridge started on " + DEFAULT_HOST + ":" + port, null);

        // Publish a user-profile discovery file so MCP clients can find the bridge
        // regardless of where the user's S32DS workspace is located. Best-effort —
        // a failure here shouldn't kill the bridge.
        try {
            DiscoveryFile.write("http://" + DEFAULT_HOST + ":" + port, token, port);
            log("Discovery file written at " + DiscoveryFile.path(), null);
        } catch (Throwable t) {
            log("Failed to write discovery file (non-fatal)", t);
        }

        // Clean up discovery file on JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(DiscoveryFile::deleteQuietly,
                "s32ds-agent-bridge-discovery-cleanup"));
    }

    private int resolvePort() {
        String raw = System.getenv("S32DS_AGENT_PORT");
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_PORT;
        }

        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            log("Invalid S32DS_AGENT_PORT: " + raw + ", falling back to " + DEFAULT_PORT, ex);
            return DEFAULT_PORT;
        }
    }

    private long resolvePid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Throwable ignored) {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            int at = runtimeName.indexOf('@');
            if (at > 0) {
                try {
                    return Long.parseLong(runtimeName.substring(0, at));
                } catch (NumberFormatException ignoredAgain) {
                    // ignore
                }
            }
            return -1L;
        }
    }

    private static void log(String message, Throwable error) {
        String prefix = "[s32ds-agent-bridge] ";
        System.err.println(new String((prefix + message).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        if (error != null) {
            error.printStackTrace(System.err);
        }
    }
}
