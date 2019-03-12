/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi.util;

import android.annotation.NonNull;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.security.DigestException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Tools to provide integrity checking of byte arrays based on NIAP Common Criteria Protection
 * Profile <a href="https://www.niap-ccevs.org/MMO/PP/-417-/#FCS_STG_EXT.3.1">FCS_STG_EXT.3.1</a>.
 */
public class DataIntegrityChecker {
    private static final String TAG = "DataIntegrityChecker";

    private static final String FILE_SUFFIX = ".encrypted-checksum";
    private static final String ALIAS_SUFFIX = ".data-integrity-checker-key";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final int GCM_TAG_LENGTH = 128;
    private static final String KEY_STORE = "AndroidKeyStore";

    private final File mIntegrityFile;

    /**
     * Construct a new integrity checker to update and check if/when a data file was altered
     * outside expected conditions.
     *
     * @param integrityFilename The {@link File} path prefix for where the integrity data is stored.
     *                          A file will be created in the name of integrityFile with the suffix
     *                          {@link DataIntegrityChecker#FILE_SUFFIX} We recommend using the same
     *                          path as the file for which integrity is performed on.
     * @throws NullPointerException When integrity file is null or the empty string.
     */
    public DataIntegrityChecker(@NonNull String integrityFilename) {
        if (TextUtils.isEmpty(integrityFilename)) {
            throw new NullPointerException("integrityFilename must not be null or the empty "
                    + "string");
        }
        mIntegrityFile = new File(integrityFilename + FILE_SUFFIX);
    }

    /**
     * Computes a digest of a byte array, encrypt it, and store the result
     *
     * Call this method immediately before storing the byte array
     *
     * @param data The data desired to ensure integrity
     */
    public void update(byte[] data) {
        if (data == null || data.length < 1) {
            Log.e(TAG, "No data to update.");
            writeErrorToFile(new Exception("No data to update"));
            return;
        }
        byte[] digest = getDigest(data);
        if (digest == null || digest.length < 1) {
            return;
        }
        String alias = mIntegrityFile.getName() + ALIAS_SUFFIX;
        EncryptedData integrityData = encrypt(digest, alias);
        if (integrityData != null) {
            writeIntegrityData(integrityData, mIntegrityFile);
        } else {
            writeErrorToFile(new Exception("integrityData null upon update"));
        }
    }

    /**
     * Check the integrity of a given byte array
     *
     * Call this method immediately before trusting the byte array. This method will return false
     * when the byte array was altered since the last {@link #update(byte[])}
     * call, when {@link #update(byte[])} has never been called, or if there is
     * an underlying issue with the cryptographic functions or the key store.
     *
     * @param data The data to check if its been altered
     * @throws DigestException The integrity mIntegrityFile cannot be read. Ensure
     *      {@link #isOk(byte[])} is called after {@link #update(byte[])}. Otherwise, consider the
     *      result vacuously true and immediately call {@link #update(byte[])}.
     * @return true if the data was not altered since {@link #update(byte[])} was last called
     */
    public boolean isOk(byte[] data) throws DigestException {
        if (data == null || data.length < 1) {
            return false;
        }
        byte[] currentDigest = getDigest(data);
        if (currentDigest == null || currentDigest.length < 1) {
            return false;
        }
        EncryptedData encryptedData = readIntegrityData(mIntegrityFile);
        if (encryptedData == null) {
            throw new DigestException("No stored digest is available to compare.");
        }
        byte[] storedDigest = decrypt(encryptedData);
        if (storedDigest == null) {
            return false;
        }
        return constantTimeEquals(storedDigest, currentDigest);
    }

    private byte[] getDigest(byte[] data) {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(data);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "getDigest could not find algorithm: " + DIGEST_ALGORITHM);
            writeErrorToFile(e);
            return null;
        }
    }

    private EncryptedData encrypt(byte[] data, String keyAlias) {
        EncryptedData encryptedData = null;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            SecretKey secretKeyReference = getOrCreateSecretKey(keyAlias);
            if (secretKeyReference != null) {
                cipher.init(Cipher.ENCRYPT_MODE, secretKeyReference);
                encryptedData = new EncryptedData(cipher.doFinal(data), cipher.getIV(), keyAlias);
            } else {
                writeErrorToFile(new Exception("secretKeyReference is null."));
            }
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "encrypt could not find the algorithm: " + CIPHER_ALGORITHM);
            writeErrorToFile(e);
        } catch (NoSuchPaddingException e) {
            Log.e(TAG, "encrypt had a padding exception");
            writeErrorToFile(e);
        } catch (InvalidKeyException e) {
            Log.e(TAG, "encrypt received an invalid key");
            writeErrorToFile(e);
        } catch (BadPaddingException e) {
            Log.e(TAG, "encrypt had a padding problem");
            writeErrorToFile(e);
        } catch (IllegalBlockSizeException e) {
            Log.e(TAG, "encrypt had an illegal block size");
            writeErrorToFile(e);
        }
        return encryptedData;
    }

    private byte[] decrypt(EncryptedData encryptedData) {
        byte[] decryptedData = null;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.getIv());
            SecretKey secretKeyReference = getOrCreateSecretKey(encryptedData.getKeyAlias());
            if (secretKeyReference != null) {
                cipher.init(Cipher.DECRYPT_MODE, secretKeyReference, spec);
                decryptedData = cipher.doFinal(encryptedData.getEncryptedData());
            }
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "decrypt could not find cipher algorithm " + CIPHER_ALGORITHM);
            writeErrorToFile(e);
        } catch (NoSuchPaddingException e) {
            Log.e(TAG, "decrypt could not find padding algorithm");
            writeErrorToFile(e);
        } catch (IllegalBlockSizeException e) {
            Log.e(TAG, "decrypt had a illegal block size");
            writeErrorToFile(e);
        } catch (BadPaddingException e) {
            Log.e(TAG, "decrypt had bad padding");
            writeErrorToFile(e);
        } catch (InvalidKeyException e) {
            Log.e(TAG, "decrypt had an invalid key");
            writeErrorToFile(e);
        } catch (InvalidAlgorithmParameterException e) {
            Log.e(TAG, "decrypt had an invalid algorithm parameter");
            writeErrorToFile(e);
        }
        return decryptedData;
    }

    private SecretKey getOrCreateSecretKey(String keyAlias) {
        SecretKey secretKey = null;
        try {
            KeyStore keyStore = KeyStore.getInstance(KEY_STORE);
            keyStore.load(null);
            if (keyStore.containsAlias(keyAlias)) { // The key exists in key store. Get the key.
                KeyStore.SecretKeyEntry secretKeyEntry = (KeyStore.SecretKeyEntry) keyStore
                        .getEntry(keyAlias, null);
                if (secretKeyEntry != null) {
                    secretKey = secretKeyEntry.getSecretKey();
                } else {
                    writeErrorToFile(new Exception("keystore contains the alias and the secret key "
                            + "entry was null"));
                }
            } else { // The key does not exist in key store. Create the key and store it.
                KeyGenerator keyGenerator = KeyGenerator
                        .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE);

                KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build();

                keyGenerator.init(keyGenParameterSpec);
                secretKey = keyGenerator.generateKey();
            }
        } catch (CertificateException e) {
            Log.e(TAG, "getOrCreateSecretKey had a certificate exception.");
            writeErrorToFile(e);
        } catch (InvalidAlgorithmParameterException e) {
            Log.e(TAG, "getOrCreateSecretKey had an invalid algorithm parameter");
            writeErrorToFile(e);
        } catch (IOException e) {
            Log.e(TAG, "getOrCreateSecretKey had an IO exception.");
            writeErrorToFile(e);
        } catch (KeyStoreException e) {
            Log.e(TAG, "getOrCreateSecretKey cannot find the keystore: " + KEY_STORE);
            writeErrorToFile(e);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "getOrCreateSecretKey cannot find algorithm");
            writeErrorToFile(e);
        } catch (NoSuchProviderException e) {
            Log.e(TAG, "getOrCreateSecretKey cannot find crypto provider");
            writeErrorToFile(e);
        } catch (UnrecoverableEntryException e) {
            Log.e(TAG, "getOrCreateSecretKey had an unrecoverable entry exception.");
            writeErrorToFile(e);
        }
        return secretKey;
    }

    private void writeIntegrityData(EncryptedData encryptedData, File file) {
        try (FileOutputStream fos = new FileOutputStream(file);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(encryptedData);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "writeIntegrityData could not find the integrity file");
            writeErrorToFile(e);
        } catch (IOException e) {
            Log.e(TAG, "writeIntegrityData had an IO exception");
            writeErrorToFile(e);
        }
    }

    private EncryptedData readIntegrityData(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            return (EncryptedData) ois.readObject();
        } catch (FileNotFoundException e) {
            Log.e(TAG, "readIntegrityData could not find integrity file");
            writeErrorToFile(e);
        } catch (IOException e) {
            Log.e(TAG, "readIntegrityData had an IO exception");
            writeErrorToFile(e);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "readIntegrityData could not find the class EncryptedData");
            writeErrorToFile(e);
        }
        return null;
    }

    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null && b == null) {
            return true;
        }

        if (a == null || b == null || a.length != b.length) {
            return false;
        }

        byte differenceAccumulator = 0;
        for (int i = 0; i < a.length; ++i) {
            differenceAccumulator |= a[i] ^ b[i];
        }
        return (differenceAccumulator == 0);
    }


    /* TODO(b/128526030): Remove this error reporting code upon resolving the bug. */
    private static final boolean DEBUG = true;
    private static final int LOGCAT_LINES = 1024;
    private static final String ERROR_SUFFIX = ".error";

    private void writeErrorToFile(Exception exception) {
        if (DEBUG) {
            try (PrintStream printStream = new PrintStream(
                    mIntegrityFile.getAbsolutePath() + ERROR_SUFFIX)) {
                printStream.println("DataIntegrityChecker Error");
                printStream.println("Exception: " + exception);
                printStream.println("Stacktrace:");
                exception.printStackTrace(printStream);
                printStream.println("Logcat:");
                List<String> logcatLines = getLogcat();
                for (String line : logcatLines) {
                    printStream.println(line);
                }
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Could not write error log.");
            }
        }
    }

    private static List<String> getLogcat() {
        List<String> lines = new ArrayList<>(LOGCAT_LINES);
        try {
            Process process = Runtime.getRuntime().exec(
                    String.format("logcat -t %d", LOGCAT_LINES));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            process.waitFor();
        } catch (InterruptedException | IOException e) {
            Log.e(TAG, "Exception while capturing logcat: " + e.toString());
        }
        return lines;
    }
}
