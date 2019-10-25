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
import android.os.SystemProperties;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;

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

    private static final String ALIAS_SUFFIX = ".data-integrity-checker-key";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final int GCM_TAG_LENGTH = 128;
    private static final String KEY_STORE = "AndroidKeyStore";

    /**
     * When KEYSTORE_FAILURE_RETURN_VALUE is true, all cryptographic operation failures will not
     * enforce security and {@link #isOk(byte[], EncryptedData)} always return true.
     */
    private static final boolean KEYSTORE_FAILURE_RETURN_VALUE = true;

    private final String mDataFileName;

    /**
     * Construct a new integrity checker to update and check if/when a data file was altered
     * outside expected conditions.
     *
     * @param dataFileName The full path of the data file for which integrity check is performed.
     * @throws NullPointerException When data file is empty string.
     */
    public DataIntegrityChecker(@NonNull String dataFileName) {
        if (TextUtils.isEmpty(dataFileName)) {
            throw new NullPointerException("dataFileName must not be null or the empty "
                    + "string");
        }
        mDataFileName = dataFileName;
    }

    private String getKeyAlias() {
        return mDataFileName + ALIAS_SUFFIX;
    }

    /**
     * Computes a digest of a byte array, encrypt it, and store the result
     *
     * Call this method immediately before storing the byte array
     *
     * @param data The data desired to ensure integrity
     * @return Instance of {@link EncryptedData} containing the encrypted integrity data.
     */
    public EncryptedData compute(byte[] data) {
        if (data == null || data.length < 1) {
            reportException(new Exception("No data to compute"), "No data to compute.");
            return null;
        }
        byte[] digest = getDigest(data);
        if (digest == null || digest.length < 1) {
            reportException(new Exception("digest null in compute"),
                    "digest null in compute");
            return null;
        }
        EncryptedData integrityData = encrypt(digest, getKeyAlias());
        if (integrityData == null) {
            reportException(new Exception("integrityData null in compute"),
                    "integrityData null in compute");
        }
        return integrityData;
    }


    /**
     * Check the integrity of a given byte array
     *
     * Call this method immediately before trusting the byte array. This method will return false
     * when the integrity data calculated on the byte array does not match the encrypted integrity
     * data provided to compare or if there is an underlying issue with the cryptographic functions
     * or the key store.
     *
     * @param data The data to check if its been altered.
     * @param integrityData Encrypted integrity data to be used for comparison.
     * @return true if the integrity data computed on |data| matches the provided |integrityData|.
     */
    public boolean isOk(@NonNull byte[] data, @NonNull EncryptedData integrityData) {
        if (data == null || data.length < 1) {
            return KEYSTORE_FAILURE_RETURN_VALUE;
        }
        byte[] currentDigest = getDigest(data);
        if (currentDigest == null || currentDigest.length < 1) {
            reportException(new Exception("current digest null"), "current digest null");
            return KEYSTORE_FAILURE_RETURN_VALUE;
        }
        if (integrityData == null) {
            reportException(new Exception("integrityData null in isOk"),
                    "integrityData null in isOk");
            return KEYSTORE_FAILURE_RETURN_VALUE;
        }
        byte[] storedDigest = decrypt(integrityData, getKeyAlias());
        if (storedDigest == null) {
            return KEYSTORE_FAILURE_RETURN_VALUE;
        }
        return constantTimeEquals(storedDigest, currentDigest);
    }

    private byte[] getDigest(byte[] data) {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(data);
        } catch (NoSuchAlgorithmException e) {
            reportException(e, "getDigest could not find algorithm: " + DIGEST_ALGORITHM);
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
                encryptedData = new EncryptedData(cipher.doFinal(data), cipher.getIV());
            } else {
                reportException(new Exception("secretKeyReference is null."),
                        "secretKeyReference is null.");
            }
        } catch (NoSuchAlgorithmException e) {
            reportException(e, "encrypt could not find the algorithm: " + CIPHER_ALGORITHM);
        } catch (NoSuchPaddingException e) {
            reportException(e, "encrypt had a padding exception");
        } catch (InvalidKeyException e) {
            reportException(e, "encrypt received an invalid key");
        } catch (BadPaddingException e) {
            reportException(e, "encrypt had a padding problem");
        } catch (IllegalBlockSizeException e) {
            reportException(e, "encrypt had an illegal block size");
        }
        return encryptedData;
    }

    private byte[] decrypt(EncryptedData encryptedData, String keyAlias) {
        byte[] decryptedData = null;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.getIv());
            SecretKey secretKeyReference = getOrCreateSecretKey(keyAlias);
            if (secretKeyReference != null) {
                cipher.init(Cipher.DECRYPT_MODE, secretKeyReference, spec);
                decryptedData = cipher.doFinal(encryptedData.getEncryptedData());
            }
        } catch (NoSuchAlgorithmException e) {
            reportException(e, "decrypt could not find cipher algorithm " + CIPHER_ALGORITHM);
        } catch (NoSuchPaddingException e) {
            reportException(e, "decrypt could not find padding algorithm");
        } catch (IllegalBlockSizeException e) {
            reportException(e, "decrypt had a illegal block size");
        } catch (BadPaddingException e) {
            reportException(e, "decrypt had bad padding");
        } catch (InvalidKeyException e) {
            reportException(e, "decrypt had an invalid key");
        } catch (InvalidAlgorithmParameterException e) {
            reportException(e, "decrypt had an invalid algorithm parameter");
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
                    reportException(new Exception("keystore contains the alias and the secret key "
                            + "entry was null"),
                            "keystore contains the alias and the secret key entry was null");
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
            reportException(e, "getOrCreateSecretKey had a certificate exception.");
        } catch (InvalidAlgorithmParameterException e) {
            reportException(e, "getOrCreateSecretKey had an invalid algorithm parameter");
        } catch (IOException e) {
            reportException(e, "getOrCreateSecretKey had an IO exception.");
        } catch (KeyStoreException e) {
            reportException(e, "getOrCreateSecretKey cannot find the keystore: " + KEY_STORE);
        } catch (NoSuchAlgorithmException e) {
            reportException(e, "getOrCreateSecretKey cannot find algorithm");
        } catch (NoSuchProviderException e) {
            reportException(e, "getOrCreateSecretKey cannot find crypto provider");
        } catch (UnrecoverableEntryException e) {
            reportException(e, "getOrCreateSecretKey had an unrecoverable entry exception.");
        }
        return secretKey;
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
    private static final boolean REQUEST_BUG_REPORT = false;
    private void reportException(Exception exception, String error) {
        Log.wtf(TAG, "An irrecoverable key store error was encountered: " + error, exception);
        if (REQUEST_BUG_REPORT) {
            SystemProperties.set("dumpstate.options", "bugreportwifi");
            SystemProperties.set("ctl.start", "bugreport");
        }
    }
}
