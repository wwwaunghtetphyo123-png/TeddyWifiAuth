package com.violet.wifidogauthenticator.activities;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.violet.wifidogauthenticator.R;
import com.violet.wifidogauthenticator.adapters.LogAdapter;
import com.violet.wifidogauthenticator.models.LogModel;
import com.violet.wifidogauthenticator.services.AuthForegroundService;
import com.violet.wifidogauthenticator.utils.CryptoService;
import com.violet.wifidogauthenticator.utils.Logger;

public class MainActivity extends AppCompatActivity implements Logger.LogListener {

    // ---- Views ----
    private EditText  etIp, etEncUrl;
    private Button    btnStart, btnStop, btnClearLog, btnBuyKey;
    private TextView  tvStatus, tvRequestCount, tvLastTime, tvSessionId;
    private RecyclerView rvLogs;

    private LogAdapter logAdapter;

    // FIX: Track receiver registration state to prevent "receiver not registered" crash
    private boolean receiverRegistered = false;

    // FIX: Track running state in UI layer to guard button spam
    private boolean isServiceRunning = false;

    // ---- Broadcast receiver for service updates ----
    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            int    count     = intent.getIntExtra(AuthForegroundService.EXTRA_REQUEST_COUNT, 0);
            String time      = intent.getStringExtra(AuthForegroundService.EXTRA_LAST_TIME);
            String sessionId = intent.getStringExtra(AuthForegroundService.EXTRA_SESSION_ID);
            String status    = intent.getStringExtra(AuthForegroundService.EXTRA_STATUS);

            if (tvRequestCount != null) tvRequestCount.setText(String.valueOf(count));
            if (time      != null && tvLastTime  != null) tvLastTime.setText(time);
            if (sessionId != null && tvSessionId != null) tvSessionId.setText(sessionId);
            if (status    != null && tvStatus    != null) {
                tvStatus.setText(status);
                tvStatus.setTextColor(status.contains("SUCCESS")
                        ? 0xFF4CAF50 : status.contains("FAIL")
                        ? 0xFFF44336 : 0xFFFF9800);
            }

            // FIX [2]: Only sync button state on TERMINAL statuses ("Stopped" / "Idle").
            // Transient statuses like "FAILED ✗" must NOT re-enable Start while the
            // loop is still running – that was causing the Start button to flicker
            // back to enabled after every failed request.
            if ("Stopped".equals(status)) {
                if (isServiceRunning) {
                    isServiceRunning = false;
                    setRunningState(isServiceRunning);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupRecyclerView();
        loadSavedPrefs();
        requestPermissionsIfNeeded();
        requestIgnoreBatteryOptimizations();

        Logger.getInstance().addListener(this);
        logAdapter.setLogs(Logger.getInstance().getLogs());

        // FIX: Restore button state from SharedPreferences on re-entry
        SharedPreferences prefs = getSharedPreferences(
                AuthForegroundService.PREFS_NAME, Context.MODE_PRIVATE);
        isServiceRunning = prefs.getBoolean(AuthForegroundService.KEY_RUNNING, false);
        setRunningState(isServiceRunning);

        btnStart.setOnClickListener(v -> onStartClicked());
        btnStop.setOnClickListener(v  -> onStopClicked());
        btnClearLog.setOnClickListener(v -> {
            Logger.getInstance().clear();
            logAdapter.clear();
        });
        btnBuyKey.setOnClickListener(v -> {
            try {
                Intent tgIntent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://t.me/Mather_Fucker0"));
                startActivity(tgIntent);
            } catch (Exception e) {
                Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(AuthForegroundService.BROADCAST_UPDATE);
            registerReceiver(updateReceiver, filter);
            receiverRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            try {
                unregisterReceiver(updateReceiver);
            } catch (IllegalArgumentException e) {
                Logger.getInstance().warn("updateReceiver already unregistered: " + e.getMessage());
            }
            receiverRegistered = false;
        }
        savePrefs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Logger.getInstance().removeListener(this);
    }

    // -----------------------------------------------------------------------
    // Logger.LogListener
    // -----------------------------------------------------------------------
    @Override
    public void onLogAdded(LogModel log) {
        if (logAdapter == null || rvLogs == null || isFinishing() || isDestroyed()) return;

        logAdapter.addLog(log);
        int count = logAdapter.getItemCount();
        if (count > 0) {
            try {
                rvLogs.scrollToPosition(count - 1);
            } catch (Exception e) {
                // Ignore scroll failures
            }
        }
    }

    // -----------------------------------------------------------------------
    // Start / Stop
    // -----------------------------------------------------------------------
    private void onStartClicked() {
        // ═══════════════════════════════════════════════════════════════════
        // STEP 1 — User pressed Start.
        //          DO NOT change any UI at this point.
        //          Guard only: reject silently if already running.
        // ═══════════════════════════════════════════════════════════════════
        if (isServiceRunning) {
            Toast.makeText(this, "Service is already running", Toast.LENGTH_SHORT).show();
            return;
        }

        // Read inputs — no UI mutation, just data capture.
        String ip     = etIp.getText().toString().trim();
        String encUrl = etEncUrl.getText().toString().trim();

        // ═══════════════════════════════════════════════════════════════════
        // STEP 2 — Validate IP address.
        //          If invalid: show error and STOP.
        //          DO NOT change any UI. DO NOT proceed to decryption.
        // ═══════════════════════════════════════════════════════════════════
        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter IP address", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isValidIPv4(ip)) {
            Toast.makeText(this,
                    "Invalid IP address. Must be a valid IPv4 (e.g. 192.168.1.1).",
                    Toast.LENGTH_LONG).show();
            Logger.getInstance().error("REJECTED: \"" + ip + "\" is not a valid IPv4 address.");
            return; // STOP — no UI change
        }

        // ═══════════════════════════════════════════════════════════════════
        // STEP 3 — Decrypt the encrypted URL.
        //          If decryption fails for ANY reason: show error and STOP.
        //          DO NOT change any UI. DO NOT proceed to STEP 4.
        // ═══════════════════════════════════════════════════════════════════
        if (encUrl.isEmpty()) {
            Toast.makeText(this, "Enter encrypted URL", Toast.LENGTH_SHORT).show();
            return;
        }

        // Reject plain URLs before attempting decryption — AES ciphertext
        // never starts with "http://" or "https://".
        if (looksLikePlainUrl(encUrl)) {
            Toast.makeText(this,
                    "REJECTED: Plain URLs are not accepted. Input must be AES-encrypted.",
                    Toast.LENGTH_LONG).show();
            Logger.getInstance().error("[STEP 3] REJECTED: Input appears to be a plain URL – encryption required.");
            return; // STOP — no UI change
        }

        // Attempt AES decryption. Any failure → stop immediately, no UI change.
        String decrypted = CryptoService.decrypt(encUrl);
        if (decrypted == null || decrypted.isEmpty()) {
            Toast.makeText(this,
                    "Decryption FAILED – cannot start. Provide valid encrypted data.",
                    Toast.LENGTH_LONG).show();
            Logger.getInstance().error("Decryption returned null/empty – will NOT start.");
            return; // STOP — no UI change
        }

        // Validate that the decrypted output is a well-formed HTTP/HTTPS URL.
        if (!isValidHttpUrl(decrypted)) {
            Toast.makeText(this,
                    "Decryption succeeded but result is not a valid HTTP/HTTPS URL.",
                    Toast.LENGTH_LONG).show();
            Logger.getInstance().error("Decrypted value is not a valid URL.");
            return; // STOP — no UI change
        }

        Logger.getInstance().success("Decryption succeeded.");

        // ═══════════════════════════════════════════════════════════════════
        // STEP 4 — Both validations passed. NOW change UI state.
        //          - Disable Start button (hide it)
        //          - Show Cancel (Stop) button
        //          - Set isRunning = true
        //          This is the ONLY place UI state is mutated on the start path.
        // ═══════════════════════════════════════════════════════════════════

        // Persist validated inputs so the service can read them.
        getSharedPreferences(AuthForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(AuthForegroundService.KEY_IP, ip)
                .putString(AuthForegroundService.KEY_ENC_URL, decrypted)
                .apply();

        isServiceRunning = true;       // isRunning = true
        setRunningState(true);         // Hide Start, show Cancel/Stop

        if (tvStatus != null) {
            tvStatus.setText("Starting…");
            tvStatus.setTextColor(0xFFFF9800);
        }

        // ═══════════════════════════════════════════════════════════════════
        // STEP 5 — UI is committed. NOW start sending requests (launch service).
        //          This always runs AFTER STEP 4 completes.
        // ═══════════════════════════════════════════════════════════════════
        Intent serviceIntent = new Intent(this, AuthForegroundService.class);
        serviceIntent.setAction(AuthForegroundService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Logger.getInstance().info("Service start command sent.");
    }

    private void onStopClicked() {
        // FIX: Prevent stop when already stopped
        if (!isServiceRunning) {
            Toast.makeText(this, "Service is not running", Toast.LENGTH_SHORT).show();
            return;
        }

        // FIX: Flip state and update buttons immediately to prevent double-tap
        isServiceRunning = false;
        setRunningState(isServiceRunning);

        Intent serviceIntent = new Intent(this, AuthForegroundService.class);
        serviceIntent.setAction(AuthForegroundService.ACTION_STOP);
        try {
            startService(serviceIntent);
        } catch (Exception e) {
            Logger.getInstance().error("[!] Failed to send stop command: " + e.getMessage());
        }

        if (tvStatus != null) {
            tvStatus.setText("Stopped");
            tvStatus.setTextColor(0xFFB0BEC5);
        }
        Logger.getInstance().warn("[*] Service stop command sent");
    }

    // -----------------------------------------------------------------------
    // State management — SINGLE SOURCE OF TRUTH
    // -----------------------------------------------------------------------

    /**
     * The ONE place that may update button UI state.
     *
     * Rules (enforced here, nowhere else):
     *  - running == true  → Start button GONE,    Stop button VISIBLE
     *  - running == false → Start button VISIBLE, Stop button GONE
     *
     * Always marshalled onto the main thread so callers on any thread are safe,
     * but per architecture rules this must only be called from the main thread
     * after validation succeeds (start) or when stopping (stop).
     *
     * STRICT: No other code in this class (or any other class) may call
     * btnStart.setEnabled(...), btnStart.setVisibility(...),
     * btnStop.setEnabled(...), or btnStop.setVisibility(...).
     */
    private void setRunningState(final boolean running) {
        runOnUiThread(() -> {
            if (btnStart != null) {
                btnStart.setVisibility(running ? View.GONE : View.VISIBLE);
            }
            if (btnStop != null) {
                btnStop.setVisibility(running ? View.VISIBLE : View.GONE);
            }
        });
    }

    /**
     * STRICT: Validate that a string is a well-formed HTTP or HTTPS URL.
     * Only used to validate the DECRYPTED output, never the raw input.
     */
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

    /**
     * STRICT SECURITY: Detect if input looks like a plain (unencrypted) URL.
     * AES/Base64 ciphertext will NEVER start with http:// or https://.
     * If this returns true, the input is rejected immediately before any decryption.
     */
    private boolean looksLikePlainUrl(String input) {
        if (input == null) return false;
        String lower = input.trim().toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * STRICT IPv4 VALIDATION.
     * Accepts only x.x.x.x where each octet is an integer in [0, 255].
     * Rejects: partial addresses, leading zeros beyond "0" itself,
     * alphabetic input, extra dots, and out-of-range octets.
     */
    private boolean isValidIPv4(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        // Must match exactly four numeric groups separated by dots — nothing more, nothing less.
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty()) return false;
            // Reject leading zeros (e.g. "01", "001") — not valid in strict IPv4
            if (part.length() > 1 && part.charAt(0) == '0') return false;
            // Reject anything that is not purely digits
            for (char c : part.toCharArray()) {
                if (c < '0' || c > '9') return false;
            }
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private void bindViews() {
        etIp           = findViewById(R.id.et_ip);
        etEncUrl       = findViewById(R.id.et_enc_url);
        btnStart       = findViewById(R.id.btn_start);
        btnStop        = findViewById(R.id.btn_stop);
        btnClearLog    = findViewById(R.id.btn_clear_log);
        btnBuyKey      = findViewById(R.id.btn_buy_key);
        tvStatus       = findViewById(R.id.tv_status);
        tvRequestCount = findViewById(R.id.tv_request_count);
        tvLastTime     = findViewById(R.id.tv_last_time);
        tvSessionId    = findViewById(R.id.tv_session_id);
        rvLogs         = findViewById(R.id.rv_logs);
    }

    private void setupRecyclerView() {
        logAdapter = new LogAdapter(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rvLogs.setLayoutManager(lm);
        rvLogs.setAdapter(logAdapter);
    }

    private void loadSavedPrefs() {
        SharedPreferences prefs = getSharedPreferences(
                AuthForegroundService.PREFS_NAME, Context.MODE_PRIVATE);
        String ip     = prefs.getString(AuthForegroundService.KEY_IP, "");
        // STRICT: Do NOT pre-fill the encrypted URL field with a plain (decrypted) URL
        // that was stored by a previous run. The field must always hold AES-encrypted data.
        // If the stored value is already a plain URL, show empty field so user re-enters
        // the encrypted form. This prevents the user from unknowingly submitting a plain URL.
        String storedUrl = prefs.getString(AuthForegroundService.KEY_ENC_URL, "");
        if (ip       != null && !ip.isEmpty()) etIp.setText(ip);
        if (storedUrl != null && !storedUrl.isEmpty()) {
            // Only pre-fill if it does NOT look like a plain URL (i.e. it is still encrypted)
            String lower = storedUrl.trim().toLowerCase();
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                etEncUrl.setText(storedUrl);
            }
            // If it IS a plain URL we leave the field empty – user must enter encrypted form.
        }
    }

    private void savePrefs() {
        String ip     = (etIp     != null) ? etIp.getText().toString().trim()     : "";
        String encUrl = (etEncUrl != null) ? etEncUrl.getText().toString().trim() : "";

        getSharedPreferences(AuthForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(AuthForegroundService.KEY_IP,      ip)
                .putString(AuthForegroundService.KEY_ENC_URL, encUrl)
                .apply();
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                try { startActivity(intent); } catch (Exception ignored) {}
            }
        }
    }
}
