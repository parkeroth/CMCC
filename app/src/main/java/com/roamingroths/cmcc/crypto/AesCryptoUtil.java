package com.roamingroths.cmcc.crypto;

import android.util.Base64;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;

import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Created by parkeroth on 8/27/17.
 */

public class AesCryptoUtil {

  private static final int KEY_SIZE = 128;
  private static final String ALG = "AES/CBC/PKCS5Padding";
  private static final Joiner ON_BAR = Joiner.on("|");

  public static SecretKey createKey() throws NoSuchAlgorithmException {
    KeyGenerator keyGen = KeyGenerator.getInstance("AES");
    keyGen.init(KEY_SIZE);
    return keyGen.generateKey();
  }

  public static String encrypt(SecretKey key, String rawText) throws Exception {
    Cipher cipher = Cipher.getInstance(ALG);
    IvParameterSpec iv = new IvParameterSpec(createKey().getEncoded());
    String ivStr = Base64.encodeToString(iv.getIV(), Base64.NO_WRAP);
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getEncoded(), "AES"), iv);

    byte[] cipherBytes = cipher.doFinal(rawText.getBytes(Charsets.UTF_8));
    String cipherStr = Base64.encodeToString(cipherBytes, Base64.NO_WRAP);

    return ON_BAR.join(ivStr, cipherStr);
  }

  public static String decrypt(SecretKey key, String combinedStr) throws Exception {
    String[] parts = combinedStr.split("\\|");
    IvParameterSpec iv = new IvParameterSpec(Base64.decode(parts[0], Base64.NO_WRAP));
    byte[] cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP);

    Cipher cipher = Cipher.getInstance(ALG);
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getEncoded(), "AES"), iv);
    return new String(cipher.doFinal(cipherBytes), Charsets.UTF_8);
  }

  public static String serializeKey(SecretKey key) {
    return Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP);
  }

  public static SecretKey parseKey(String keyStr) {
    byte[] encodedKey = Base64.decode(keyStr, Base64.NO_WRAP);
    return new SecretKeySpec(encodedKey, "AES");
  }
}
