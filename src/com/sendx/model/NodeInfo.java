package com.sendx.model;

import java.net.InetAddress;

public class NodeInfo {

    private final String id;
    private final String name;
    private final InetAddress address;
    private final int tcpPort;
    private volatile long lastSeen;

    public NodeInfo(String id, String name, InetAddress address, int tcpPort){
        this.id = id;
        this.name = name;
        this.address = address;
        this.tcpPort = tcpPort;
        this.lastSeen = System.currentTimeMillis();
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

    public boolean isExpired(long timeoutMs){
        return System.currentTimeMillis() - lastSeen > timeoutMs;
    }

    @Override
    public String toString() {
        return name + " (" + address.getHostAddress() + ":" + tcpPort + ")";
    }
}
