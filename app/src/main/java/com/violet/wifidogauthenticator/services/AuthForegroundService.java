package com.violet.wifidogauthenticator.services;

import android.app.Notification;
import android.net.Uri;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import com.violet.wifidogauthenticator.R;
import com.violet.wifidogauthenticator.activities.MainActivity;
import com.violet.wifidogauthenticator.utils.CryptoService;
import com.violet.wifidogauthenticator.utils.Logger;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground service that runs the infinite authentication loop.
 *
 * FIX [1]: startLoop() uses AtomicBoolean.getAndSet(true) as a CAS guard –
 *          idempotent on repeated ACTION_START commands.
 * FIX [2]: stopLoop() uses getAndSet(false) – idempotent, executes ONCE only.
 *          onDestroy() no longer calls stopLoop() redundantly.
 * FIX [3]: waitForResult() replaced with CountDownLatch – status updates
 *          immediately when the OkHttp callback fires, not after 22 s sleep.
 * FIX [4]: authLoop tracks consecutive failures; auto-stops after MAX_FAILURES.
 * FIX [5]: inner sleep loops check running flag every second so stop is instant.
 * FIX [6]: Broadcast receiver button-state sync no longer re-enables Start on
 *          transient FAIL – only "Stopped"/"Idle" flip isServiceRunning to false.
 */
public class AuthForegroundService extends Service {

    // ----------- Notification -----------
    public static final String CHANNEL_ID      = "wifidog_auth_channel";
    public static final int    NOTIFICATION_ID = 1001;

    // ----------- Intent actions -----------
    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP  = "ACTION_STOP";

    // ----------- Broadcast to UI -----------
    public static final String BROADCAST_UPDATE    = "com.violet.wifidogauthenticator.UPDATE";
    public static final String EXTRA_REQUEST_COUNT = "requestCount";
    public static final String EXTRA_LAST_TIME     = "lastTime";
    public static final String EXTRA_SESSION_ID    = "sessionId";
    public static final String EXTRA_STATUS        = "status";

    // ----------- SharedPreferences keys -----------
    public static final String PREFS_NAME     = "wifidog_prefs";
    public static final String KEY_IP         = "ip";
    public static final String KEY_ENC_URL    = "enc_url";
    public static final String KEY_SESSION_ID = "session_id";
    public static final String KEY_RUNNING    = "running";

    // FIX [4]: Auto-stop after this many consecutive failures
    private static final int MAX_CONSECUTIVE_FAILURES = 10;

    // ----------- State -----------
    private final AtomicBoolean running      = new AtomicBoolean(false);
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private ExecutorService     executor;
    private PowerManager.WakeLock wakeLock;

    private final AuthService     authService = new AuthService();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private String currentSessionId = "None";
    private String currentStatus    = "Idle";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // FIX: intent can be null when system restarts a START_STICKY service
        if (intent == null) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_RUNNING, false)) {
                startLoop();
            }
            return START_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            startLoop();
        } else if (ACTION_STOP.equals(action)) {
            stopLoop();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"));
        acquireWakeLock();
    }

    @Override
    public void onDestroy() {
        // FIX [2]: Do NOT call stopLoop() here – it is already guarded by AtomicBoolean
        // and would log a spurious "already stopped – ignored" on every normal stop.
        // The executor is shut down inside stopLoop(); nothing more needed here.
        releaseWakeLock();
        super.onDestroy();
    }

    // -----------------------------------------------------------------------
    // Start / Stop
    // -----------------------------------------------------------------------

    /**
     * BEFORE: called stopLoop() from onDestroy() causing double-stop log spam.
     *         No fail-safe for consecutive failures.
     * AFTER:  atomic CAS guard; onDestroy() no longer calls this; fail-safe added.
     */
    private void startLoop() {
        // FIX [1]: AtomicBoolean CAS – if already true, duplicate start; bail immediately.
        if (running.getAndSet(true)) {
            Logger.getInstance().warn("[!] startLoop called while already running – ignored");
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String ip     = prefs.getString(KEY_IP, "");
        String encUrl = prefs.getString(KEY_ENC_URL, "");

        if (ip == null || ip.isEmpty() || encUrl == null || encUrl.isEmpty()) {
            Logger.getInstance().error("[!] IP or URL not configured – stopping");
            running.set(false);
            stopSelf();
            return;
        }

        // ---------------------------------------------------------------
        // STRICT SECURITY MODE (service-side defence-in-depth):
        // ---------------------------------------------------------------
        String resolvedUrl = encUrl;

        if (!resolvedUrl.trim().toLowerCase().startsWith("http://")
                && !resolvedUrl.trim().toLowerCase().startsWith("https://")) {
            Logger.getInstance().warn("Stored URL is not a plain URL – attempting decryption.");
            String attempt = CryptoService.decrypt(resolvedUrl);
            if (attempt == null || attempt.isEmpty()) {
                Logger.getInstance().error("Service-side decryption failed – ABORTING. No fallback.");
                running.set(false);
                stopSelf();
                return;
            }
            resolvedUrl = attempt;
            Logger.getInstance().success("Service-side decryption succeeded.");
        }

        if (!isValidHttpUrl(resolvedUrl)) {
            Logger.getInstance().error("Resolved URL is not a valid HTTP/HTTPS URL – ABORTING.");
            running.set(false);
            stopSelf();
            return;
        }

        prefs.edit()
             .putString(KEY_ENC_URL, resolvedUrl)
             .putBoolean(KEY_RUNNING, true)
             .apply();

        final String safeUrl = resolvedUrl;
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> authLoop(ip, safeUrl));
        updateNotification("Running…");
        Logger.getInstance().info("[*] Auth loop started");
    }

    /**
     * BEFORE: Called from both ACTION_STOP and onDestroy() – ran twice per stop,
     *         causing "already stopped – ignored" log and double stopSelf().
     * AFTER:  getAndSet(false) is the single gate – executes body EXACTLY ONCE.
     *         onDestroy() no longer calls this.
     */
    private void stopLoop() {
        // FIX [2]: getAndSet(false) returns the OLD value.
        // If old value was false → already stopped → ignore silently.
        // If old value was true  → we are the one true stop → proceed.
        if (!running.getAndSet(false)) {
            Logger.getInstance().warn("[!] stopLoop called while already stopped – ignored");
            return;
        }

        ExecutorService ex = executor;
        if (ex != null) {
            ex.shutdownNow();
            executor = null;
        }
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_RUNNING, false).apply();
        } catch (Exception e) {
            // SharedPreferences write failure is non-fatal
        }
        updateNotification("Stopped");
        currentStatus = "Stopped";
        broadcastUpdate();
        Logger.getInstance().warn("[*] Auth loop stopped");
        try { stopForeground(true); } catch (Exception ignored) {}
        try { stopSelf();          } catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // Core auth loop – runs on executor thread (NOT main thread)
    // -----------------------------------------------------------------------

    /**
     * BEFORE: Inner sleep was a fixed 22 s Thread.sleep regardless of result.
     *         No consecutive failure counter – loop ran forever on repeated errors.
     *         sleepSeconds() did not check running flag – stop was slow.
     * AFTER:  CountDownLatch-based wait gives immediate status on callback.
     *         consecutiveFailures counter auto-stops after MAX_CONSECUTIVE_FAILURES.
     *         interruptibleSleep() exits instantly when running becomes false.
     */
    private void authLoop(String ip, String sessionUrl) {
        String previousSessionId = null;
        try {
            previousSessionId = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_SESSION_ID, null);
        } catch (Exception e) {
            // Non-fatal – start with null previousSessionId
        }

        // FIX [4]: Track consecutive failures for fail-safe auto-stop
        int consecutiveFailures = 0;

        while (running.get()) {

            // ---- Phase 1: get sessionId ----
            //String sessionId = fetchSessionIdBlocking(sessionUrl, previousSessionId);

            if (!running.get()) break;

            // ---- Phase 1: get sessionId ----
            String sessionId = null; // ပြင်ဆင်ချက် - Variable ကို Loop အပြင်ဘက်တွင် ကြေညာပါ

            while (true) { // ပြင်ဆင်ချက် - ခွင်းစခွင်းပိတ် ထည့်ပါ
                if (!running.get()) break;
                
                sessionId = fetchSessionIdBlocking(sessionUrl, previousSessionId);
                
                if (sessionId == null) {
                    Logger.getInstance().warn("[!] Session ID not found – retrying in 5 s");
                    currentStatus = "Retrying session…";
                    broadcastUpdate();
                    interruptibleSleep(5);
                } else if ("sessionid_limited".equals(sessionId)) {
                    Logger.getInstance().error("[!] Session ID Request Rate Limited: Retrying in 120s");
                    currentStatus = "Session ID Rate Limited";
                    broadcastUpdate();
                    interruptibleSleep(120);
                } else {
                    break; // မှန်ကန်သော Session ID ရပါက Loop မှထွက်မည်
                }
            }

            // 'Stop' နှိပ်လိုက်လို့ Loop ထဲက ထွက်လာခဲ့ရင် အောက်ကကုဒ်တွေ ဆက်မလုပ်အောင် တားပေးခြင်း
            if (!running.get() || sessionId == null) break; 

            // ယခုအခါ sessionId ကို အောက်တွင် ပြဿနာမရှိ ဆက်လက်အသုံးပြုနိုင်ပါပြီ
            previousSessionId = sessionId;
            currentSessionId  = sessionId;
            saveSessionId(sessionId);
            broadcastUpdate();

            // ---- Phase 2: send 3 auth requests 10 s apart ----
            for (int i = 0; i < 6 && running.get(); i++) {

                // FIX [3]: Use CountDownLatch so result is available immediately
                //          when OkHttp callback fires – no fixed 22 s sleep.
                final boolean[]        result   = {false};
                final String[]         finalUrl = {null};
                final CountDownLatch   latch    = new CountDownLatch(1);

                authService.sendAuth(ip, sessionId, (success, url) -> {
                    result[0]   = success;
                    finalUrl[0] = url;
                    latch.countDown();   // ← unblocks immediately
                });

                // Wait up to 22 s for the callback; interrupted if stop fires
                try {
                    latch.await(22, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                if (!running.get()) break;

                int    count = requestCount.incrementAndGet();
                String time  = sdf.format(new Date());

                // FIX [3]: Status broadcast fires immediately here, not 22 s later
                if (result[0]) {
                    currentStatus = "SUCCESS ✓";
                    consecutiveFailures = 0;                     // FIX [4]: reset on success
                } else {
                    currentStatus = "FAILED ✗";
                    consecutiveFailures++;                       // FIX [4]: increment on fail
                }

                broadcastUpdate(count, time, currentSessionId, currentStatus);
                updateNotification(currentStatus + "  request-" + count);

                // FIX [4]: Auto-stop after MAX_CONSECUTIVE_FAILURES
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    Logger.getInstance().error(
                            "[!] " + MAX_CONSECUTIVE_FAILURES
                            + " consecutive failures  auto-stopping");
                
                    stopLoop();
                    return;
                }
                

                // FIX [5]: interruptible 10 s gap – exits instantly on stop
                if (i < 5) interruptibleSleep(5);
            }

            // ---- Phase 3: 5 s gap before re-fetching session ----
            if (running.get()) interruptibleSleep(10);
        }
    }

    // -----------------------------------------------------------------------
    // Blocking wrapper around the async getSessionId (unchanged logic)
    // -----------------------------------------------------------------------
    private String fetchSessionIdBlocking(String sessionUrl, String previousSessionId) {
        Logger.getInstance().info("[*] Getting session id…");
        final String[]  result = {null};
        final boolean[] done   = {false};

        authService.getSessionId(sessionUrl, previousSessionId, sessionId -> {
            result[0] = sessionId;
            synchronized (done) {
                done[0] = true;
                done.notifyAll();
            }
        });

        synchronized (done) {
            long deadline = System.currentTimeMillis() + 25_000;
            while (!done[0] && System.currentTimeMillis() < deadline) {
                try { done.wait(1000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return result[0];
    }

    // -----------------------------------------------------------------------
    // FIX [5]: Interruptible sleep – checks running flag every second.
    //          BEFORE: Thread.sleep(seconds * 1000) – entire duration even after stop.
    //          AFTER:  loop exits as soon as running becomes false or interrupted.
    // -----------------------------------------------------------------------
    private void interruptibleSleep(long seconds) {
        for (long i = 0; i < seconds && running.get(); i++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private void saveSessionId(String id) {
        if (id == null) return;
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_SESSION_ID, id).apply();
        } catch (Exception e) {
            // Non-fatal
        }
    }

    // -----------------------------------------------------------------------
    // URL validation (service-side strict check)
    // -----------------------------------------------------------------------
    private boolean isValidHttpUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String lower = url.trim().toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        try {
            android.net.Uri parsed = android.net.Uri.parse(url.trim());
            String host = parsed.getHost();
            return host != null && !host.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Broadcast to UI
    // -----------------------------------------------------------------------
    private void broadcastUpdate() {
        broadcastUpdate(requestCount.get(), sdf.format(new Date()),
                currentSessionId, currentStatus);
    }

    private void broadcastUpdate(int count, String time, String sessionId, String status) {
        try {
            Intent intent = new Intent(BROADCAST_UPDATE);
            intent.putExtra(EXTRA_REQUEST_COUNT, count);
            intent.putExtra(EXTRA_LAST_TIME,     time      != null ? time      : "");
            intent.putExtra(EXTRA_SESSION_ID,    sessionId != null ? sessionId : "");
            intent.putExtra(EXTRA_STATUS,        status    != null ? status    : "");
            sendBroadcast(intent);
        } catch (Exception e) {
            Logger.getInstance().warn("[!] broadcastUpdate failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // WakeLock
    // -----------------------------------------------------------------------
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "WifiDogAuth::WakeLock");
                wakeLock.acquire(2 * 60 * 60 * 1000L);
            }
        } catch (Exception e) {
            Logger.getInstance().warn("[!] WakeLock acquire failed: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            // Non-fatal
        } finally {
            wakeLock = null;
        }
    }

    // -----------------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------------
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "WifiDog Auth Service",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Persistent notification for auth service");
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(channel);
            } catch (Exception e) {
                Logger.getInstance().warn("[!] Notification channel creation failed: " + e.getMessage());
            }
        }
    }

    private Notification buildNotification(String text) {
        if (text == null) text = "";
        if (text.length() > 100) text = text.substring(0, 97) + "…";

        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WifiDog Authenticator")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pi)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception e) {
            Logger.getInstance().warn("[!] Notification update failed: " + e.getMessage());
        }
    }
}
