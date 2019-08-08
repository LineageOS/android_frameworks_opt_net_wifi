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

import com.android.internal.util.Preconditions;

/**
 * A class to store data created by {@link DataIntegrityChecker}.
 */
public class EncryptedData {
    public static final int ENCRYPTED_DATA_LENGTH = 48;
    public static final int IV_LENGTH = 12;

    private final byte[] mEncryptedData;
    private final byte[] mIv;

    public EncryptedData(byte[] encryptedData, byte[] iv) {
        Preconditions.checkNotNull(encryptedData, iv);
        Preconditions.checkState(encryptedData.length == ENCRYPTED_DATA_LENGTH,
                "encryptedData.length=" + encryptedData.length);
        Preconditions.checkState(iv.length == IV_LENGTH, "iv.length=" + iv.length);
        mEncryptedData = encryptedData;
        mIv = iv;
    }

    public byte[] getEncryptedData() {
        return mEncryptedData;
    }

    public byte[] getIv() {
        return mIv;
    }
}
