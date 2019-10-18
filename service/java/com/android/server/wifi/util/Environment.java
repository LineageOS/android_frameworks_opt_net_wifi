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

}
