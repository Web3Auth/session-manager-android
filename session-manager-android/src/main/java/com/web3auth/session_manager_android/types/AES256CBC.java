package com.web3auth.session_manager_android.types;

import com.web3auth.session_manager_android.keystore.KeyStoreManager;

import org.bouncycastle.util.encoders.Hex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECFieldFp;
import java.security.spec.EllipticCurve;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class AES256CBC {
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private final byte[] AES_ENCRYPTION_KEY;
    private final byte[] ENCRYPTION_IV;

    private final byte[] MAC_KEY;

    public AES256CBC(String privateKeyHex, String ephemPublicKeyHex, String encryptionIvHex) throws NoSuchAlgorithmException {
        byte[] hash = SHA512.digest(toByteArray(ecdh(privateKeyHex, ephemPublicKeyHex)));
        byte[] encKeyBytes = Arrays.copyOfRange(hash, 0, 32);
        AES_ENCRYPTION_KEY = encKeyBytes;
        MAC_KEY = Arrays.copyOfRange(hash, 32, hash.length);
        ENCRYPTION_IV = toByteArray(encryptionIvHex);
    }

    /**
     * Utility method to convert a BigInteger to a byte array in unsigned
     * format as needed in the handshake messages. BigInteger uses
     * 2's complement format, i.e. it prepends an extra zero if the MSB
     * is set. We remove that.
     */
    public static byte[] toByteArray(BigInteger bi) {
        byte[] b = bi.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            int n = b.length - 1;
            byte[] newArray = new byte[n];
            System.arraycopy(b, 1, newArray, 0, n);
            b = newArray;
        }
        return b;
    }

    /**
     * Converts a hex string to a byte array
     * @param s - string to be converted
     * @return - byte[]
     */
    public static byte[] toByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public byte[] encrypt(byte[] src) throws TorusException {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, makeKey(), makeIv());
            return cipher.doFinal(src);
        } catch (Exception e) {
            e.printStackTrace();
            throw new TorusException("Torus Internal Error", e);
        }
    }

    public byte[] decrypt(String src) throws TorusException {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, makeKey(), makeIv());
            byte[] decrypt = cipher.doFinal(hexStringToByteArray(src));
            return decrypt;
        } catch (Exception e) {
            e.printStackTrace();
            throw new TorusException("There was an error decrypting data.", e);
        }
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];

        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }

        return data;
    }

    private BigInteger ecdh(String privateKeyHex, String ephemPublicKeyHex) {
        String affineX = ephemPublicKeyHex.substring(2, 66);
        String affineY = ephemPublicKeyHex.substring(66);

        ECPointArithmetic ecPoint = new ECPointArithmetic(new EllipticCurve(new ECFieldFp(new BigInteger("115792089237316195423570985008687907853269984665640564039457584007908834671663")), new BigInteger("0"), new BigInteger("7")), new BigInteger(affineX, 16), new BigInteger(affineY, 16), null);
        return ecPoint.multiply(new BigInteger(privateKeyHex, 16)).getX();
    }

    private Key makeKey() {
        return new SecretKeySpec(AES_ENCRYPTION_KEY, "AES");
    }

    private AlgorithmParameterSpec makeIv() {
        return new IvParameterSpec(ENCRYPTION_IV);
    }

    public String getMacKey() {
        return KeyStoreManager.convertByteToHexadecimal(MAC_KEY);
    }

    public byte[] getMac(byte[] cipherTextBytes) throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(ENCRYPTION_IV);
        outputStream.write(AES_ENCRYPTION_KEY);
        outputStream.write(cipherTextBytes);
        byte[] combinedData = outputStream.toByteArray();
        return hmacSha256Sign(MAC_KEY, combinedData);
    }

    public byte[] hmacSha256Sign(byte[] key, byte[] data) throws NoSuchAlgorithmException, InvalidKeyException {
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, "HMACSHA256");
        Mac mac = Mac.getInstance("HMACSHA256");
        mac.init(secretKeySpec);
        byte[] macData = mac.doFinal(data);
        return Hex.encode(macData);
    }
}
