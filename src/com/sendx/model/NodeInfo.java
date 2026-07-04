package com.sendx.model;

import java.net.InetAddress;

public class NodeInfo {

    private final String id;
    private final String name;
    private final InetAddress address;
    private volatile int tcpPort;
    private volatile long lastSeen;
    private final boolean manual;

    public NodeInfo(String id, String name, InetAddress address, int tcpPort, boolean manual){
        this.id = id;
        this.name = name;
        this.address = address;
        this.tcpPort = tcpPort;
        this.lastSeen = System.currentTimeMillis();
        this.manual = manual;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void updateLastSeen(){
        this.lastSeen = System.currentTimeMillis();
    }

    public void updateTcpPort(int port) {
        this.tcpPort = port;
    }

    public boolean isManual() {
        return manual;
    }

    public boolean isExpired(long timeoutMs){
        if (manual) return false;
        return System.currentTimeMillis() - lastSeen > timeoutMs;
    }

    @Override
    public String toString() {
        return name + " (" + address.getHostAddress() + ":" + tcpPort + ")";
    }
}
