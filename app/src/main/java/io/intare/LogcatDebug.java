package io.intare;

import android.util.Log;

import org.filesys.debug.ConsoleDebug;
import org.filesys.debug.Debug;
import org.filesys.server.config.ServerConfiguration;
import org.springframework.extensions.config.ConfigElement;

/**
 * Routes JFileServer debug output to logcat (tag "JFileServer") instead of System.out,
 * and forces the global log level to Debug so session-level diagnostics are visible.
 */
public class LogcatDebug extends ConsoleDebug {
    private static final String TAG = "JFileServer";

    @Override
    public void initialize(ConfigElement configElement, ServerConfiguration serverConfig) throws Exception {
        super.initialize(configElement, serverConfig);
        setLogLevel(Debug.Debug);
    }

    @Override
    public void debugPrint(String msg, int level) {
        Log.d(TAG, msg);
    }

    @Override
    public void debugPrintln(String msg, int level) {
        Log.d(TAG, msg);
    }

    @Override
    public void debugPrintln(Exception ex, int level) {
        Log.d(TAG, "exception: " + ex, ex);
    }
}
