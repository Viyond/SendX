package com.sendx.transfer;

import com.sendx.protocol.Protocol;
import com.sendx.util.Crypto;
import com.sendx.util.ProgressBar;

import javax.crypto.Cipher;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class FileReceiver {
    private final int port;
    private final File receiveDir;
    private final InetAddress bindAddress;
    private final Crypto crypto;
    private ServerSocket serverSocket;
    private volatile boolean running;

    public FileReceiver(int port, File receiveDir, InetAddress bindAddress, Crypto crypto){
        this.port = port;
        this.receiveDir = receiveDir;
        this.bindAddress = bindAddress;
        this.crypto = crypto;
        if (!receiveDir.exists()){
            receiveDir.mkdirs();
        }
    }

    public int getLocalPort() {
        return serverSocket != null? serverSocket.getLocalPort() : port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, port));
        running = true;

        Thread acceptThread = new Thread(this::acceptLoop, "file-receiver");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()){
            try {serverSocket.close();} catch (IOException ignored){}
        }
    }

    private void acceptLoop() {
        while (running){
            try{
                Socket client = serverSocket.accept();
                if (client != null){
                    Thread handler = new Thread(() -> handleTransfer(client), "transfer-handler");
                    handler.setDaemon(true);
                    handler.start();
                }
            }catch (IOException e){
                if (running){
                    System.out.println("Accept error:" + e.getMessage());
                }
            }
        }
    }

    private void handleTransfer(Socket client) {
        try (client) {
            client.setReceiveBufferSize(256 * 1024);
            DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream(), 256 * 1024));
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            //Read IV
            byte[] iv = new byte[Crypto.getIvLength()];
            in.readFully(iv);
            Cipher cipher = crypto.decryptCipher(iv);

            //Password handshake
            byte[] encryptedChallenge = new byte[Protocol.getChallengePlain().length];
            in.readFully(encryptedChallenge);
            byte[] decryptedChallenge = cipher.update(encryptedChallenge);
            if (!Arrays.equals(decryptedChallenge, Protocol.getChallengePlain())) {
                out.writeByte(Protocol.RESP_AUTH_FAIL);
                out.flush();
                String senderAddr = client.getInetAddress().getHostAddress();
                System.out.println("\n  Rejected connection from " + senderAddr + ": password mismatch.");
                System.out.print("\n> ");
                return;
            }
            out.writeByte(Protocol.RESP_OK);
            out.flush();

            //Read header (encrypted)
            Protocol.TransferHeader header = Protocol.readHeader(in, cipher);
            String sendAddr = client.getInetAddress().getHostAddress();

            //Check for existing partial transfer (resume support)
            File outFile = new File(receiveDir, header.fileName);
            File metaFile = new File(receiveDir, header.fileName + ".sendx.meta");
            long offset = 0;

            if (metaFile.exists() && outFile.exists()){
                //Read stored SHA-256 from meta to verify it's the same file
                try {
                    String metaContent = new String(Files.readAllBytes(metaFile.toPath()));
                    String[] metaParts = metaContent.split("\n");
                    String metaSha = metaParts[0];
                    if (metaSha.equals(Protocol.bytesToHex(header.sha256))) {
                        offset = outFile.length();
                    }else {
                        //different file, start fresh
                        outFile.delete();
                        metaFile.delete();
                    }
                }catch (Exception e){
                    outFile.delete();
                    metaFile.delete();
                }
            }else if (outFile.exists() && !metaFile.exists()){
                // Completed file already exists, avoid overwriting
                String name = header.fileName;
                String base = name.contains(".")? name.substring(0, name.lastIndexOf(".")) : name;
                String ext = name.contains(".")? name.substring(name.lastIndexOf(".")) : "";
                int counter = 0;
                while (outFile.exists() && !metaFile.exists()){
                    outFile = new File(receiveDir, base + "_" + counter + ext);
                    metaFile = new File(receiveDir, base + "_" + counter + ext + ".sendx.meta");
                    counter++;
                }
            }

            //Send resume offset to sender
            out.writeLong(offset);
            out.flush();

            if (offset > 0){
                System.out.println("\n  Resuming " + header.fileName + " from " + ProgressBar.formatSize(offset)
                    + " (" + (offset * 100 / header.fileSize) + "%)");
            }else {
                System.out.println("\n  Receiving " + header.fileName + " (" + ProgressBar.formatSize(header.fileSize)
                    + ") from " + sendAddr + "...");
            }

            //Write meta file (marks transfer as in-progress)
            Files.writeString(metaFile.toPath(), Protocol.bytesToHex(header.sha256) + "\n" + header.fileSize);

            //Create data cipher at offset position
            Cipher dataCipher = crypto.decryptCipherAtOffset(iv, offset);

            long transferStart = System.currentTimeMillis();
            ProgressBar progress = new ProgressBar("Receive", header.fileSize);
            progress.update(offset);
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long totalReceived = offset;
            long remaining = header.fileSize - offset;

            try(FileOutputStream fos = new FileOutputStream(outFile, true);//append mode
                BufferedOutputStream bos = new BufferedOutputStream(fos, 256 * 1024)) {
                long networkRead = 0;
                while (networkRead < remaining){
                    int toRead = (int)Math.min(buffer.length, remaining - networkRead);
                    int read = in.read(buffer, 0, toRead);
                    if (-1 == read){
                        throw new IOException("Connection closed prematurely");
                    }
                    byte[] decrypted = dataCipher.update(buffer, 0, read);
                    if (decrypted != null){
                        bos.write(decrypted);
                        totalReceived += decrypted.length;
                        networkRead += read;
                        progress.update(totalReceived);
                    }
                }
                byte[] finalBlock = dataCipher.doFinal();
                if (finalBlock != null && finalBlock.length > 0){
                    bos.write(finalBlock);
                    totalReceived += finalBlock.length;
                    progress.update(totalReceived);
                }
            }

            //Verify checksum over the entire file
            MessageDigest digest;
            try{
                digest = MessageDigest.getInstance("SHA-256");
            }catch (NoSuchAlgorithmException e){
                throw new RuntimeException(e);
            }
            try(FileInputStream fis = new FileInputStream(outFile)) {
                int read;
                while ((read = fis.read(buffer)) != -1){
                    digest.update(buffer, 0, read);
                }
            }
            byte[] computedHash = digest.digest();
            boolean match = MessageDigest.isEqual(computedHash, header.sha256);

            long elapsed = System.currentTimeMillis() - transferStart;
            if (match){
                out.writeByte(Protocol.RESP_OK);
                progress.complete(true);
                System.out.println("  Saved to: " + outFile.getAbsolutePath());
                System.out.println("  Size: " + ProgressBar.formatSize(header.fileSize));
                System.out.println("  Time: " + formatTime(elapsed));
                System.out.println("  SHA-256 verified: " + Protocol.bytesToHex(computedHash));
                //Remove meta file on success
            } else {
                out.writeByte(Protocol.RESP_CHECKSUM_FAIL);
                progress.complete(false);
                System.out.println("  SHA-256 MISMATCH! File may be corrupted (wrong password?).");
                outFile.delete();
                metaFile.delete();
            }
            out.flush();

            System.out.println("\n>  ");
        }catch (Exception e) {
            System.out.println("\n  Receive error: " + e.getMessage());
            System.out.print("\n> ");
        }
    }

    private static String formatTime(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m" + (seconds % 60) + "s";
    }
}
