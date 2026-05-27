package com.example.s32ds.agent.bridge.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.s32ds.agent.bridge.BridgeServer;
import com.example.s32ds.agent.bridge.auth.DangerGate;
import com.example.s32ds.agent.bridge.exec.BuildController;
import com.example.s32ds.agent.bridge.exec.EditorController;
import com.example.s32ds.agent.bridge.exec.WorkbenchController;
import com.example.s32ds.agent.bridge.index.BreakpointController;
import com.example.s32ds.agent.bridge.index.CommandIndexer;
import com.example.s32ds.agent.bridge.index.ConsoleTail;
import com.example.s32ds.agent.bridge.index.DebugController;
import com.example.s32ds.agent.bridge.index.DebugContextDiagnostics;
import com.example.s32ds.agent.bridge.index.DebugInspector;
import com.example.s32ds.agent.bridge.index.ExpressionController;
import com.example.s32ds.agent.bridge.index.DialogInspector;
import com.example.s32ds.agent.bridge.index.LaunchConfigAnalyzer;
import com.example.s32ds.agent.bridge.index.LaunchRunner;
import com.example.s32ds.agent.bridge.index.S32dsInventory;
import com.example.s32ds.agent.bridge.index.ExtensionRegistryIndexer;
import com.example.s32ds.agent.bridge.index.MarkerIndexer;
import com.example.s32ds.agent.bridge.index.MenuMaterializer;
import com.example.s32ds.agent.bridge.index.PerspectiveIndexer;
import com.example.s32ds.agent.bridge.index.StateInspector;
import com.example.s32ds.agent.bridge.index.ViewIndexer;
import com.example.s32ds.agent.bridge.index.WizardIndexer;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

public final class Router {
    private final BridgeServer bridgeServer;
    private final String bearerToken;
    private final CommandIndexer commandIndexer = new CommandIndexer();
    private final ExtensionRegistryIndexer extensionRegistryIndexer = new ExtensionRegistryIndexer();
    private final MenuMaterializer menuMaterializer = new MenuMaterializer();
    private final StateInspector stateInspector = new StateInspector();
    private final ViewIndexer viewIndexer = new ViewIndexer();
    private final PerspectiveIndexer perspectiveIndexer = new PerspectiveIndexer();
    private final WizardIndexer wizardIndexer = new WizardIndexer();
    private final MarkerIndexer markerIndexer = new MarkerIndexer();
    private final WorkbenchController workbenchController = new WorkbenchController();
    private final BuildController buildController = new BuildController();
    private final EditorController editorController = new EditorController();
    private final S32dsInventory s32dsInventory = new S32dsInventory();
    private final LaunchConfigAnalyzer launchAnalyzer = new LaunchConfigAnalyzer();
    private final DebugInspector debugInspector = new DebugInspector();
    private final DebugContextDiagnostics debugContextDiagnostics = new DebugContextDiagnostics();
    private final DebugController debugController = new DebugController();
    private final BreakpointController breakpointController = new BreakpointController();
    private final ExpressionController expressionController = new ExpressionController();
    private final LaunchRunner launchRunner = new LaunchRunner();
    private final DialogInspector dialogInspector = new DialogInspector();
    private final ConsoleTail consoleTail = new ConsoleTail();

    public Router(BridgeServer bridgeServer, String bearerToken) {
        this.bridgeServer = bridgeServer;
        this.bearerToken = bearerToken;
    }

    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!isAuthorized(exchange.getRequestHeaders())) {
                send(exchange, 401, error("UNAUTHORIZED", "Missing or invalid bearer token", null));
                return;
            }

            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

            if ("GET".equals(method) && "/health".equals(path)) {
                send(exchange, 200, ok(bridgeServer.buildHealthData()));
                return;
            }
            if ("GET".equals(method) && "/state".equals(path)) {
                send(exchange, 200, ok(stateInspector.inspectState()));
                return;
            }
            if ("GET".equals(method) && "/commands".equals(path)) {
                send(exchange, 200, ok(commandIndexer.listCommands()));
                return;
            }
            if ("GET".equals(method) && "/commands/search".equals(path)) {
                String q = trimToNull(query.get("q"));
                if (q == null) {
                    send(exchange, 400, error("BAD_REQUEST", "Query parameter 'q' is required", null));
                    return;
                }
                send(exchange, 200, ok(commandIndexer.search(q)));
                return;
            }
            if ("GET".equals(method) && "/registry/menus".equals(path)) {
                send(exchange, 200, ok(extensionRegistryIndexer.listMenus(query.get("q"))));
                return;
            }
            if ("GET".equals(method) && "/registry/legacy-actions".equals(path)) {
                send(exchange, 200, ok(extensionRegistryIndexer.listLegacyActions(query.get("q"))));
                return;
            }
            if ("GET".equals(method) && "/views".equals(path)) {
                send(exchange, 200, ok(viewIndexer.listViews()));
                return;
            }
            if ("GET".equals(method) && "/perspectives".equals(path)) {
                send(exchange, 200, ok(perspectiveIndexer.listPerspectives()));
                return;
            }
            if ("GET".equals(method) && "/wizards".equals(path)) {
                String type = query.get("type");
                if (!wizardIndexer.isSupportedType(type)) {
                    send(exchange, 400, error("BAD_REQUEST",
                            "Query parameter 'type' must be one of new, import, export, all", null));
                    return;
                }
                send(exchange, 200, ok(wizardIndexer.listWizards(type)));
                return;
            }
            if ("GET".equals(method) && "/markers/problems".equals(path)) {
                String projectName = query.get("project");
                send(exchange, 200, ok(markerIndexer.listProblems(projectName)));
                return;
            }
            if ("POST".equals(method) && "/open-file".equals(path)) {
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Object wp = ((Map<?, ?>) parsed).get("workspacePath");
                String workspacePath = wp != null ? String.valueOf(wp).trim() : null;
                if (workspacePath == null || workspacePath.isEmpty()) {
                    send(exchange, 400, error("BAD_REQUEST", "Field 'workspacePath' is required", null));
                    return;
                }
                send(exchange, 200, ok(editorController.openFile(workspacePath)));
                return;
            }
            if ("POST".equals(method) && "/save-all".equals(path)) {
                send(exchange, 200, ok(editorController.saveAll()));
                return;
            }
            if ("GET".equals(method) && "/editors".equals(path)) {
                send(exchange, 200, ok(editorController.listOpenEditors()));
                return;
            }
            if ("GET".equals(method) && "/launch-configs".equals(path)) {
                send(exchange, 200, ok(launchAnalyzer.listAll()));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/launch-configs/") && path.endsWith("/analyze")) {
                String inner = path.substring("/launch-configs/".length(), path.length() - "/analyze".length());
                String name = URLDecoder.decode(inner, StandardCharsets.UTF_8);
                send(exchange, 200, ok(launchAnalyzer.analyze(name)));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/launch-configs/")
                    && !path.endsWith("/analyze")) {
                String inner = path.substring("/launch-configs/".length());
                String name = URLDecoder.decode(inner, StandardCharsets.UTF_8);
                send(exchange, 200, ok(launchAnalyzer.fullDetails(name)));
                return;
            }
            if ("GET".equals(method) && "/debug/sessions".equals(path)) {
                send(exchange, 200, ok(debugInspector.listSessions()));
                return;
            }
            if ("GET".equals(method) && "/debug/context".equals(path)) {
                send(exchange, 200, ok(debugContextDiagnostics.inspect()));
                return;
            }
            if ("GET".equals(method) && "/debug/stackframes".equals(path)) {
                send(exchange, 200, ok(debugInspector.stackFrames()));
                return;
            }
            if ("GET".equals(method) && "/debug/variables".equals(path)) {
                int frameIdx = 0;
                String fi = query.get("frame");
                if (fi != null) {
                    try { frameIdx = Integer.parseInt(fi); } catch (NumberFormatException ignored) {}
                }
                send(exchange, 200, ok(debugInspector.variables(frameIdx)));
                return;
            }
            if ("GET".equals(method) && "/debug/breakpoints".equals(path)) {
                send(exchange, 200, ok(debugInspector.breakpoints()));
                return;
            }
            if ("GET".equals(method) && "/debug/status".equals(path)) {
                send(exchange, 200, ok(debugInspector.status()));
                return;
            }
            if ("GET".equals(method) && "/debug/location".equals(path)) {
                send(exchange, 200, ok(debugInspector.location()));
                return;
            }
            if ("GET".equals(method) && "/debug/registers".equals(path)) {
                send(exchange, 200, ok(debugInspector.registers()));
                return;
            }
            if ("GET".equals(method) && "/debug/memory".equals(path)) {
                String addr = query.get("addr");
                if (addr == null || addr.isEmpty()) {
                    send(exchange, 400, error("BAD_REQUEST", "Query parameter 'addr' is required (hex 0x... or decimal)", null));
                    return;
                }
                int len = 64;
                String ls = query.get("length");
                if (ls != null) { try { len = Integer.parseInt(ls); } catch (NumberFormatException ignored) {} }
                send(exchange, 200, ok(debugInspector.readMemory(addr, len)));
                return;
            }
            if ("GET".equals(method) && "/dialogs/open".equals(path)) {
                send(exchange, 200, ok(dialogInspector.listShells()));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/dialogs/") && path.endsWith("/widgets")) {
                String idxStr = path.substring("/dialogs/".length(), path.length() - "/widgets".length());
                int idx;
                try { idx = Integer.parseInt(idxStr); }
                catch (NumberFormatException e) {
                    send(exchange, 400, error("BAD_REQUEST", "dialog index must be integer", null));
                    return;
                }
                int depth = 6;
                String ds = query.get("depth");
                if (ds != null) { try { depth = Integer.parseInt(ds); } catch (NumberFormatException ignored) {} }
                send(exchange, 200, ok(dialogInspector.shellWidgets(idx, depth)));
                return;
            }
            if ("GET".equals(method) && "/console/list".equals(path)) {
                send(exchange, 200, ok(consoleTail.listConsoles()));
                return;
            }
            if ("GET".equals(method) && "/console/tail".equals(path)) {
                String name = query.get("name");
                Integer idx = null;
                String ids = query.get("index");
                if (ids != null) { try { idx = Integer.parseInt(ids); } catch (NumberFormatException ignored) {} }
                int lines = 100;
                String ls = query.get("lines");
                if (ls != null) { try { lines = Integer.parseInt(ls); } catch (NumberFormatException ignored) {} }
                send(exchange, 200, ok(consoleTail.tail(name, idx, lines)));
                return;
            }
            if ("GET".equals(method) && "/s32ds/inventory".equals(path)) {
                send(exchange, 200, ok(s32dsInventory.listInventory()));
                return;
            }
            if ("GET".equals(method) && "/s32ds/config-tools".equals(path)) {
                send(exchange, 200, ok(s32dsInventory.listConfigTools()));
                return;
            }
            if ("GET".equals(method) && "/s32ds/debuggers".equals(path)) {
                send(exchange, 200, ok(s32dsInventory.listDebuggers()));
                return;
            }
            if ("GET".equals(method) && "/s32ds/toolchains".equals(path)) {
                send(exchange, 200, ok(s32dsInventory.listToolchains()));
                return;
            }
            if ("POST".equals(method) && "/build-project".equals(path)) {
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Map<?, ?> body = (Map<?, ?>) parsed;
                Object nm = body.get("projectName");
                String projectName = nm != null ? String.valueOf(nm).trim() : null;
                if (projectName == null || projectName.isEmpty()) {
                    send(exchange, 400, error("BAD_REQUEST", "Field 'projectName' is required", null));
                    return;
                }
                String kind = body.get("kind") != null ? String.valueOf(body.get("kind")) : null;
                long timeout = 300000L; // 5 min default
                Object to = body.get("timeoutMs");
                if (to instanceof Number) timeout = ((Number) to).longValue();
                send(exchange, 200, ok(buildController.buildProject(projectName, kind, timeout)));
                return;
            }
            if ("POST".equals(method) && "/show-view".equals(path)) {
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Object vid = ((Map<?, ?>) parsed).get("viewId");
                String viewId = vid != null ? String.valueOf(vid).trim() : null;
                if (viewId == null || viewId.isEmpty()) {
                    send(exchange, 400, error("BAD_REQUEST", "Field 'viewId' is required", null));
                    return;
                }
                send(exchange, 200, ok(workbenchController.showView(viewId)));
                return;
            }
            if ("POST".equals(method) && "/switch-perspective".equals(path)) {
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Object pid = ((Map<?, ?>) parsed).get("perspectiveId");
                String perspectiveId = pid != null ? String.valueOf(pid).trim() : null;
                if (perspectiveId == null || perspectiveId.isEmpty()) {
                    send(exchange, 400, error("BAD_REQUEST", "Field 'perspectiveId' is required", null));
                    return;
                }
                send(exchange, 200, ok(workbenchController.switchPerspective(perspectiveId)));
                return;
            }
            if ("POST".equals(method) && "/visible-menu".equals(path)) {
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }

                Object locationUri = ((Map<?, ?>) parsed).get("locationUri");
                String value = locationUri != null ? String.valueOf(locationUri).trim() : null;
                if (value == null || value.isEmpty()) {
                    send(exchange, 400, error("BAD_REQUEST", "Field 'locationUri' is required", null));
                    return;
                }
                send(exchange, 200, ok(menuMaterializer.materialize(value)));
                return;
            }

            // ??????????????? Phase 5: danger gate + mutating debug/launch ops ???????????????

            if ("GET".equals(method) && "/danger/state".equals(path)) {
                send(exchange, 200, ok(DangerGate.snapshot()));
                return;
            }
            if ("POST".equals(method) && "/danger/enable".equals(path)) {
                long ttl = 0L;
                try {
                    Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                    if (parsed instanceof Map<?, ?>) {
                        Object t = ((Map<?, ?>) parsed).get("ttlMs");
                        if (t instanceof Number) ttl = ((Number) t).longValue();
                        else {
                            Object s = ((Map<?, ?>) parsed).get("ttlSeconds");
                            if (s instanceof Number) ttl = ((Number) s).longValue() * 1000L;
                        }
                    }
                } catch (Exception ignored) { /* allow empty body */ }
                DangerGate.enable(ttl);
                send(exchange, 200, ok(DangerGate.snapshot()));
                return;
            }
            if ("POST".equals(method) && "/danger/disable".equals(path)) {
                DangerGate.disable();
                send(exchange, 200, ok(DangerGate.snapshot()));
                return;
            }

            // The mutators below all require the gate.
            if ("POST".equals(method) && "/debug/step".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                String kind = query.get("kind");
                send(exchange, 200, ok(debugController.step(kind)));
                return;
            }
            if ("POST".equals(method) && "/debug/resume".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                send(exchange, 200, ok(debugController.resume()));
                return;
            }
            if ("POST".equals(method) && "/debug/suspend".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                send(exchange, 200, ok(debugController.suspend()));
                return;
            }
            if ("POST".equals(method) && "/debug/terminate".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                send(exchange, 200, ok(debugController.terminate()));
                return;
            }
            if ("POST".equals(method) && "/debug/restart".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                send(exchange, 200, ok(debugController.restart()));
                return;
            }
            if ("POST".equals(method) && "/debug/breakpoints".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Map<?, ?> body = (Map<?, ?>) parsed;
                String fileSpec = strOrNull(body.get("file"));
                if (fileSpec == null) fileSpec = strOrNull(body.get("path"));
                Object ln = body.get("line");
                int line = (ln instanceof Number) ? ((Number) ln).intValue() : -1;
                String condition = strOrNull(body.get("condition"));
                send(exchange, 200, ok(breakpointController.setLineBreakpoint(fileSpec, line, condition)));
                return;
            }
            if ("DELETE".equals(method) && "/debug/breakpoints".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                if ("true".equalsIgnoreCase(query.get("all"))) {
                    send(exchange, 200, ok(breakpointController.clearAll()));
                    return;
                }
                String file = query.get("file");
                int line = -1;
                String ls = query.get("line");
                if (ls != null) { try { line = Integer.parseInt(ls); } catch (NumberFormatException ignored) {} }
                send(exchange, 200, ok(breakpointController.clearLineBreakpoint(file, line)));
                return;
            }
            if ("POST".equals(method) && "/debug/memory".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Map<?, ?> body = (Map<?, ?>) parsed;
                String addr = strOrNull(body.get("addr"));
                String hex = strOrNull(body.get("hex"));
                send(exchange, 200, ok(debugController.writeMemory(addr, hex)));
                return;
            }
            if ("POST".equals(method) && "/debug/registers".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Map<?, ?> body = (Map<?, ?>) parsed;
                String group = strOrNull(body.get("group"));
                String reg = strOrNull(body.get("name"));
                String val = strOrNull(body.get("value"));
                send(exchange, 200, ok(debugController.writeRegister(group, reg, val)));
                return;
            }
            if ("POST".equals(method) && "/launch/run".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Map<?, ?> body = (Map<?, ?>) parsed;
                String cfg = strOrNull(body.get("configName"));
                if (cfg == null) cfg = strOrNull(body.get("name"));
                String mode = strOrNull(body.get("mode"));
                send(exchange, 200, ok(launchRunner.run(cfg, mode)));
                return;
            }
            if ("POST".equals(method) && "/launch/run-with-overrides".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Map<?, ?> body = (Map<?, ?>) parsed;
                String cfg = strOrNull(body.get("configName"));
                if (cfg == null) cfg = strOrNull(body.get("name"));
                String mode = strOrNull(body.get("mode"));
                String copyName = strOrNull(body.get("copyName"));
                Object rawOverrides = body.get("overrides");
                if (rawOverrides != null && !(rawOverrides instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "Field 'overrides' must be a JSON object", null));
                    return;
                }
                Map<String, Object> overrides = new LinkedHashMap<>();
                if (rawOverrides instanceof Map<?, ?>) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) rawOverrides).entrySet()) {
                        overrides.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                send(exchange, 200, ok(launchRunner.runWithOverrides(cfg, mode, copyName, overrides)));
                return;
            }


            // ??????????????? Phase 6: expression eval / watch / variable / run-to-line ???????????????

            if ("POST".equals(method) && "/debug/evaluate".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Map<?, ?> body = (Map<?, ?>) parsed;
                String expr = strOrNull(body.get("expression"));
                int frameIdx = 0;
                Object f = body.get("frame");
                if (f instanceof Number) frameIdx = ((Number) f).intValue();
                send(exchange, 200, ok(expressionController.evaluate(expr, frameIdx)));
                return;
            }
            if ("GET".equals(method) && "/debug/watch".equals(path)) {
                send(exchange, 200, ok(expressionController.listWatch()));
                return;
            }
            if ("POST".equals(method) && "/debug/watch".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                String expr = strOrNull(((Map<?, ?>) parsed).get("expression"));
                send(exchange, 200, ok(expressionController.addWatch(expr)));
                return;
            }
            if ("DELETE".equals(method) && "/debug/watch".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                String expr = query.get("expression");
                if ("true".equalsIgnoreCase(query.get("all"))) expr = null;
                send(exchange, 200, ok(expressionController.removeWatch(expr)));
                return;
            }
            if ("POST".equals(method) && "/debug/variable".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Map<?, ?> body = (Map<?, ?>) parsed;
                String name = strOrNull(body.get("name"));
                String value = strOrNull(body.get("value"));
                int frameIdx = 0;
                Object f = body.get("frame");
                if (f instanceof Number) frameIdx = ((Number) f).intValue();
                send(exchange, 200, ok(expressionController.writeVariable(name, value, frameIdx)));
                return;
            }
            if ("POST".equals(method) && "/debug/run-to-line".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Map<?, ?> body = (Map<?, ?>) parsed;
                String file = strOrNull(body.get("file"));
                Object ln = body.get("line");
                int line = (ln instanceof Number) ? ((Number) ln).intValue() : -1;
                Object skip = body.get("skipBreakpoints");
                boolean skipBp = skip instanceof Boolean ? (Boolean) skip : false;
                send(exchange, 200, ok(debugController.runToLine(file, line, skipBp)));
                return;
            }
            if ("POST".equals(method) && "/debug/jump-to-line".equals(path)) {
                if (!DangerGate.isOn()) { sendDangerOff(exchange); return; }
                Object parsed = Json.parse(readBody(exchange.getRequestBody()));
                if (!(parsed instanceof Map<?, ?>)) {
                    send(exchange, 400, error("BAD_REQUEST", "JSON object body is required", null));
                    return;
                }
                Map<?, ?> body = (Map<?, ?>) parsed;
                String file = strOrNull(body.get("file"));
                Object ln = body.get("line");
                int line = (ln instanceof Number) ? ((Number) ln).intValue() : -1;
                send(exchange, 200, ok(debugController.jumpToLine(file, line)));
                return;
            }

            send(exchange, 404, error("NOT_FOUND", "No route for " + method + " " + path, null));
        } catch (IllegalArgumentException e) {
            send(exchange, 400, error("BAD_REQUEST", e.getMessage(), null));
        } catch (Throwable t) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("exception", t.getClass().getName());
            send(exchange, 500, error("INTERNAL_ERROR", t.getMessage(), details));
        } finally {
            exchange.close();
        }
    }

    private boolean isAuthorized(Headers headers) {
        String header = headers.getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String supplied = header.substring("Bearer ".length()).trim();
        return !supplied.isEmpty() && supplied.equals(bearerToken);
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", Boolean.TRUE);
        body.put("data", data);
        body.put("warnings", new ArrayList<>());
        body.put("error", null);
        return body;
    }

    private Map<String, Object> error(String code, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", Boolean.FALSE);
        body.put("data", null);
        body.put("warnings", new ArrayList<>());

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("details", details);
        body.put("error", error);
        return body;
    }

    private void send(HttpExchange exchange, int statusCode, Map<String, Object> body) throws IOException {
        byte[] payload = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private String readBody(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = inputStream.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            String value = equals >= 0 ? pair.substring(equals + 1) : "";
            query.put(urlDecode(key), urlDecode(value));
        }
        return query;
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String strOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private void sendDangerOff(HttpExchange exchange) throws IOException {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dangerState", DangerGate.snapshot());
        details.put("hint", "POST /danger/enable {\"ttlMs\": <ms>} or run /s32:danger on first.");
        send(exchange, 403, error("DANGER_OFF",
                "Mutating operation requires danger mode. It is currently OFF.", details));
    }

    private static final class Json {
        private Json() {
        }

        static String stringify(Object value) {
            StringBuilder builder = new StringBuilder();
            append(builder, value);
            return builder.toString();
        }

        static Object parse(String text) {
            Parser parser = new Parser(text == null ? "" : text);
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (!parser.isEnd()) {
                throw new IllegalArgumentException("Unexpected trailing content in JSON body");
            }
            return value;
        }

        private static void append(StringBuilder builder, Object value) {
            if (value == null) {
                builder.append("null");
            } else if (value instanceof String) {
                appendString(builder, (String) value);
            } else if (value instanceof Number || value instanceof Boolean) {
                builder.append(String.valueOf(value));
            } else if (value instanceof Map<?, ?>) {
                builder.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!first) {
                        builder.append(',');
                    }
                    appendString(builder, String.valueOf(entry.getKey()));
                    builder.append(':');
                    append(builder, entry.getValue());
                    first = false;
                }
                builder.append('}');
            } else if (value instanceof Iterable<?>) {
                builder.append('[');
                boolean first = true;
                for (Object item : (Iterable<?>) value) {
                    if (!first) {
                        builder.append(',');
                    }
                    append(builder, item);
                    first = false;
                }
                builder.append(']');
            } else if (value.getClass().isArray()) {
                builder.append('[');
                int length = java.lang.reflect.Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    append(builder, java.lang.reflect.Array.get(value, i));
                }
                builder.append(']');
            } else {
                appendString(builder, String.valueOf(value));
            }
        }

        private static void appendString(StringBuilder builder, String value) {
            builder.append('"');
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                switch (ch) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", Integer.valueOf(ch)));
                    } else {
                        builder.append(ch);
                    }
                    break;
                }
            }
            builder.append('"');
        }

        private static final class Parser {
            private final String text;
            private int index;

            Parser(String text) {
                this.text = text;
            }

            Object parseValue() {
                skipWhitespace();
                if (isEnd()) {
                    throw new IllegalArgumentException("Empty JSON body");
                }
                char ch = text.charAt(index);
                switch (ch) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (ch == '-' || Character.isDigit(ch)) {
                        return parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character in JSON body: " + ch);
                }
            }

            Map<String, Object> parseObject() {
                Map<String, Object> object = new LinkedHashMap<>();
                expect('{');
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    return object;
                }
                while (true) {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    expect(':');
                    Object value = parseValue();
                    object.put(key, value);
                    skipWhitespace();
                    if (peek(',')) {
                        expect(',');
                        continue;
                    }
                    expect('}');
                    return object;
                }
            }

            List<Object> parseArray() {
                List<Object> array = new ArrayList<>();
                expect('[');
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    return array;
                }
                while (true) {
                    array.add(parseValue());
                    skipWhitespace();
                    if (peek(',')) {
                        expect(',');
                        continue;
                    }
                    expect(']');
                    return array;
                }
            }

            String parseString() {
                expect('"');
                StringBuilder builder = new StringBuilder();
                while (!isEnd()) {
                    char ch = text.charAt(index++);
                    if (ch == '"') {
                        return builder.toString();
                    }
                    if (ch == '\\') {
                        if (isEnd()) {
                            throw new IllegalArgumentException("Invalid JSON escape");
                        }
                        char escaped = text.charAt(index++);
                        switch (escaped) {
                        case '"':
                        case '\\':
                        case '/':
                            builder.append(escaped);
                            break;
                        case 'b':
                            builder.append('\b');
                            break;
                        case 'f':
                            builder.append('\f');
                            break;
                        case 'n':
                            builder.append('\n');
                            break;
                        case 'r':
                            builder.append('\r');
                            break;
                        case 't':
                            builder.append('\t');
                            break;
                        case 'u':
                            builder.append(parseUnicode());
                            break;
                        default:
                            throw new IllegalArgumentException("Unsupported JSON escape: \\" + escaped);
                        }
                    } else {
                        builder.append(ch);
                    }
                }
                throw new IllegalArgumentException("Unterminated JSON string");
            }

            Number parseNumber() {
                int start = index;
                if (peek('-')) {
                    index++;
                }
                while (!isEnd() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
                if (!isEnd() && text.charAt(index) == '.') {
                    index++;
                    while (!isEnd() && Character.isDigit(text.charAt(index))) {
                        index++;
                    }
                }
                String raw = text.substring(start, index);
                if (raw.contains(".")) {
                    return Double.valueOf(raw);
                }
                try {
                    return Long.valueOf(raw);
                } catch (NumberFormatException e) {
                    return Double.valueOf(raw);
                }
            }

            char parseUnicode() {
                if (index + 4 > text.length()) {
                    throw new IllegalArgumentException("Invalid unicode escape");
                }
                String hex = text.substring(index, index + 4);
                index += 4;
                return (char) Integer.parseInt(hex, 16);
            }

            void skipWhitespace() {
                while (!isEnd() && Character.isWhitespace(text.charAt(index))) {
                    index++;
                }
            }

            void expect(char expected) {
                skipWhitespace();
                if (isEnd() || text.charAt(index) != expected) {
                    throw new IllegalArgumentException("Expected '" + expected + "' in JSON body");
                }
                index++;
            }

            void expect(String expected) {
                if (!text.startsWith(expected, index)) {
                    throw new IllegalArgumentException("Expected '" + expected + "' in JSON body");
                }
                index += expected.length();
            }

            boolean peek(char expected) {
                return !isEnd() && text.charAt(index) == expected;
            }

            boolean isEnd() {
                return index >= text.length();
            }
        }
    }
}
