package io.intare;

import android.os.Build;
import android.util.Log;

import org.filesys.debug.DebugConfigSection;
import org.filesys.server.SessionListener;
import org.filesys.server.SrvSession;
import org.filesys.server.auth.ClientInfo;
import org.filesys.server.auth.ISMBAuthenticator;
import org.filesys.server.auth.LocalAuthenticator;
import org.filesys.server.auth.UserAccountList;
import org.filesys.server.auth.acl.DefaultAccessControlManager;
import org.filesys.server.config.CoreServerConfigSection;
import org.filesys.server.config.GlobalConfigSection;
import org.filesys.server.config.SecurityConfigSection;
import org.filesys.server.config.ServerConfiguration;
import org.filesys.server.filesys.DiskDeviceContext;
import org.filesys.server.filesys.DiskSharedDevice;
import org.filesys.server.filesys.FilesystemsConfigSection;
import org.filesys.smb.server.SMBConfigSection;
import org.filesys.smb.server.SMBServer;
import org.filesys.smb.server.SMBSrvSession;
import org.filesys.smb.server.disk.JavaNIODiskDriver;
import org.springframework.extensions.config.element.GenericConfigElement;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Simple SMB file server built on JFileServer (org.filesys, LGPL-3.0).
 *
 * Serves a single disk share ("Intare") over SMB1/TCP on port 4450 with guest access.
 * Design modeled on SimbaDroid (MPL-2.0) by Jan Henning.
 */
public class SmbServer {
    private static final String TAG = "SmbServer";

    /** TCP port the SMB server listens on (port 445 is privileged/unavailable without root). */
    public static final int SMB_PORT = 4450;
    /** Name of the exported share. */
    public static final String SHARE_NAME = "Intare";

    private static final int[] MEMORY_POOL_SIZES = {256, 4096, 16384, 66000};
    private static final int[] MEMORY_POOL_INIT = {20, 20, 5, 5};
    private static final int[] MEMORY_POOL_MAX = {100, 50, 50, 50};

    private ServerConfiguration mConfig;
    private SMBServer mSmbServer;
    private boolean mStarted;

    /** Callback for SMB session lifecycle events, fired from a JFileServer thread. */
    public interface SessionEventListener {
        void onSessionActivated(String address);
        void onSessionClosed(String address);
    }

    private volatile SessionEventListener mSessionEventListener;

    /** Sessions that have logged on, so a close is only reported for an activated session. */
    private final Set<Integer> mActivatedSessions = Collections.synchronizedSet(new HashSet<>());

    public void setSessionEventListener(SessionEventListener listener) {
        mSessionEventListener = listener;
    }

    public synchronized boolean isRunning() {
        return mStarted;
    }

    /**
     * Snapshot of the LAN IP addresses of devices whose SMB session is currently
     * active (authenticated/logged on — i.e. actually using a share). Deduplicated
     * and sorted for stable display.
     *
     * <p>The session list is backed by a Hashtable, so it is safe to enumerate
     * from the UI thread while the server thread accepts/clears sessions;
     * concurrently-removed entries are simply skipped.
     *
     * @return sorted list of unique client IPs; empty when the server is stopped
     */
    public synchronized List<String> getConnectedClientAddresses() {
        if (!mStarted || mSmbServer == null) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> ips = new LinkedHashSet<>(); // dedup: macOS opens several sessions per client
        Enumeration<SrvSession> sessions = mSmbServer.getSessions().enumerateSessions();
        while (sessions.hasMoreElements()) {
            SrvSession s = sessions.nextElement();
            // Only sessions that have actually logged on count as connected.
            if (!s.isLoggedOn()) {
                continue;
            }
            // Skip sessions whose TCP connection already closed: JFileServer keeps
            // SMB1 sessions around briefly for reconnect, and those would otherwise
            // show up as "connected" after the client has gone away.
            if (s.isDisconnectedSession() || s.isShutdown()) {
                continue;
            }
            InetAddress addr = s.getRemoteAddress();
            if (addr != null) {
                ips.add(addr.getHostAddress());
            }
        }
        List<String> sorted = new ArrayList<>(ips);
        Collections.sort(sorted); // stable display order
        return sorted;
    }

    private static String addressOf(SrvSession session) {
        InetAddress addr = session.getRemoteAddress();
        return addr != null ? addr.getHostAddress() : null;
    }

    /**
     * Start the SMB server sharing the given directory.
     *
     * @param sharePath directory to share (e.g. external storage root)
     * @param serverName NetBIOS/computer name of the server
     */
    public synchronized void start(String sharePath, String serverName) throws Exception {
        if (mStarted) {
            return;
        }

        mConfig = new ServerConfiguration(serverName);

        // Debug: route JFileServer output to logcat at Debug level
        DebugConfigSection debugConfig = new DebugConfigSection(mConfig);
        debugConfig.setDebug("io.intare.LogcatDebug", new GenericConfigElement("debug"));

        // Core: memory + thread pools
        CoreServerConfigSection coreConfig = new CoreServerConfigSection(mConfig);
        coreConfig.setMemoryPool(MEMORY_POOL_SIZES, MEMORY_POOL_INIT, MEMORY_POOL_MAX);
        coreConfig.setThreadPool(6, 6);
        coreConfig.getThreadPool().setDebug(false);

        // Global
        new GlobalConfigSection(mConfig);

        // Security: guest access, empty account list
        SecurityConfigSection secConfig = new SecurityConfigSection(mConfig);
        DefaultAccessControlManager aclManager = new DefaultAccessControlManager();
        aclManager.setDebug(false);
        aclManager.initialize(mConfig, new GenericConfigElement("aclManager"));
        secConfig.setAccessControlManager(aclManager);
        secConfig.setUserAccounts(new UserAccountList());

        // Filesystem: one disk share rooted at sharePath
        FilesystemsConfigSection filesysConfig = new FilesystemsConfigSection(mConfig);
        addShare(filesysConfig, secConfig, mConfig, new File(sharePath));

        // SMB transport: direct TCP/IP SMB only (no NetBIOS - it would need privileged ports)
        SMBConfigSection smbConfig = new SMBConfigSection(mConfig);
        smbConfig.setServerName(serverName);
        smbConfig.setDomainName("WORKGROUP");
        smbConfig.setNetBIOSSMB(false);
        smbConfig.setTcpipSMB(true);
        smbConfig.setTcpipSMBPort(SMB_PORT);
        smbConfig.setHostAnnouncer(false);
        // Errors and connection state only (the per-search / per-transaction
        // debug flags were used to diagnose macOS smbfs enumeration and would
        // otherwise flood logcat on large listings).
        smbConfig.setSessionDebugFlags(EnumSet.of(
                SMBSrvSession.Dbg.STATE,
                SMBSrvSession.Dbg.ERROR));

        // Android API level workarounds (same as SimbaDroid):
        // - NIO sockets need java.time/nio backports that core lib desugaring can't fully provide below N
        // - the hashed open file map relies on ConcurrentHashMap.keySet() that changed in Java 8
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            smbConfig.setDisableNIOCode(true);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            smbConfig.setDisableHashedOpenFileMap(true);
        }

        // Accept any credentials (guest access)
        LocalAuthenticator authenticator = new LocalAuthenticator() {
            @Override
            public AuthStatus authenticateUser(ClientInfo client, SrvSession sess, PasswordAlgorithm alg) {
                return AuthStatus.AUTHENTICATED;
            }
        };
        authenticator.setDebug(false);
        authenticator.setAllowGuest(true);
        authenticator.setAccessMode(ISMBAuthenticator.AuthMode.USER);
        authenticator.initialize(mConfig, new GenericConfigElement("authenticator"));
        smbConfig.setAuthenticator(authenticator);

        mSmbServer = new SMBServer(mConfig);
        mConfig.addServer(mSmbServer);

        // Forward SMB session lifecycle events (activated = logged on, deactivated =
        // session closed) so the owner can be alerted when a device mounts/unmounts.
        mSmbServer.addSessionListener(new SessionListener() {
            @Override
            public void sessionCreated(SrvSession session) {
            }

            @Override
            public void sessionLoggedOn(SrvSession session) {
                mActivatedSessions.add(session.getSessionId());
                SessionEventListener listener = mSessionEventListener;
                if (listener != null) {
                    listener.onSessionActivated(addressOf(session));
                }
            }

            @Override
            public void sessionClosed(SrvSession session) {
                // Only report closures of sessions that actually activated, so a bare
                // TCP probe that never logged on doesn't produce a "disconnected" alert.
                if (mActivatedSessions.remove(session.getSessionId())) {
                    SessionEventListener listener = mSessionEventListener;
                    if (listener != null) {
                        listener.onSessionClosed(addressOf(session));
                    }
                }
            }
        });

        mSmbServer.startServer();
        mStarted = true;
        Log.i(TAG, "SMB server started on port " + SMB_PORT + ", sharing " + sharePath);
    }

    public synchronized void stop() {
        if (!mStarted) {
            return;
        }
        try {
            mSmbServer.shutdownServer(false);
        } catch (Exception ex) {
            Log.w(TAG, "Error shutting down SMB server", ex);
        } finally {
            mConfig.removeAllServers();
            mSmbServer = null;
            mConfig = null;
            mStarted = false;
            Log.i(TAG, "SMB server stopped");
        }
    }

    private static void addShare(FilesystemsConfigSection filesysConfig,
                                 SecurityConfigSection secConfig,
                                 ServerConfiguration serverConfig,
                                 File root) throws Exception {
        JavaNIODiskDriver diskDriver = new JavaNIODiskDriver();

        final GenericConfigElement driverConfig = new GenericConfigElement("driver");
        final GenericConfigElement localPath = new GenericConfigElement("LocalPath");
        localPath.setValue(root.getAbsolutePath());
        driverConfig.addChild(localPath);
        // Android's shared storage behaves case-insensitively
        driverConfig.addChild(new GenericConfigElement("DiskIsCaseInsensitive"));

        DiskDeviceContext devCtx =
                (DiskDeviceContext) diskDriver.createContext(SHARE_NAME, driverConfig);
        devCtx.setShareName(SHARE_NAME);
        devCtx.setConfigurationParameters(driverConfig);
        devCtx.enableChangeHandler(false);

        DiskSharedDevice share = new DiskSharedDevice(SHARE_NAME, diskDriver, devCtx);
        share.setConfiguration(serverConfig);
        share.setAccessControlList(secConfig.getGlobalAccessControls());
        devCtx.startFilesystem(share);
        filesysConfig.addShare(share);
    }
}
