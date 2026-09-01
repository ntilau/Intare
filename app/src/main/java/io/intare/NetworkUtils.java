package io.intare;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** Small helpers for the device's LAN address, shared by the UI and the mDNS advertiser. */
public final class NetworkUtils {

    private NetworkUtils() {
    }

    /**
     * First up, non-loopback IPv4 address on any interface, or {@code null} if none.
     * Used by the mDNS advertiser (needs the {@link Inet4Address} object to bind to).
     */
    public static Inet4Address getLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return (Inet4Address) addr;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** String form of the local IPv4, defaulting to loopback if none is available. */
    public static String getLocalIpv4Address() {
        Inet4Address addr = getLocalIpv4();
        return addr != null ? addr.getHostAddress() : "127.0.0.1";
    }
}
