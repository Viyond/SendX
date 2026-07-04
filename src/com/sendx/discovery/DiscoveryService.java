package com.sendx.discovery;

import com.sendx.model.NodeInfo;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class DiscoveryService {

    private static final int DISCOVERY_PORT = 9876;
    private static final int HEARTBEAT_INTERVAL_MS = 2000;
    private static final long NODE_TIMEOUT_MS = 6000;
    private static final int PACKET_BUFFER_SIZE = 512;
    private static final String PREFIX = "SENDX|";

    private final String nodeId;
    private final String nodeName;
    private volatile int tcpPort;
    private final ConcurrentHashMap<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    private DatagramSocket socket;
    private InetAddress broadcastAddress;
    private InetAddress localAddress;
    private volatile boolean running;

    public DiscoveryService(String nodeId, String nodeName, int tcpPort){
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.tcpPort = tcpPort;
    }

    public InetAddress getLocalAddress(){
        return localAddress;
    }

    public void setTcpPort(int port){
        this.tcpPort = port;
    }

    public void start() throws IOException {
        //Find LAN interface and its broadcast address
        resolveNetworkInfo();

        //Single socket: bound to LAN IP, listens on discovery port
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(new InetSocketAddress(DISCOVERY_PORT));

        running = true;

        Thread sender = new Thread(this::heartbeatLoop, "discovery-sender");
        sender.setDaemon(true);
        sender.start();

        Thread listener = new Thread(this::listenLoop, "discovery-listener");
        listener.setDaemon(true);
        listener.start();

        Thread cleaner = new Thread(this::cleanLoop, "discovery-cleaner");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()){
            socket.close();
        }
    }

    public List<NodeInfo> getOnlineNodes() {
        return new ArrayList<>(nodes.values());
    }

    public NodeInfo getNodeByIndex(int index){
        List<NodeInfo> list = getOnlineNodes();
        if (index >=0 && index < list.size()){
            return list.get(index);
        }
        return null;
    }

    public void addManualNode(InetAddress address, int port){
        String id = "manual-" + address.getHostAddress() + ":" + port;
        String name = address.getHostName();
        NodeInfo node = new NodeInfo(id, name, address, port, true);
        nodes.put(id, node);
    }

    private void heartbeatLoop() {
        while (running){
            try{
                String message = PREFIX + nodeId + "|" + nodeName + "|" + tcpPort;
                byte[] data = message.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddress, DISCOVERY_PORT);
                socket.send(packet);
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            }catch (InterruptedException e){
                break;
            }catch (IOException e){
                if (running){
                    System.err.println("Heartbeat send error: " + e.getMessage());
                }
            }
        }
    }

    private void listenLoop(){
        byte[] buffer = new byte[PACKET_BUFFER_SIZE];
        while (running){
            try{
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                if (!message.startsWith(PREFIX)) continue;

                String[] parts = message.substring(PREFIX.length()).split("\\|");
                if (parts.length != 3) continue;

                String remoteId = parts[0];
                String remoteName = parts[1];
                int remotePort = Integer.parseInt(parts[2]);

                if (remoteId.equals(nodeId)) continue; //ignore self

                NodeInfo existing = nodes.get(remoteId);
                if (existing != null){
                    existing.updateLastSeen();
                    if (existing.getTcpPort() != remotePort){
                        existing.updateTcpPort(remotePort);
                    }
                }else {
                    NodeInfo newNode = new NodeInfo(remoteId, remoteName, packet.getAddress(), remotePort);
                    nodes.put(remoteId, newNode);
                }
            }catch (IOException e){
                if (running){
                    System.err.println("ListenLoop recv error: " + e.getMessage());
                }
            }
        }
    }

    private void cleanLoop(){
        while (running){
            try {
                Thread.sleep(2000);
                nodes.entrySet().removeIf(entry -> entry.getValue().isExpired(NODE_TIMEOUT_MS));
            }catch (InterruptedException e){
                break;
            }
        }
    }

    private void resolveNetworkInfo() throws SocketException{
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()){
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint() || ni.isVirtual()){
                continue;
            }
            String name = ni.getName();
            if (!name.startsWith("en") && !name.startsWith("eth") && !name.startsWith("bridge")){
                continue;
            }
            for (InterfaceAddress ifAddr : ni.getInterfaceAddresses()){
                InetAddress addr = ifAddr.getAddress();
                if (addr instanceof Inet4Address){
                    localAddress = addr;
                    broadcastAddress = ifAddr.getBroadcast();
                    if (broadcastAddress != null){
                        return;
                    }
                }
            }
        }
        //Fallback: use 255.255.255.255
        if (localAddress == null){
            try{
                localAddress = InetAddress.getLocalHost();
            }catch (Exception e){
                //ignore
            }
        }
        if (null == broadcastAddress){
            try{
                broadcastAddress = InetAddress.getByName("255.255.255.255");
            }catch (Exception e){
                //ignore
            }
        }
    }
}
