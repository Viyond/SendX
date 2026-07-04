package com.sendx.transfer;

import com.sendx.model.NodeInfo;
import com.sendx.protocol.Protocol;
import com.sendx.util.Crypto;
import com.sendx.util.ProgressBar;

import javax.crypto.Cipher;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class FileSender {

    private final InetAddress localAddress;
    private final Crypto crypto;

    public FileSender(InetAddress localAddress, Crypto crypto){
        this.localAddress = localAddress;
        this.crypto = crypto;
    }

    public void send(NodeInfo target, File file) {
        if (!file.exists() || !file.isFile()) {
            System.out.println("  Error: File not found - " + file.getPath());
            return;
        }

        long fileSize = file.length();
        System.out.println("  Sending " + file.getName() + " (" + ProgressBar.formatSize(fileSize) + ") to " + target.getName() + "...");
        System.out.println("  Computing SHA-256...");

        byte[] sha256;
        try{
            sha256 = Protocol.computeSha256(file);
        }catch (IOException e){
            System.out.println("  Error computing checksum: " + e.getMessage());
            return;
        }

        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(localAddress, 0));
            socket.connect(new InetSocketAddress(target.getAddress(), target.getTcpPort()), 5000);
            socket.setSendBufferSize(256 * 1024);
            socket.setTcpNoDelay(false);

            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 256 * 1024));
            DataInputStream in = new DataInputStream(socket.getInputStream());

            //Generate and send IV
            byte[] iv = crypto.generateIv();
            out.write(iv);

            //Password handshake: encrypt challenge and send
            Cipher cipher = crypto.encryptCipher(iv);
            byte[] encryptedChallenge = cipher.update(Protocol.getChallengePlain());
            out.write(encryptedChallenge);
            out.flush();

            //Wait for auth response
            byte authResp = in.readByte();
            if (authResp == Protocol.RESP_AUTH_FAIL){
                System.out.println("  Authentication failed! Password mismatch.");
                return;
            }

            //send header
            Protocol.writeHeader(out, file.getName(), fileSize, sha256, cipher);

            //Read resume offset from receiver
            long offset = in.readLong();
            if (offset > 0 && offset < fileSize){
                System.out.println("  Remaining from " + ProgressBar.formatSize(offset) + " (" + (offset * 100 / fileSize) + "%)");
            }

            //Create a new cipher at the file data offset
            Cipher dataCipher = crypto.encryptCipherAtOffset(iv, offset);

            //Send file data (encrypted)
            long transferStart = System.currentTimeMillis();
            ProgressBar progress = new ProgressBar("Send", fileSize);
            progress.update(offset);
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long totalSent = offset;

            try(FileInputStream fis = new FileInputStream(file)) {
                if (offset > 0) {
                    long skipped = fis.skip(offset);
                    if (skipped != offset){
                        throw new IOException("Failed to skip to offset " + offset);
                    }
                }
                int read;
                while ((read = fis.read(buffer)) != -1){
                    byte[] encrypted = dataCipher.update(buffer, 0, read);
                    if (encrypted != null){
                        out.write(encrypted);
                    }
                    totalSent += read;
                    progress.update(totalSent);
                }
                byte[] finalBlock = dataCipher.doFinal();
                if (finalBlock != null && finalBlock.length > 0){
                    out.write(finalBlock);
                }
                out.flush();
            }

            //wait for response
            byte response = in.readByte();
            long elapsed = System.currentTimeMillis() - transferStart;
            if (response == Protocol.RESP_OK){
                progress.complete(true);
                System.out.println("  File: " + file.getAbsolutePath());
                System.out.println("  Size: " + ProgressBar.formatSize(fileSize));
                System.out.println("  Time: " + formatTime(elapsed));
                System.out.println("  SHA-256: " + Protocol.bytesToHex(sha256));
            }else {
                progress.complete(false);
                System.out.println("  Remote checksum verification failed! (Wrong password?)");
            }
        } catch (Exception e){
            System.out.println("\n  Transfer error: " + e.getMessage());
        }
    }

    private static String formatTime(long ms){
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m" + (seconds % 60) + "s";
    }
}
