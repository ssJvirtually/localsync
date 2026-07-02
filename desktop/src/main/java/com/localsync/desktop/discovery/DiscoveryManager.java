package com.localsync.desktop.discovery;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiscoveryManager {
    private static final String SERVICE_TYPE = "_photobackup._tcp.local.";
    private JmDNS jmdns;
    private ServiceInfo serviceInfo;
    private int port;
    private String serviceName;

    public DiscoveryManager(String serviceName, int port) {
        this.serviceName = serviceName;
        this.port = port;
    }

    public synchronized void startAdvertising() {
        if (jmdns != null) {
            stopAdvertising();
        }

        try {
            InetAddress localAddr = getLocalIpAddress();
            if (localAddr == null) {
                localAddr = InetAddress.getLocalHost();
            }

            System.out.println("Starting mDNS advertisement on address: " + localAddr.getHostAddress() + " and port: " + port);

            jmdns = JmDNS.create(localAddr, serviceName);

            Map<String, String> properties = new HashMap<>();
            properties.put("port", String.valueOf(port));
            properties.put("path", "/health");
            properties.put("pcName", serviceName);

            serviceInfo = ServiceInfo.create(SERVICE_TYPE, serviceName, port, 0, 0, properties);
            jmdns.registerService(serviceInfo);

            System.out.println("mDNS Service '" + serviceName + "' registered successfully.");
        } catch (IOException e) {
            System.err.println("Error starting JmDNS: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized void stopAdvertising() {
        if (jmdns != null) {
            System.out.println("Stopping mDNS advertisement...");
            if (serviceInfo != null) {
                jmdns.unregisterService(serviceInfo);
                serviceInfo = null;
            }
            try {
                jmdns.close();
            } catch (IOException e) {
                System.err.println("Error closing JmDNS: " + e.getMessage());
            }
            jmdns = null;
            System.out.println("mDNS Service stopped.");
        }
    }

    public static InetAddress getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        if (!addr.isLinkLocalAddress() && !addr.isLoopbackAddress()) {
                            return addr;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Error retrieving network interfaces: " + e.getMessage());
        }
        return null;
    }

    public static List<String> getAllLocalIpAddresses() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        if (!addr.isLoopbackAddress()) {
                            ips.add(addr.getHostAddress());
                        }
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Error retrieving all local network interfaces: " + e.getMessage());
        }
        return ips;
    }
}
