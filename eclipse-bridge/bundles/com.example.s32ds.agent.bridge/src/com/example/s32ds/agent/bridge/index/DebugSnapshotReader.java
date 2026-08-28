package com.example.s32ds.agent.bridge.index;

import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.debug.service.IExpressions;
import org.eclipse.cdt.dsf.debug.service.IFormattedValues;
import org.eclipse.cdt.dsf.debug.service.IStack;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.debug.core.model.IStackFrame;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Reads a fresh, same-stop snapshot of side-effect-free C variable paths.
 *
 * <p>This deliberately accepts a much smaller grammar than the general
 * expression evaluator. Function calls, assignments, arithmetic, casts, and
 * pointer dereferences are rejected, allowing the endpoint to remain
 * read-only and outside the danger gate. Values are fetched directly through
 * DSF rather than the Expressions view, so no persistent watches or UI
 * activation are involved.</p>
 */
public final class DebugSnapshotReader {
    private static final int MAX_EXPRESSIONS = 128;
    private static final int MAX_EXPRESSION_LENGTH = 256;
    private static final Pattern SAFE_PATH = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*(?:(?:\\[[0-9]+\\])|(?:\\.[A-Za-z_][A-Za-z0-9_]*))*$");

    public Map<String, Object> snapshot(List<String> requested, int frameIndex,
                                        String format, String configName,
                                        String sessionId, String launchId) {
        long started = System.nanoTime();
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> expressions;
        try {
            expressions = validate(requested);
        } catch (IllegalArgumentException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }

        String normalizedFormat;
        try {
            normalizedFormat = normalizeFormat(format);
        } catch (IllegalArgumentException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }

        int normalizedFrame = Math.max(0, frameIndex);
        DebugSessionSelector.Selector selector =
                new DebugSessionSelector.Selector(configName, sessionId, launchId);
        DebugContextPicker.Selection selection =
                DebugSessionSelector.select(selector, normalizedFrame, true);
        if (selection == null) {
            out.put("ok", false);
            String problem = DebugSessionSelector.selectionProblem(selector);
            out.put("error", problem != null
                    ? problem : "matching debug session is not suspended");
            selectorDetails(out, selector);
            return out;
        }
        DebugSessionSelector.annotate(out, selection);
        out.put("frame", normalizedFrame);
        out.put("format", normalizedFormat);
        out.put("requestedCount", expressions.size());

        List<Map<String, Object>> values;
        if (selection.dmContext != null) {
            values = snapshotDsf(selection, expressions, normalizedFrame,
                    normalizedFormat, out);
        } else if (selection.frame != null) {
            values = snapshotGeneric(selection.frame, expressions);
            out.put("source", "genericWatchExpression");
        } else {
            out.put("ok", false);
            out.put("error", "selected session exposes neither a DSF context nor a stack frame");
            return out;
        }

        int successes = 0;
        for (Map<String, Object> value : values) {
            if (Boolean.TRUE.equals(value.get("ok"))) successes++;
        }
        out.put("values", values);
        out.put("successCount", successes);
        out.put("failureCount", values.size() - successes);
        out.put("ok", successes == values.size() && values.size() == expressions.size());
        out.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return out;
    }

    private List<Map<String, Object>> snapshotDsf(DebugContextPicker.Selection selection,
                                                  List<String> expressions,
                                                  int frameIndex,
                                                  String format,
                                                  Map<String, Object> metadata) {
        List<Map<String, Object>> values = new ArrayList<>();
        DsfSession session = DsfSession.getSession(selection.dmContext.getSessionId());
        if (session == null || !session.isActive()) {
            metadata.put("error", "DSF session is not active: "
                    + selection.dmContext.getSessionId());
            return values;
        }
        Bundle bundle = FrameworkUtil.getBundle(DebugSnapshotReader.class);
        if (bundle == null || bundle.getBundleContext() == null) {
            metadata.put("error", "bridge bundle context unavailable");
            return values;
        }
        DsfServicesTracker tracker = new DsfServicesTracker(bundle.getBundleContext(), session.getId());
        try {
            final IStack stack = tracker.getService(IStack.class);
            final IExpressions expressionService = tracker.getService(IExpressions.class);
            if (stack == null || expressionService == null) {
                metadata.put("error", "required DSF IStack/IExpressions service unavailable");
                return values;
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> fatalError = new AtomicReference<>();
            session.getExecutor().execute(new Runnable() {
                @Override public void run() {
                    IStack.IFrameDMContext existing = DMContexts.getAncestorOfType(
                            selection.dmContext, IStack.IFrameDMContext.class);
                    if (existing != null && existing.getLevel() == frameIndex) {
                        evaluateNext(expressionService, session, existing, expressions,
                                format, 0, values, latch);
                        return;
                    }
                    stack.getFrames(selection.dmContext, frameIndex, frameIndex,
                            new DataRequestMonitor<IStack.IFrameDMContext[]>(
                                    session.getExecutor(), null) {
                        @Override protected void handleCompleted() {
                            if (isSuccess() && getData() != null && getData().length > 0) {
                                evaluateNext(expressionService, session, getData()[0],
                                        expressions, format, 0, values, latch);
                            } else {
                                fatalError.set(getStatus() != null
                                        ? getStatus().getMessage()
                                        : "no DSF frame at index " + frameIndex);
                                latch.countDown();
                            }
                        }
                    });
                }
            });

            long timeoutSeconds = Math.max(5L,
                    Math.min(45L, 5L + expressions.size() * 2L));
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                metadata.put("error", "DSF snapshot timed out after "
                        + timeoutSeconds + "s");
            } else if (fatalError.get() != null) {
                metadata.put("error", fatalError.get());
            }
            metadata.put("source", "dsfExpressions");
            metadata.put("sessionId", session.getId());
            return values;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metadata.put("error", "snapshot interrupted");
            return values;
        } catch (Throwable t) {
            metadata.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            return values;
        } finally {
            tracker.dispose();
        }
    }

    private void evaluateNext(IExpressions service, DsfSession session,
                              IStack.IFrameDMContext frame,
                              List<String> expressions, String format, int index,
                              List<Map<String, Object>> values,
                              CountDownLatch latch) {
        if (index >= expressions.size()) {
            latch.countDown();
            return;
        }
        String expression = expressions.get(index);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("expression", expression);
        values.add(row);

        IExpressions.IExpressionDMContext expressionContext =
                service.createExpression(frame, expression);
        service.getExpressionData(expressionContext,
                new DataRequestMonitor<IExpressions.IExpressionDMData>(
                        session.getExecutor(), null) {
            @Override protected void handleCompleted() {
                if (isSuccess() && getData() != null) {
                    try { row.put("type", getData().getTypeName()); } catch (Throwable ignored) {}
                    try { row.put("typeId", getData().getTypeId()); } catch (Throwable ignored) {}
                    try { row.put("basicType", String.valueOf(getData().getBasicType())); }
                    catch (Throwable ignored) {}
                } else if (getStatus() != null) {
                    row.put("typeError", getStatus().getMessage());
                }

                IFormattedValues.FormattedValueDMContext formatted =
                        service.getFormattedValueContext(expressionContext, format);
                service.getFormattedExpressionValue(formatted,
                        new DataRequestMonitor<IFormattedValues.FormattedValueDMData>(
                                session.getExecutor(), null) {
                    @Override protected void handleCompleted() {
                        if (isSuccess() && getData() != null) {
                            row.put("ok", true);
                            row.put("value", getData().getFormattedValue());
                            try { row.put("editableValue", getData().getEditableValue()); }
                            catch (Throwable ignored) {}
                        } else {
                            row.put("ok", false);
                            row.put("error", getStatus() != null
                                    ? getStatus().getMessage() : "value unavailable");
                        }
                        evaluateNext(service, session, frame, expressions, format,
                                index + 1, values, latch);
                    }
                });
            }
        });
    }

    private List<Map<String, Object>> snapshotGeneric(IStackFrame frame,
                                                      List<String> expressions) {
        List<Map<String, Object>> values = new ArrayList<>();
        ExpressionController controller = new ExpressionController();
        for (String expression : expressions) {
            values.add(controller.evaluateAtFrame(expression, frame));
        }
        return values;
    }

    private List<String> validate(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("expressions must contain at least one variable path");
        }
        if (requested.size() > MAX_EXPRESSIONS) {
            throw new IllegalArgumentException("expressions exceeds " + MAX_EXPRESSIONS
                    + " item cap");
        }
        List<String> out = new ArrayList<>();
        for (String raw : requested) {
            String value = raw != null ? raw.trim() : "";
            if (value.isEmpty() || value.length() > MAX_EXPRESSION_LENGTH
                    || !SAFE_PATH.matcher(value).matches()) {
                throw new IllegalArgumentException("unsafe variable path: " + value
                        + "; only identifiers, .fields, and numeric [index] access are allowed");
            }
            out.add(value);
        }
        return out;
    }

    private String normalizeFormat(String requested) {
        String value = requested == null ? "natural"
                : requested.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "natural".equals(value)) return IFormattedValues.NATURAL_FORMAT;
        if ("hex".equals(value) || "hexadecimal".equals(value)) return IFormattedValues.HEX_FORMAT;
        if ("decimal".equals(value) || "dec".equals(value)) return IFormattedValues.DECIMAL_FORMAT;
        if ("binary".equals(value) || "bin".equals(value)) return IFormattedValues.BINARY_FORMAT;
        if ("octal".equals(value) || "oct".equals(value)) return IFormattedValues.OCTAL_FORMAT;
        if ("string".equals(value)) return IFormattedValues.STRING_FORMAT;
        throw new IllegalArgumentException("format must be natural, hex, decimal, binary, octal, or string");
    }

    private void selectorDetails(Map<String, Object> out,
                                 DebugSessionSelector.Selector selector) {
        if (selector.configName != null) out.put("configName", selector.configName);
        if (selector.sessionId != null) out.put("sessionId", selector.sessionId);
        if (selector.launchId != null) out.put("launchId", selector.launchId);
    }
}
