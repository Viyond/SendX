package com.sendx;

import com.sendx.discovery.DiscoveryService;
import com.sendx.model.NodeInfo;
import com.sendx.transfer.FileReceiver;
import com.sendx.transfer.FileSender;
import com.sendx.util.Crypto;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

public class Main {
    private static final int DEFAULT_TCP_PORT = 0;//0 = OS picks a random available port
    private static final String DEFAULT_RECEIVE_DIR = "sendx_received";

    public static void main(String[] args) {
        //Force IPv4 stack for multicast compatibility (macOS defaults to IPv6)
        System.setProperty("java.net.preferIPv4Stack", "true");

        int tcpPort = DEFAULT_TCP_PORT;
        String nodeName = getHostName();
        String receiveDir = DEFAULT_RECEIVE_DIR;

        //Simple arg parsing
        for (int i=0; i < args.length; i++){
            switch (args[i]) {
                case "--port", "-p" -> tcpPort = Integer.parseInt(args[++i]);
                case "--name", "-n" -> nodeName = args[++i];
                case "--dir", "-d" -> receiveDir = args[++i];
            }
        }

        //Password input
        String password = readPassword();
        if (password == null || password.isEmpty()){
            System.err.println("Password cannot be empty.");
            System.exit(1);
        }
        Crypto crypto = new Crypto(password);
        String nodeId = UUID.randomUUID().toString().substring(0, 8);

        //Start discovery first to resolve LAN address
        DiscoveryService discovery = new DiscoveryService(nodeId, nodeName, 0);
        try{
            discovery.start();
        }catch (IOException e){
            System.err.println("Failed to start discovery: " + e.getMessage());
            System.exit(1);
        }
        InetAddress lanAddress = discovery.getLocalAddress();
        //Start file receiver bound to LAN address (bypasses VPN)
        FileReceiver receiver = new FileReceiver(tcpPort, new File(receiveDir), lanAddress, crypto);
        try{
            receiver.start();
        }catch (IOException e) {
            System.err.println("Failed to start receiver: " + e.getMessage());
            System.exit(1);
        }
        int actualPort = receiver.getLocalPort();
        // Update discovery to broadcast the actual TCP port
        discovery.setTcpPort(actualPort);

        System.out.println("=== SendX - LAN File Transfer (AES-256 Encrypted) ===");
        System.out.println("  Node: " + nodeName + " (id: " + nodeId + ")");
        System.out.println("  Bind: " + lanAddress.getHostAddress() + ":" + actualPort);
        System.out.println("  Receive Dir: " + new File(receiveDir).getAbsolutePath());
        System.out.println();
        System.out.println("  Ready, Type 'help' for commands.");
        System.out.println();

        //CLI loop
        FileSender sender = new FileSender(lanAddress, crypto);
        Scanner scanner = new Scanner(System.in);

        while (true){
            System.out.print(">  ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 3);
            String cmd = parts[0].toLowerCase();

            switch (cmd) {
                case "list", "ls" -> listNodes(discovery);
                case "send" -> handleSend(parts, discovery, sender);
                case "connect" -> handleConnect(parts, discovery);
                case "help", "?" -> printHelp();
                case "exit", "quit", "q" -> {
                    System.out.println("  Shutting down...");
                    discovery.stop();
                    receiver.stop();
                    System.exit(0);
                }
                default -> System.out.println("  Unknown command, Type 'help' for usage.");
            }
        }
    }

    private static String readPassword() {
        Console console = System.console();
        if (console != null){
            char[] pw = console.readPassword("Enter password: ");
            return pw != null ? new String(pw) : null;
        } else {
            //fallback for IDEs where System.console() is null
            System.out.print("Enter password: ");
            Scanner scanner = new Scanner(System.in);
            return scanner.nextLine();
        }
    }

    private static void listNodes(DiscoveryService discovery){
        List<NodeInfo> nodes = discovery.getOnlineNodes();
        if (nodes.isEmpty()){
            System.out.println("  No nodes discovered yet, Waiting for peers...");
            return;
        }
        System.out.println("  Online nodes:");
        for (int i=0; i < nodes.size(); i++){
            System.out.println("  [" + (i+1) + "] " + nodes.get(i));
        }
    }

    private static void handleSend(String[] parts, DiscoveryService discovery, FileSender sender) {
        if (parts.length < 3){
            System.out.println("  Usage: send <node#> <file_path>");
            return;
        }

        int index;
        try{
            index = Integer.parseInt(parts[1]) - 1;
        }catch (NumberFormatException e){
            System.out.println("  Invalid node number.");
            return;
        }

        NodeInfo target = discovery.getNodeByIndex(index);
        if (target == null){
            System.out.println("  Node not found. Use 'list' to see available nodes.");
            return;
        }

        File file = new File(parts[2]);
        if (!file.exists()){
            System.out.println("  File not found: " + parts[2]);
            return;
        }

        sender.send(target, file);
    }

    private static void handleConnect(String[] parts, DiscoveryService discovery){
        if (parts.length < 2){
            System.out.println("  Usage: connect <ip:port>");
            return;
        }
        String target = parts[1];
        String[] hostPort = target.split(":");
        if (hostPort.length != 2){
            System.out.println("  Invalid format. Use: connect <ip:port>");
            return;
        }
        try{
            InetAddress address = InetAddress.getByName(hostPort[0]);
            int port = Integer.parseInt(hostPort[1]);
            discovery.addManualNode(address, port);
            System.out.println("  Added node:" + hostPort[0] + ":" + port);
        }catch (Exception e){
            System.out.println("  Error: " + e.getMessage());
        }
    }

    private static void printHelp() {
        System.out.println("  Commands:");
        System.out.println("    list                - Show online nodes");
        System.out.println("    send <#> <path>     - Send file to node #");
        System.out.println("    connect <ip:port>   - Manually add a peer node");
        System.out.println("    help                - Show this help");
        System.out.println("    exit                - Quit SendX");
    }

    private static String getHostName() {
        try{
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e){
            return "unknown";
        }
    }
}
