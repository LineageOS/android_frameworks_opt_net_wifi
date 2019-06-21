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

package com.android.server.wifi;

import android.content.Context;

import com.google.android.gsf.Gservices;

import java.util.concurrent.TimeUnit;

/**
 * This class allows getting all configurable flags from Gservices.
 */
public class GservicesFacade {
    private static final int DEFAULT_ABNORMAL_CONNECTION_DURATION_MS =
            (int) TimeUnit.SECONDS.toMillis(30);
    private static final String G_PREFIX = "android.wifi.";
    private Context mContext;

    public GservicesFacade(Context context) {
        mContext = context;
    }

    /**
     * Gets the feature flag for reporting abnormally long connections.
     */
    public boolean isAbnormalConnectionBugreportEnabled() {
        return Gservices.getBoolean(mContext.getContentResolver(),
                G_PREFIX + "abnormal_connection_bugreport_enabled", false);
    }

    /**
     * Gets the threshold for classifying abnormally long connections.
     */
    public int getAbnormalConnectionDurationMs() {
        return Gservices.getInt(mContext.getContentResolver(),
                G_PREFIX + "abnormal_connection_duration_ms",
                DEFAULT_ABNORMAL_CONNECTION_DURATION_MS);
    }
}
