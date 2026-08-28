package com.example.s32ds.agent.bridge;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.example.s32ds.agent.bridge.auth.DiscoveryFile;
import com.example.s32ds.agent.bridge.auth.TokenStore;
import com.example.s32ds.agent.bridge.http.Router;
import com.sun.net.httpserver.HttpServer;

public final class BridgeServer {
    public static final String BRIDGE_VERSION = "0.4.3";
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 39231;
    private static final long HEALTH_PROBE_TIMEOUT_MS = 750L;

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

        data.putAll(probeUi());
        data.putAll(probeDsf());
        return data;
    }

    /**
     * Probe the SWT event loop without using syncExec. A wedged NXP view must
     * not make /health hang indefinitely; callers need to distinguish an HTTP
     * bridge that is alive from a responsive workbench.
     */
    private Map<String, Object> probeUi() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("s32dsProduct", Platform.getProduct() != null
                ? Platform.getProduct().getName() : null);
        data.put("productId", Platform.getProduct() != null
                ? Platform.getProduct().getId() : null);
        data.put("eclipseVersion", Platform.getBundle("org.eclipse.ui") != null
                ? String.valueOf(Platform.getBundle("org.eclipse.ui").getVersion())
                : null);
        boolean workbenchRunning = PlatformUI.isWorkbenchRunning();
        Display display = Display.getDefault();
        boolean displayAvailable = display != null && !display.isDisposed();
        data.put("displayAvailable", Boolean.valueOf(displayAvailable));
        data.put("workbenchRunning", Boolean.valueOf(workbenchRunning));
        data.put("uiProbeTimeoutMs", Long.valueOf(HEALTH_PROBE_TIMEOUT_MS));
        if (!workbenchRunning || !displayAvailable) {
            data.put("uiResponsive", Boolean.FALSE);
            data.put("uiRoundTripMs", null);
            return data;
        }

        long started = System.nanoTime();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable probe = new Runnable() {
            @Override public void run() {
                try {
                    // Reading the active window is a minimal real workbench round trip.
                    PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    latch.countDown();
                }
            }
        };
        try {
            if (Display.getCurrent() == display) probe.run();
            else display.asyncExec(probe);
            boolean completed = latch.await(HEALTH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            data.put("uiResponsive", Boolean.valueOf(completed && error.get() == null));
            data.put("uiRoundTripMs", Long.valueOf(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            if (error.get() != null) {
                data.put("uiProbeError", error.get().getClass().getSimpleName()
                        + ": " + error.get().getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            data.put("uiResponsive", Boolean.FALSE);
            data.put("uiProbeError", "interrupted");
        } catch (Throwable t) {
            data.put("uiResponsive", Boolean.FALSE);
            data.put("uiProbeError", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return data;
    }

    /** Ping every active DSF executor so health reports debugger responsiveness. */
    private Map<String, Object> probeDsf() {
        Map<String, Object> data = new LinkedHashMap<>();
        DsfSession[] sessions;
        try {
            sessions = DsfSession.getActiveSessions();
        } catch (Throwable t) {
            data.put("activeDsfSessions", Integer.valueOf(0));
            data.put("dsfResponsive", Boolean.FALSE);
            data.put("dsfProbeError", t.getClass().getSimpleName() + ": " + t.getMessage());
            return data;
        }
        data.put("activeDsfSessions", Integer.valueOf(sessions.length));
        if (sessions.length == 0) {
            data.put("dsfResponsive", Boolean.TRUE);
            data.put("dsfRoundTripMs", Long.valueOf(0L));
            return data;
        }
        CountDownLatch latch = new CountDownLatch(sessions.length);
        long started = System.nanoTime();
        try {
            for (DsfSession session : sessions) {
                if (session == null || !session.isActive()) {
                    latch.countDown();
                    continue;
                }
                session.getExecutor().execute(new Runnable() {
                    @Override public void run() {
                        latch.countDown();
                    }
                });
            }
            boolean completed = latch.await(HEALTH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            data.put("dsfResponsive", Boolean.valueOf(completed));
            data.put("dsfRoundTripMs", Long.valueOf(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            data.put("dsfResponsive", Boolean.FALSE);
            data.put("dsfProbeError", "interrupted");
        } catch (Throwable t) {
            data.put("dsfResponsive", Boolean.FALSE);
            data.put("dsfProbeError", t.getClass().getSimpleName() + ": " + t.getMessage());
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
