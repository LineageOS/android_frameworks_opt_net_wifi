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

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Utility methods useful for working with files.
 *
 * Note: @hide methods copied from android.os.FileUtils
 */
public final class FileUtils {
    private static final String TAG = "FileUtils";

    /**
     * Set owner and mode of of given path.
     *
     * @param mode to apply through {@code chmod}
     * @param uid to apply through {@code chown}, or -1 to leave unchanged
     * @param gid to apply through {@code chown}, or -1 to leave unchanged
     * @return 0 on success, otherwise errno.
     */
    public static int setPermissions(String path, int mode, int uid, int gid) {
        try {
            Os.chmod(path, mode);
        } catch (ErrnoException e) {
            Log.w(TAG, "Failed to chmod(" + path + "): " + e);
            return e.errno;
        }

        if (uid >= 0 || gid >= 0) {
            try {
                Os.chown(path, uid, gid);
            } catch (ErrnoException e) {
                Log.w(TAG, "Failed to chown(" + path + "): " + e);
                return e.errno;
            }
        }

        return 0;
    }

    /**
     * Writes the bytes given in {@code content} to the file whose absolute path
     * is {@code filename}.
     */
    public static void bytesToFile(String filename, byte[] content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(filename)) {
            fos.write(content);
        }
    }

    /**
     * Writes string to file. Basically same as "echo -n $string > $filename"
     *
     * @param filename
     * @param string
     * @throws IOException
     */
    public static void stringToFile(String filename, String string) throws IOException {
        bytesToFile(filename, string.getBytes(StandardCharsets.UTF_8));
    }

}
