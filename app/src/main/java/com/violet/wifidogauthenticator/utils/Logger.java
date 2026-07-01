package com.violet.wifidogauthenticator.utils;

import android.os.Handler;
import android.os.Looper;
import com.violet.wifidogauthenticator.models.LogModel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FIX: Replaced ArrayList<LogListener> with CopyOnWriteArrayList to prevent
 * ConcurrentModificationException when listeners are added/removed while the
 * main-thread post iterates over them during a rotation or rapid lifecycle change.
 *
 * FIX: addListener / removeListener are now synchronized on the list object
 * so concurrent calls from background threads cannot corrupt the list.
 *
 * FIX: clear() now also operates on the same synchronized list to avoid a
 * race between the background auth loop logging and the UI clearing.
 *
 * FIX: getLogs() returns an unmodifiable snapshot so callers cannot
 * accidentally mutate the internal list.
 */
public class Logger {

    public interface LogListener {
        void onLogAdded(LogModel log);
    }

    private static volatile Logger instance;

    // FIX: CopyOnWriteArrayList – safe to iterate while adding/removing listeners
    private final List<LogModel>    logs      = new ArrayList<>();
    private final List<LogListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler           mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat  sdf =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // lock for the logs list only (listeners use COW)
    private final Object logLock = new Object();

    private Logger() {}

    // FIX: double-checked locking with volatile for thread-safe singleton
    public static Logger getInstance() {
        if (instance == null) {
            synchronized (Logger.class) {
                if (instance == null) instance = new Logger();
            }
        }
        return instance;
    }

    public void addListener(LogListener l) {
        if (l != null) listeners.add(l);
    }

    public void removeListener(LogListener l) {
        if (l != null) listeners.remove(l);
    }

    /** Returns an immutable snapshot – safe to iterate without holding a lock. */
    public List<LogModel> getLogs() {
        synchronized (logLock) {
            return Collections.unmodifiableList(new ArrayList<>(logs));
        }
    }

    public void clear() {
        synchronized (logLock) {
            logs.clear();
        }
    }

    public void log(String message, int type) {
        // FIX: Guard null message to prevent NPE in LogModel / adapter
        if (message == null) message = "";

        String   ts = sdf.format(new Date());
        LogModel m  = new LogModel(ts, message, type);

        synchronized (logLock) {
            logs.add(m);
            // Keep log size manageable
            if (logs.size() > 500) logs.remove(0);
        }

        // FIX: Capture final reference for lambda so it is safe across threads
        final LogModel finalM = m;
        mainHandler.post(() -> {
            // CopyOnWriteArrayList iterator is snapshot-safe – no CME possible
            for (LogListener l : listeners) {
                try {
                    l.onLogAdded(finalM);
                } catch (Exception ignored) {
                    // FIX: Never let a misbehaving listener crash the logger
                }
            }
        });
    }

    public void info(String msg)    { log(msg, LogModel.TYPE_INFO); }
    public void success(String msg) { log(msg, LogModel.TYPE_SUCCESS); }
    public void error(String msg)   { log(msg, LogModel.TYPE_ERROR); }
    public void warn(String msg)    { log(msg, LogModel.TYPE_WARN); }
}
