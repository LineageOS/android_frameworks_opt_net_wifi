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
import android.os.Handler;
import android.provider.DeviceConfig;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.TimeUnit;

/**
 * This class allows getting all configurable flags from DeviceConfig.
 */
public class DeviceConfigFacade {
    private Context mContext;

    private static final String NAMESPACE = "wifi";

    // Default values of fields
    @VisibleForTesting
    protected static final int DEFAULT_ABNORMAL_CONNECTION_DURATION_MS =
            (int) TimeUnit.SECONDS.toMillis(30);
    private boolean mDefaultMacRandomizationAggressiveModeSsidWhitelistEnabled;

    // Cached values of fields updated via updateDeviceConfigFlags()
    private boolean mIsAbnormalConnectionBugreportEnabled;
    private int mAbnormalConnectionDurationMs;
    private boolean mIsAggressiveMacRandomizationSsidWhitelistEnabled;

    public DeviceConfigFacade(Context context, Handler handler) {
        mContext = context;
        mDefaultMacRandomizationAggressiveModeSsidWhitelistEnabled = mContext.getResources()
                .getBoolean(R.bool.config_wifi_aggressive_randomization_ssid_whitelist_enabled);

        updateDeviceConfigFlags();
        DeviceConfig.addOnPropertiesChangedListener(
                NAMESPACE,
                command -> handler.post(command),
                properties -> {
                    updateDeviceConfigFlags();
                });
    }

    private void updateDeviceConfigFlags() {
        mIsAbnormalConnectionBugreportEnabled = DeviceConfig.getBoolean(NAMESPACE,
                "abnormal_connection_bugreport_enabled", false);
        mAbnormalConnectionDurationMs = DeviceConfig.getInt(NAMESPACE,
                "abnormal_connection_duration_ms",
                DEFAULT_ABNORMAL_CONNECTION_DURATION_MS);
        mIsAggressiveMacRandomizationSsidWhitelistEnabled = DeviceConfig.getBoolean(NAMESPACE,
                "aggressive_randomization_ssid_whitelist_enabled",
                mDefaultMacRandomizationAggressiveModeSsidWhitelistEnabled);
    }

    /**
     * Gets the feature flag for reporting abnormally long connections.
     */
    public boolean isAbnormalConnectionBugreportEnabled() {
        return mIsAbnormalConnectionBugreportEnabled;
    }

    /**
     * Gets the threshold for classifying abnormally long connections.
     */
    public int getAbnormalConnectionDurationMs() {
        return mAbnormalConnectionDurationMs;
    }

    /**
     * Gets the feature flag for aggressive MAC randomization per-SSID opt-in.
     */
    public boolean isAggressiveMacRandomizationSsidWhitelistEnabled() {
        return mIsAggressiveMacRandomizationSsidWhitelistEnabled;
    }
}
