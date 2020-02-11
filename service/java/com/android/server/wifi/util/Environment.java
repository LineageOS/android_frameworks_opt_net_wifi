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

import java.io.File;

/**
 * Provides access to environment variables.
 *
 * Note: @hide methods copied from android.os.Environment
 */
public class Environment {
    /**
     * Directory to store the wifi config store / shared preference files under.
     */
    private static final String WIFI_STORE_DIRECTORY_NAME = "wifi";

    /**
     * Get data/misc directory
     */
    public static File getDataMiscDirectory() {
        return new File(android.os.Environment.getDataDirectory(), "misc");
    }

    /**
     * Get data/misc_ce/<userId> directory
     */
    public static File getDataMiscCeDirectory(int userId) {
        return buildPath(android.os.Environment.getDataDirectory(), "misc_ce",
                String.valueOf(userId));
    }

    /**
     * Append path segments to given base path, returning result.
     */
    public static File buildPath(File base, String... segments) {
        File cur = base;
        for (String segment : segments) {
            if (cur == null) {
                cur = new File(segment);
            } else {
                cur = new File(cur, segment);
            }
        }
        return cur;
    }


    /**
     * TODO (b/148660313): Move to apex folder.
     */
    public static File getWifiSharedFolder() {
        return new File(Environment.getDataMiscDirectory(), WIFI_STORE_DIRECTORY_NAME);
    }

    /**
     * TODO (b/148660313): Move to apex folder.
     */
    public static File getWifiUserFolder(int userId) {
        return new File(Environment.getDataMiscCeDirectory(userId), WIFI_STORE_DIRECTORY_NAME);
    }
}
