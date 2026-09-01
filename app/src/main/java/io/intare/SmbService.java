package io.intare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Foreground service that hosts the SMB server. It runs as a background service:
 * started when the app opens, it keeps serving (with a persistent notification) after
 * the app is closed, until stopped via the notification's Stop action or the app's
 * STOP button. Declared with {@code foregroundServiceType="specialUse"}. Started with
 * {@code START_STICKY} so the system restarts it if killed, re-sharing the last path.
 */
public class SmbService extends Service {
    private static final String TAG = "SmbService";

    private static final String CHANNEL_ID = "smb_server";
    private static final int NOTIFICATION_ID = 1;

    private static final String PREFS = "smb_service";
    private static final String KEY_SHARE_PATH = "share_path";

    /** Notification "Stop" action. */
    private static final String ACTION_STOP = "io.intare.action.STOP";

    public static final String EXTRA_SHARE_PATH = "share_path";
    public static final String EXTRA_SERVER_NAME = "server_name";

    /** Server computer name advertised to clients. */
    public static final String DEFAULT_SERVER_NAME = "INTARE";

    private static volatile boolean sRunning = false;

    /** The live server instance, for read-only queries from the activity. */
    private static volatile SmbServer sActiveServer;

    private SmbServer mServer;
    private MdnsAdvertiser mMdns;
    private Thread mServerThread;

    /** Collapses the multiple sessions macOS/Windows open per mount into one beep. */
    private static final long BEEP_DEBOUNCE_MS = 2500;

    private final Map<String, Long> mLastBeepAt = new ConcurrentHashMap<>();
    private ToneGenerator mToneGen;

    public static boolean isRunning() {
        return sRunning;
    }

    /**
     * LAN IPs of devices with an active (logged-on) SMB session. Empty when stopped.
     */
    public static List<String> getConnectedClientAddresses() {
        SmbServer s = sActiveServer;
        return s != null ? s.getConnectedClientAddresses() : Collections.emptyList();
    }

    /** Beep when a device mounts the share (its SMB session activated). */
    private void beepOnActivation(String address) {
        if (debounced(address)) {
            return;
        }
        Log.i(TAG, "Session activated: " + address);
        playTone(ToneGenerator.TONE_PROP_BEEP2);
    }

    /** Beep when a mounted device goes away (its SMB session closed). */
    private void beepOnDeactivation(String address) {
        if (debounced(address)) {
            return;
        }
        Log.i(TAG, "Session closed: " + address);
        playTone(ToneGenerator.TONE_PROP_BEEP);
    }

    /** True if this address beeped within the debounce window (no second beep yet). */
    private boolean debounced(String key) {
        long now = System.currentTimeMillis();
        Long last = mLastBeepAt.get(key);
        if (last != null && now - last < BEEP_DEBOUNCE_MS) {
            return true;
        }
        mLastBeepAt.put(key, now);
        return false;
    }

    /**
     * Play a short tone so the owner hears the session event. Uses the ALARM
     * stream: it is the one stream that is loud and audible even when the phone
     * is silent/in DND or when media playback (STREAM_MUSIC) is muted — the point
     * of the beep is that the owner notices a device connecting.
     */
    private synchronized void playTone(int tone) {
        try {
            if (mToneGen == null) {
                mToneGen = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            }
            mToneGen.startTone(tone, 800);
        } catch (Exception e) {
            Log.w(TAG, "Could not play session beep", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // Notification "Stop" action: tear the server down for good.
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_STICKY;
        }

        // Remember the chosen share path so a START_STICKY restart (null intent after
        // the system killed us) re-shares the same directory, not the storage root.
        String sharePath = intent != null ? intent.getStringExtra(EXTRA_SHARE_PATH) : null;
        if (sharePath != null) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_SHARE_PATH, sharePath).apply();
        } else {
            sharePath = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_SHARE_PATH, null);
        }
        if (sharePath == null || sharePath.isEmpty()) {
            sharePath = Environment.getExternalStorageDirectory().getAbsolutePath();
        }
        String serverName = intent != null ? intent.getStringExtra(EXTRA_SERVER_NAME) : null;
        if (serverName == null) {
            serverName = DEFAULT_SERVER_NAME;
        }

        // Re-starting an already-running server: just confirm the notification.
        if (mServer != null && mServer.isRunning()) {
            updateNotification();
            return START_STICKY;
        }
        // A previous start is still in progress on its thread (e.g. rapid onResume
        // calls from auto-start): don't spawn a second server instance.
        if (mServerThread != null && mServerThread.isAlive()) {
            updateNotification();
            return START_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting SMB server…"));

        final String path = sharePath;
        final String name = serverName;
        mServer = new SmbServer();
        sActiveServer = mServer;
        // Beep when a device mounts or unmounts the share (session activated/closed).
        mServer.setSessionEventListener(new SmbServer.SessionEventListener() {
            @Override
            public void onSessionActivated(String address) {
                beepOnActivation(address);
            }

            @Override
            public void onSessionClosed(String address) {
                beepOnDeactivation(address);
            }
        });
        mServerThread = new Thread(() -> {
            try {
                mServer.start(path, name);
                sRunning = true;
                // Advertise the server over mDNS so it shows up in Finder / Windows Network.
                // Best-effort: SMB keeps running even if discovery fails to start.
                mMdns = new MdnsAdvertiser(SmbService.this);
                mMdns.start(name, SmbServer.SMB_PORT);
                updateNotification();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start SMB server", e);
                sRunning = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "smb-server");
        mServerThread.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        sActiveServer = null;
        mLastBeepAt.clear();
        if (mToneGen != null) {
            mToneGen.release();
            mToneGen = null;
        }
        if (mMdns != null) {
            mMdns.stop();
            mMdns = null;
        }
        if (mServerThread != null) {
            try {
                mServerThread.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        if (mServer != null) {
            try {
                mServer.stop();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping SMB server", e);
            }
            mServer = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(null));
        }
    }

    private Notification buildNotification(@Nullable String extraText) {
        String text = extraText != null ? extraText : "SMB server running on port " + SmbServer.SMB_PORT;

        // Tapping the notification reopens the app; the Stop action shuts the server down.
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, SmbService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("Intare SMB server")
                .setContentText(text)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "SMB server",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }
}
