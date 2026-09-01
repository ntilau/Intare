package io.intare;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

/**
 * Control screen for the on-device SMB server: pick a share directory, start/stop the server,
 * and show how to connect to it from another device.
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_STORAGE = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    /** How often the connected-devices readout refreshes while the screen is open. */
    private static final long CONNECTED_POLL_INTERVAL_MS = 2000;

    private EditText sharePathEditText;
    private Button startButton;
    private TextView statusTextView;
    private TextView permissionTextView;
    private Button permissionButton;
    private TextView batteryTextView;
    private Button batteryButton;
    private TextView hintTextView;
    private TextView connectedTextView;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Polls for newly connected / disconnected clients while the screen is open. */
    private final Runnable connectedPoller = new Runnable() {
        @Override
        public void run() {
            updateConnectedDevices();
            mainHandler.postDelayed(this, CONNECTED_POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharePathEditText = findViewById(R.id.share_path_edittext);
        startButton = findViewById(R.id.start_button);
        statusTextView = findViewById(R.id.status_textview);
        permissionTextView = findViewById(R.id.permission_textview);
        permissionButton = findViewById(R.id.permission_button);
        batteryTextView = findViewById(R.id.battery_textview);
        batteryButton = findViewById(R.id.battery_button);
        hintTextView = findViewById(R.id.hint_textview);
        connectedTextView = findViewById(R.id.connected_textview);

        if (sharePathEditText.getText().toString().trim().isEmpty()) {
            sharePathEditText.setText(Environment.getExternalStorageDirectory().getAbsolutePath());
        }

        startButton.setOnClickListener(v -> onStartStopClicked());
        permissionButton.setOnClickListener(v -> requestAllFilesAccess());
        batteryButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());

        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        // Keep the connected-devices readout live while the screen is open.
        mainHandler.post(connectedPoller);
        // Start the server whenever the app opens. It keeps running as a background
        // service after the app is closed; stop it from the notification's Stop
        // action or the STOP button.
        autoStartServer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(connectedPoller);
    }

    /**
     * Start the server if it isn't already running. No-op when the app lacks the
     * all-files-access permission — the permission banner/button already handles that.
     */
    private void autoStartServer() {
        if (SmbService.isRunning()) {
            return;
        }
        if (!hasStoragePermission()) {
            return;
        }
        startServer(false);
    }

    private void onStartStopClicked() {
        if (SmbService.isRunning()) {
            stopService(new Intent(this, SmbService.class));
            // stopService() is asynchronous: the service's onDestroy() — which clears
            // isRunning() — is queued on the main looper and runs a moment later. Flip
            // the controls to the stopped state right away so the button refreshes
            // immediately, then reconcile against the real state once the teardown has
            // completed. The reconcile is posted on the same main looper, so it always
            // runs after onDestroy() has finished.
            statusTextView.setText(getString(R.string.status_stopped));
            startButton.setText(R.string.start_button);
            Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show();
            mainHandler.postDelayed(this::refreshUi, 300);
            return;
        }

        if (!hasStoragePermission()) {
            Toast.makeText(this, R.string.toast_permission_needed, Toast.LENGTH_LONG).show();
            requestAllFilesAccess();
            return;
        }

        startServer(true);
    }

    /** Start the SMB service from the current share-path field. */
    private void startServer(boolean showResultToast) {
        String sharePath = sharePathEditText.getText().toString().trim();
        if (sharePath.isEmpty()) {
            sharePath = Environment.getExternalStorageDirectory().getAbsolutePath();
        }

        Intent serviceIntent = new Intent(this, SmbService.class);
        serviceIntent.putExtra(SmbService.EXTRA_SHARE_PATH, sharePath);
        serviceIntent.putExtra(SmbService.EXTRA_SERVER_NAME, SmbService.DEFAULT_SERVER_NAME);
        startService(serviceIntent);

        // The server starts on a background thread; reflect the actual state shortly after.
        mainHandler.postDelayed(() -> {
            refreshUi();
            if (showResultToast) {
                if (SmbService.isRunning()) {
                    Toast.makeText(this, R.string.started, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this,
                            String.format(Locale.US, getString(R.string.start_failed), "see logcat"),
                            Toast.LENGTH_LONG).show();
                }
            }
        }, 1200);
    }

    private void refreshUi() {
        boolean running = SmbService.isRunning();
        statusTextView.setText(running
                ? getString(R.string.status_running_fmt, connectUrl())
                : getString(R.string.status_stopped));
        startButton.setText(running ? R.string.stop_button : R.string.start_button);

        boolean storageOk = hasStoragePermission();
        permissionTextView.setVisibility(storageOk ? android.view.View.GONE : android.view.View.VISIBLE);
        permissionButton.setVisibility(storageOk ? android.view.View.GONE : android.view.View.VISIBLE);

        boolean batteryOk = isIgnoringBatteryOptimizations();
        batteryTextView.setVisibility(batteryOk ? android.view.View.GONE : android.view.View.VISIBLE);
        batteryButton.setVisibility(batteryOk ? android.view.View.GONE : android.view.View.VISIBLE);

        hintTextView.setText(buildHint());
        updateConnectedDevices();
    }

    /**
     * Show the LAN IPs of devices connected to the server, live-updated by
     * {@link #connectedPoller}. Hidden while the server is stopped.
     */
    private void updateConnectedDevices() {
        boolean running = SmbService.isRunning();
        connectedTextView.setVisibility(running ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!running) {
            return;
        }
        List<String> ips = SmbService.getConnectedClientAddresses();
        String text = getString(R.string.connected_devices)
                + (ips.isEmpty()
                    ? ": " + getString(R.string.connected_devices_none)
                    : ":\n" + TextUtils.join("\n", ips));
        connectedTextView.setText(text);
    }

    private String buildHint() {
        String ip = getLocalIpAddress();
        int port = SmbServer.SMB_PORT;
        String share = SmbServer.SHARE_NAME;
        return getString(R.string.hint_title)
                + "\n" + String.format(Locale.US, getString(R.string.hint_macos), ip, port, share)
                + "\n" + String.format(Locale.US, getString(R.string.hint_linux), ip, port, share)
                + "\n" + String.format(Locale.US, getString(R.string.hint_windows), ip, port, share)
                + "\n\n" + getString(R.string.hint_guest) + " " + getString(R.string.hint_note);
    }

    private String connectUrl() {
        return String.format(Locale.US, "smb://%s:%d/%s",
                getLocalIpAddress(), SmbServer.SMB_PORT, SmbServer.SHARE_NAME);
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * True if the system already exempts this app from battery optimization
     * (Doze / app standby), so the background server isn't throttled or killed.
     */
    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /**
     * Ask the user to grant the "unrestricted" battery exemption. The system shows
     * a one-time dialog; the user must confirm. Falls back to the battery-optimization
     * settings screen if the direct request intent isn't available on this device.
     */
    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshUi();
    }

    private String getLocalIpAddress() {
        return NetworkUtils.getLocalIpv4Address();
    }
}
