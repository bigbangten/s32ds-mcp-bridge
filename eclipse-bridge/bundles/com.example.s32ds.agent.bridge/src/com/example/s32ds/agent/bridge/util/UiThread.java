package com.example.s32ds.agent.bridge.util;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;

public final class UiThread {
    private UiThread() {
    }

    public static <T> T sync(Callable<T> callable) {
        Display display = Display.getDefault();
        if (display == null) {
            throw new IllegalStateException("SWT Display is not available");
        }

        if (Display.getCurrent() == display) {
            try {
                return callable.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("UI task failed", e);
            }
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        display.syncExec(() -> {
            try {
                result.set(callable.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });

        if (error.get() != null) {
            Throwable throwable = error.get();
            if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            }
            throw new IllegalStateException("UI task failed", throwable);
        }
        return result.get();
    }

    public static void async(Runnable runnable) {
        Display display = Display.getDefault();
        if (display == null) {
            throw new IllegalStateException("SWT Display is not available");
        }
        display.asyncExec(runnable);
    }
}
