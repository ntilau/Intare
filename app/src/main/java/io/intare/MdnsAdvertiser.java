package io.intare;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.Inet4Address;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * Advertises the SMB server over mDNS (Bonjour) so the device appears as a shared
 * computer in Finder / Windows Network without typing the IP. Pure-Java jmdns bound
 * to IPv4 only (Android's NsdManager cannot register {@code _smb._tcp}, and IPv6
 * multicast is unreliable on Android). Failures are logged and swallowed: SMB keeps
 * running even if discovery is unavailable.
 */
public class MdnsAdvertiser {
    private static final String TAG = "MdnsAdvertiser";
    private static final String SERVICE_TYPE = "_smb._tcp.local.";

    private final Context mContext;
    private WifiManager.MulticastLock mMulticastLock;
    private JmDNS mJmDns;
    private volatile boolean mStarted;

    public MdnsAdvertiser(Context context) {
        mContext = context.getApplicationContext();
    }

    public boolean isStarted() {
        return mStarted;
    }

    /**
     * Start advertising the given server name on the given TCP port. Returns false
     * (without throwing) if discovery cannot start — the SMB server is unaffected.
     * Must be called off the main thread: {@link JmDNS#create} binds a socket and
     * spawns its own thread.
     */
    public synchronized boolean start(String serverName, int port) {
        if (mStarted) {
            return true;
        }

        Inet4Address addr = NetworkUtils.getLocalIpv4();
        if (addr == null) {
            Log.w(TAG, "No IPv4 address available; mDNS disabled");
            return false;
        }

        // mDNS labels are a single DNS label: the SMB server name may contain dots.
        String label = serverName.replace('.', '-');

        try {
            // Android's Wi-Fi stack filters inbound multicast unless the lock is held;
            // the responder must hear the multicast queries to reply.
            mMulticastLock = wifi().createMulticastLock("intare-mdns");
            mMulticastLock.setReferenceCounted(false);
            mMulticastLock.acquire();

            // Binding to the IPv4 address joins only 224.0.0.251 (no IPv6 multicast).
            // Passing the server name makes the mDNS host name "INTARE.local.".
            mJmDns = JmDNS.create(addr, label);
            ServiceInfo info = ServiceInfo.create(SERVICE_TYPE, label, port, "");
            mJmDns.registerService(info);
            mStarted = true;
            Log.i(TAG, "mDNS advertising " + label + "." + SERVICE_TYPE
                    + " port " + port + " (" + addr.getHostAddress() + ")");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to start mDNS advertiser; SMB will run without discovery", e);
            teardown();
            return false;
        }
    }

    /**
     * Stop advertising: release the multicast lock now, close JmDNS on a background
     * thread ({@link JmDNS#close} sends the goodbye packet and can block briefly).
     */
    public synchronized void stop() {
        if (!mStarted && mJmDns == null && mMulticastLock == null) {
            return;
        }
        mStarted = false;
        final JmDNS dns = mJmDns;
        mJmDns = null;
        releaseMulticastLock();
        if (dns != null) {
            new Thread(() -> {
                try {
                    dns.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing JmDNS", e);
                }
            }, "mdns-stop").start();
        }
    }

    private WifiManager wifi() {
        return (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    }

    private void releaseMulticastLock() {
        if (mMulticastLock != null) {
            if (mMulticastLock.isHeld()) {
                mMulticastLock.release();
            }
            mMulticastLock = null;
        }
    }

    private void teardown() {
        releaseMulticastLock();
        if (mJmDns != null) {
            try {
                mJmDns.close();
            } catch (Exception ignored) {
            }
            mJmDns = null;
        }
    }
}
