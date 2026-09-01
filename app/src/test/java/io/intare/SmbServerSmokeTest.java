package io.intare;

import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end SMB1 smoke test. Requires the SMB server to be running on the device and
 * reachable at 127.0.0.1:4450 (e.g. via {@code adb forward tcp:4450 tcp:4450}).
 *
 * Uses jcifs 1.3.17, an SMB1 client, as the counterparty so we prove the server really
 * speaks SMB1 (not just that a TCP socket is open).
 */
public class SmbServerSmokeTest {
    private static final String ROOT = "smb://127.0.0.1:4450/Intare/";

    @Test
    public void listShareAndReadWriteAFile() throws Exception {
        NtlmPasswordAuthentication auth = new NtlmPasswordAuthentication("WORKGROUP", "guest", "");

        SmbFile share = new SmbFile(ROOT, auth);
        assertTrue("Share should exist and be a directory", share.isDirectory());
        assertTrue("Share should be readable", share.canRead());

        // Write a probe file through the share, then read it back.
        String probe = "smoke-" + System.nanoTime() + ".txt";
        SmbFile out = new SmbFile(ROOT + probe, auth);
        try (OutputStream os = out.getOutputStream()) {
            os.write("hello-from-jcifs\n".getBytes("UTF-8"));
        }

        SmbFile in = new SmbFile(ROOT + probe, auth);
        assertTrue("Written file should be visible", in.exists());
        try (InputStream is = in.getInputStream()) {
            byte[] buf = new byte[64];
            int n = is.read(buf);
            String content = new String(buf, 0, n, "UTF-8");
            if (!"hello-from-jcifs\n".equals(content)) {
                fail("Unexpected file content: " + content);
            }
        }

        // Clean up the probe file.
        in.delete();

        // List the share root to confirm directory enumeration works.
        SmbFile[] entries = share.listFiles();
        assertTrue("Share should enumerate at least one entry", entries.length > 0);

        System.out.println("SMB1 smoke test OK - listed " + entries.length + " entries");
    }
}
