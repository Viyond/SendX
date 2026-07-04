package com.sendx.protocol;

import javax.crypto.Cipher;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Protocol {
    public static final byte[] MAGIC = "SNDX".getBytes(StandardCharsets.UTF_8);
    public static final int BUFFER_SIZE = 512 * 1024; //512KB
    public static final byte RESP_OK = 0x00;
    public static final byte RESP_CHECKSUM_FAIL = 0x01;
    public static final byte RESP_AUTH_FAIL = 0x02;
    //MAGIC(4) + nameLen(4) + name(max 255) + fileSize(8) + sha256(32) = 303, round up to 1024
    private static final int MAX_HEADER_SIZE = 1024;

    //16-byte challenge plaintext for password verification
    private static final byte[] CHALLENGE_PLAIN = "SENDX_AUTH_OK!!!".getBytes(StandardCharsets.UTF_8);

    public static byte[] getChallengePlain() {
        return CHALLENGE_PLAIN;
    }

    public static void writeHeader(DataOutputStream out, String fileName, long fileSize, byte[] sha256, Cipher cipher) throws IOException{
        // Build header in memory, then encrypt and send
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        DataOutputStream headerOut = new DataOutputStream(headerBuf);
        headerOut.write(MAGIC);
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        headerOut.write(nameBytes.length);
        headerOut.write(nameBytes);
        headerOut.writeLong(fileSize);
        headerOut.write(sha256);
        headerOut.flush();

        byte[] headerData = headerBuf.toByteArray();
        byte[] encrypted = cipher.update(headerData);
        if (encrypted == null){
            encrypted = new byte[0];
        }
        //Send encrypted header length so receiver knows how much to read
        out.writeInt(encrypted.length);
        out.write(encrypted);
        out.flush();
    }

    public static TransferHeader readHeader(DataInputStream in, Cipher cipher) throws IOException{
        int encryptedLen = in.readInt();
        if (encryptedLen <= 0 || encryptedLen > MAX_HEADER_SIZE) {
            throw new IOException("Invalid encrypted header length: " + encryptedLen);
        }
        byte[] encryptedHeader = new byte[encryptedLen];
        in.readFully(encryptedHeader);

        byte[] headerData = cipher.update(encryptedHeader);
        if (headerData == null || headerData.length == 0){
            throw new IOException("Decryption produced no data");
        }

        DataInputStream headerIn = new DataInputStream(new ByteArrayInputStream(headerData));

        byte[] magic = new byte[4];
        headerIn.readFully(magic);
        for (int i=0; i<4; i++){
            if (magic[i] != MAGIC[i]){
                throw new IOException("Invalid protocol magic (wrong password?)");
            }
        }
        int nameLen = headerIn.readInt();
        if (nameLen <=0 || nameLen > 4096){
            throw new IOException("Invalid file name length: " + nameLen + " (wrong password?)");
        }
        byte[] nameBytes = new byte[nameLen];
        headerIn.readFully(nameBytes);
        String fileName = new String(nameBytes, StandardCharsets.UTF_8);

        long fileSize = headerIn.readLong();
        if (fileSize < 0){
            throw new IOException("Invalid file size: " + fileSize);
        }

        byte[] sha256 = new byte[32];
        headerIn.readFully(sha256);

        return new TransferHeader(fileName, fileSize, sha256);
    }

    public static byte[] computeSha256(File file) throws IOException {
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            try(FileInputStream fis = new FileInputStream(file)) {
                int read;
                while ((read = fis.read(buffer)) != -1){
                    digest.update(buffer, 0, read);
                }
            }
            return digest.digest();
        }catch (NoSuchAlgorithmException e){
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static String bytesToHex(byte[] bytes){
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes){
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static class TransferHeader {
        public final String fileName;
        public final long fileSize;
        public final byte[] sha256;

        public TransferHeader(String fileName, long fileSize, byte[] sha256){
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.sha256 = sha256;
        }
    }
}
