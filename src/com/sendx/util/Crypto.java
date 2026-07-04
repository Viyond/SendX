package com.sendx.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

public class Crypto {
    private static final String ALGORITHM = "AES/CTR/NoPadding";
    private static final int KEY_LENGTH = 256;
    private static final int IV_LENGTH = 16;
    private static final int ITERATIONS = 65536;
    //fixed salt, simple enough for LAN usage
    private static final byte[] SALT = "SendX_2026_Salt!".getBytes(StandardCharsets.UTF_8);

    private final SecretKey key;

    public Crypto(String password){
        try{
            KeySpec spec = new PBEKeySpec(password.toCharArray(), SALT, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            this.key = new SecretKeySpec(keyBytes, "AES");
        }catch (Exception e){
            throw new RuntimeException("Failed to derive key", e);
        }
    }

    public byte[] generateIv() {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    public Cipher encryptCipher(byte[] iv) {
        try{
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            return cipher;
        }catch (Exception e){
            throw new RuntimeException("Failed to create encrypt cipher.", e);
        }
    }

    public Cipher decryptCipher(byte[] iv){
        try{
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            return cipher;
        }catch (Exception e){
            throw new RuntimeException("Failed to create decrypt cipher.", e);
        }
    }

    public Cipher encryptCipherAtOffset(byte[] iv, long offset) {
        return cipherAtOffset(Cipher.ENCRYPT_MODE, iv, offset);
    }

    public Cipher decryptCipherAtOffset(byte[] iv, long offset) {
        return cipherAtOffset(Cipher.DECRYPT_MODE, iv, offset);
    }

    private Cipher cipherAtOffset(int mode, byte[] iv, long offset) {
        try{
            //calculate the CTR counter value at the given offset
            long blockNumber = offset / 16;
            byte[] adjustedIv = iv.clone();
            //Add blockNumber to the IV (big-endian addition on the last 8 bytes)
            for (int i = 15; i >=0 && blockNumber > 0; i--){
                long sum = (adjustedIv[i] & 0xFFL) + (blockNumber &0xFF);
                adjustedIv[i] = (byte) sum;
                blockNumber = (blockNumber >>>8) + (sum >>>8);
            }

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(mode, key, new IvParameterSpec(adjustedIv));

            //skip the partial block bytes (offset % 16)
            int skip = (int) (offset % 16);
            if (skip > 0){
                cipher.update(new byte[skip]);
            }

            return cipher;
        }catch (Exception e){
            throw new RuntimeException("Failed to create cipher at offset", e);
        }
    }

    public static int getIvLength() {
        return IV_LENGTH;
    }
}
