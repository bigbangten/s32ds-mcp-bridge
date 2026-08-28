package com.example.s32ds.agent.bridge.index;

import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.debug.service.IExpressions;
import org.eclipse.cdt.dsf.debug.service.IFormattedValues;
import org.eclipse.cdt.dsf.debug.service.IStack;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IExpressionManager;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IValueModification;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.core.model.IWatchExpression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.framework.FrameworkUtil;

/**
 * Phase 6: ad-hoc expression evaluation, watch-list management, and direct
 * variable-value modification.
 *
 * <p>The evaluation flow uses {@link IWatchExpression} but does <b>not</b>
 * register with the IExpressionManager — we stamp a temp watch, evaluate,
 * read the result, and dispose. This keeps the user's Expressions view clean.
 * Use {@link #addWatch(String)} when the user explicitly wants the expression
 * to stick around in that view.
 */
public final class ExpressionController {

    /** Default upper bound for one evaluation in milliseconds. */
    private static final long EVAL_TIMEOUT_MS = 5000L;

    /**
     * Evaluate an expression in the context of the first suspended thread's
     * given stack frame (default frame 0). Read-mostly, but expressions like
     * {@code my_func()} can have side effects on the target — danger gate
     * applies in the Router.
     */
    public Map<String, Object> evaluate(String expression, int frameIndex) {
        if (expression == null || expression.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "expression required");
            return out;
        }
        IStackFrame frame = pickFrame(frameIndex);
        if (frame == null) {
            Map<String, Object> dsfResult = evaluateDsf(expression, frameIndex);
            if (dsfResult != null) return dsfResult;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "no suspended thread");
            return out;
        }
        return evaluateAtFrame(expression, frame);
    }

    /** Evaluate against an already selected frame without consulting the UI. */
    Map<String, Object> evaluateAtFrame(String expression, IStackFrame frame) {
        Map<String, Object> out = new LinkedHashMap<>();
        IExpressionManager mgr = DebugPlugin.getDefault().getExpressionManager();
        IWatchExpression we = mgr.newWatchExpression(expression);
        we.setExpressionContext(frame);
        try {
            we.evaluate();
            long deadline = System.currentTimeMillis() + EVAL_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (we.hasErrors()) break;
                IValue v = we.getValue();
                if (v != null) break;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            out.put("expression", expression);
            try { out.put("frameName", frame.getName()); } catch (DebugException ignored) {}
            if (we.hasErrors()) {
                out.put("ok", false);
                out.put("errors", java.util.Arrays.asList(we.getErrorMessages()));
                return out;
            }
            IValue v = we.getValue();
            if (v == null) {
                out.put("ok", false);
                out.put("error", "evaluation timed out after " + EVAL_TIMEOUT_MS + "ms");
                return out;
            }
            out.put("ok", true);
            try { out.put("type", v.getReferenceTypeName()); } catch (DebugException ignored) {}
            try { out.put("value", v.getValueString()); } catch (DebugException ignored) {}
            try {
                if (v.hasVariables()) {
                    List<Map<String, Object>> children = new ArrayList<>();
                    for (IVariable child : v.getVariables()) {
                        Map<String, Object> cr = new LinkedHashMap<>();
                        try { cr.put("name", child.getName()); } catch (DebugException ignored) {}
                        try { cr.put("type", child.getReferenceTypeName()); } catch (DebugException ignored) {}
                        try {
                            IValue cv = child.getValue();
                            if (cv != null) cr.put("value", cv.getValueString());
                        } catch (DebugException ignored) {}
                        children.add(cr);
                    }
                    out.put("children", children);
                }
            } catch (DebugException ignored) {}
            return out;
        } finally {
            // Detach. Don't dispose — newWatchExpression isn't auto-added so dispose
            // would do little; setting context to null releases the frame ref.
            try { we.setExpressionContext(null); } catch (Throwable ignored) {}
        }
    }

    /** Add a watch expression to the IDE's Expressions view. */
    public Map<String, Object> addWatch(String expression) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (expression == null || expression.isEmpty()) {
            out.put("ok", false);
            out.put("error", "expression required");
            return out;
        }
        IExpressionManager mgr = DebugPlugin.getDefault().getExpressionManager();
        IWatchExpression we = mgr.newWatchExpression(expression);
        // If a thread is suspended, give the watch immediate context so the user
        // sees a value the moment they look. Otherwise it'll resolve on next stop.
        IStackFrame frame = pickFrame(0);
        if (frame != null) {
            we.setExpressionContext(frame);
        }
        mgr.addExpression(we);
        out.put("ok", true);
        out.put("expression", expression);
        out.put("hasContext", frame != null);
        return out;
    }

    /** Remove watch expressions whose text equals {@code expression} (or all if null). */
    public Map<String, Object> removeWatch(String expression) {
        Map<String, Object> out = new LinkedHashMap<>();
        IExpressionManager mgr = DebugPlugin.getDefault().getExpressionManager();
        IExpression[] all = mgr.getExpressions();
        int removed = 0;
        List<IExpression> kill = new ArrayList<>();
        for (IExpression e : all) {
            if (expression == null || expression.equals(e.getExpressionText())) {
                kill.add(e);
            }
        }
        for (IExpression e : kill) {
            mgr.removeExpression(e);
            removed++;
        }
        out.put("ok", true);
        out.put("removed", removed);
        return out;
    }

    /** List watch expressions currently in the IDE's Expressions view. Read-only. */
    public Map<String, Object> listWatch() {
        Map<String, Object> out = new LinkedHashMap<>();
        IExpressionManager mgr = DebugPlugin.getDefault().getExpressionManager();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IExpression e : mgr.getExpressions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("expression", e.getExpressionText());
            row.put("modelIdentifier", e.getModelIdentifier());
            try {
                IValue v = e.getValue();
                if (v != null) {
                    try { row.put("type", v.getReferenceTypeName()); } catch (DebugException ignored) {}
                    try { row.put("value", v.getValueString()); } catch (DebugException ignored) {}
                }
            } catch (Throwable ignored) {}
            if (e instanceof IWatchExpression) {
                IWatchExpression we = (IWatchExpression) e;
                row.put("enabled", we.isEnabled());
                row.put("hasErrors", we.hasErrors());
                if (we.hasErrors()) {
                    row.put("errors", java.util.Arrays.asList(we.getErrorMessages()));
                }
            }
            rows.add(row);
        }
        out.put("expressions", rows);
        out.put("count", rows.size());
        return out;
    }

    /**
     * Modify a variable in the currently suspended frame.
     * Looks up by exact name match within the frame's locals; for nested
     * struct fields use evaluate() with an assignment expression instead
     * (e.g. {@code "obj.flags = 0x80"}).
     */
    public Map<String, Object> writeVariable(String name, String value, int frameIndex) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (name == null || name.isEmpty() || value == null) {
            out.put("ok", false);
            out.put("error", "name and value required");
            return out;
        }
        IStackFrame frame = pickFrame(frameIndex);
        if (frame == null) {
            out.put("ok", false);
            out.put("error", "no suspended thread");
            return out;
        }
        try {
            for (IVariable v : frame.getVariables()) {
                if (name.equals(v.getName())) {
                    if (!(v instanceof IValueModification)) {
                        out.put("ok", false);
                        out.put("error", "variable does not support modification");
                        return out;
                    }
                    if (!v.supportsValueModification()) {
                        out.put("ok", false);
                        out.put("error", "variable.supportsValueModification() = false");
                        return out;
                    }
                    if (!v.verifyValue(value)) {
                        out.put("ok", false);
                        out.put("error", "value not accepted by debug model: " + value);
                        return out;
                    }
                    v.setValue(value);
                    out.put("ok", true);
                    out.put("name", name);
                    out.put("newValue", value);
                    try {
                        IValue post = v.getValue();
                        if (post != null) out.put("readback", post.getValueString());
                    } catch (DebugException ignored) {}
                    return out;
                }
            }
            out.put("ok", false);
            out.put("error", "variable not in current frame's locals: " + name);
            return out;
        } catch (DebugException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    // ────────── helpers ──────────

    private IStackFrame pickFrame(int frameIndex) {
        return DebugContextPicker.frame(frameIndex);
    }

    /**
     * Evaluate through CDT DSF when PEmicro exposes no generic IStackFrame.
     * General expression evaluation stays danger-gated by Router because the
     * expression may contain assignments or target function calls.
     */
    private Map<String, Object> evaluateDsf(String expression, int frameIndex) {
        DebugContextPicker.Selection selection = DebugContextPicker.suspended(frameIndex);
        if (selection == null || selection.dmContext == null) return null;

        DsfSession session = DsfSession.getSession(selection.dmContext.getSessionId());
        if (session == null || !session.isActive()) return null;
        org.osgi.framework.Bundle bundle = FrameworkUtil.getBundle(ExpressionController.class);
        if (bundle == null || bundle.getBundleContext() == null) return null;

        DsfServicesTracker tracker =
                new DsfServicesTracker(bundle.getBundleContext(), session.getId());
        try {
            final IExpressions expressions = tracker.getService(IExpressions.class);
            final IStack stack = tracker.getService(IStack.class);
            if (expressions == null || stack == null) return null;

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Map<String, Object>> result = new AtomicReference<>();
            AtomicReference<String> error = new AtomicReference<>();
            final int requestedFrame = Math.max(0, frameIndex);

            session.getExecutor().execute(new Runnable() {
                @Override public void run() {
                    IStack.IFrameDMContext frame = DMContexts.getAncestorOfType(
                            selection.dmContext, IStack.IFrameDMContext.class);
                    if (frame != null && frame.getLevel() == requestedFrame) {
                        evaluateDsfAtFrame(expressions, session, frame, expression,
                                selection, result, error, latch);
                        return;
                    }
                    stack.getFrames(selection.dmContext, requestedFrame, requestedFrame,
                            new DataRequestMonitor<IStack.IFrameDMContext[]>(
                                    session.getExecutor(), null) {
                        @Override protected void handleCompleted() {
                            if (isSuccess() && getData() != null && getData().length > 0) {
                                evaluateDsfAtFrame(expressions, session, getData()[0],
                                        expression, selection, result, error, latch);
                            } else {
                                error.set(getStatus() != null
                                        ? getStatus().getMessage()
                                        : "no DSF stack frame");
                                latch.countDown();
                            }
                        }
                    });
                }
            });

            if (!latch.await(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Map<String, Object> timedOut = new LinkedHashMap<>();
                timedOut.put("ok", false);
                timedOut.put("error", "DSF evaluation timed out after "
                        + EVAL_TIMEOUT_MS + "ms");
                return timedOut;
            }
            if (result.get() != null) return result.get();

            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("error", error.get() != null ? error.get()
                    : "DSF expression evaluation failed");
            return failed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("error", "DSF evaluation interrupted");
            return failed;
        } catch (Throwable t) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            return failed;
        } finally {
            tracker.dispose();
        }
    }

    private void evaluateDsfAtFrame(IExpressions expressions, DsfSession session,
                                    IStack.IFrameDMContext frame, String expression,
                                    DebugContextPicker.Selection selection,
                                    AtomicReference<Map<String, Object>> result,
                                    AtomicReference<String> error,
                                    CountDownLatch latch) {
        try {
            IExpressions.IExpressionDMContext expressionContext =
                    expressions.createExpression(frame, expression);
            IFormattedValues.FormattedValueDMContext formattedContext =
                    expressions.getFormattedValueContext(expressionContext,
                            IFormattedValues.NATURAL_FORMAT);
            expressions.getFormattedExpressionValue(formattedContext,
                    new DataRequestMonitor<IFormattedValues.FormattedValueDMData>(
                            session.getExecutor(), null) {
                @Override protected void handleCompleted() {
                    try {
                        if (isSuccess() && getData() != null) {
                            Map<String, Object> out = new LinkedHashMap<>();
                            out.put("ok", true);
                            out.put("expression", expression);
                            out.put("value", getData().getFormattedValue());
                            out.put("frameLevel", frame.getLevel());
                            out.put("debugContextSource", selection.source);
                            out.put("source", "dsfExpressions");
                            result.set(out);
                        } else {
                            error.set(getStatus() != null
                                    ? getStatus().getMessage()
                                    : "DSF expression returned no value");
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        } catch (Throwable t) {
            error.set(t.getClass().getSimpleName() + ": " + t.getMessage());
            latch.countDown();
        }
    }
}
