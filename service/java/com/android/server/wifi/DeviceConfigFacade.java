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

import com.android.internal.annotations.VisibleForTesting;
import com.android.wifi.R;

import java.util.concurrent.TimeUnit;

/**
 * This class allows getting all configurable flags from DeviceConfig.
 */
public class DeviceConfigFacade {
    private Context mContext;
    private final WifiMetrics mWifiMetrics;

    private static final String NAMESPACE = "wifi";

    // Default values of fields
    @VisibleForTesting
    protected static final int DEFAULT_ABNORMAL_CONNECTION_DURATION_MS =
            (int) TimeUnit.SECONDS.toMillis(30);
    // Default duration for evaluating Wifi condition to trigger a data stall
    // measured in milliseconds
    public static final int DEFAULT_DATA_STALL_DURATION_MS = 1500;
    // Default threshold of Tx throughput below which to trigger a data stall measured in Kbps
    public static final int DEFAULT_DATA_STALL_TX_TPUT_THR_KBPS = 2000;
    // Default threshold of Rx throughput below which to trigger a data stall measured in Kbps
    public static final int DEFAULT_DATA_STALL_RX_TPUT_THR_KBPS = 2000;
    // Default threshold of Tx packet error rate above which to trigger a data stall in percentage
    public static final int DEFAULT_DATA_STALL_TX_PER_THR = 90;
    // Default threshold of CCA level above which to trigger a data stall in percentage
    public static final int DEFAULT_DATA_STALL_CCA_LEVEL_THR = 100;
    private boolean mDefaultMacRandomizationAggressiveModeSsidWhitelistEnabled;

    // Cached values of fields updated via updateDeviceConfigFlags()
    private boolean mIsAbnormalConnectionBugreportEnabled;
    private int mAbnormalConnectionDurationMs;
    private boolean mIsAggressiveMacRandomizationSsidWhitelistEnabled;
    private int mDataStallDurationMs;
    private int mDataStallTxTputThrKbps;
    private int mDataStallRxTputThrKbps;
    private int mDataStallTxPerThr;
    private int mDataStallCcaLevelThr;

    public DeviceConfigFacade(Context context, Handler handler, WifiMetrics wifiMetrics) {
        mContext = context;
        mWifiMetrics = wifiMetrics;
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

        mDataStallDurationMs = DeviceConfig.getInt(NAMESPACE,
                "data_stall_duration_ms", DEFAULT_DATA_STALL_DURATION_MS);
        mDataStallTxTputThrKbps = DeviceConfig.getInt(NAMESPACE,
                "data_stall_tx_tput_thr_kbps", DEFAULT_DATA_STALL_TX_TPUT_THR_KBPS);
        mDataStallRxTputThrKbps = DeviceConfig.getInt(NAMESPACE,
                "data_stall_rx_tput_thr_kbps", DEFAULT_DATA_STALL_RX_TPUT_THR_KBPS);
        mDataStallTxPerThr = DeviceConfig.getInt(NAMESPACE,
                "data_stall_tx_per_thr", DEFAULT_DATA_STALL_TX_PER_THR);
        mDataStallCcaLevelThr = DeviceConfig.getInt(NAMESPACE,
                "data_stall_cca_level_thr", DEFAULT_DATA_STALL_CCA_LEVEL_THR);
        mWifiMetrics.setDataStallDurationMs(mDataStallDurationMs);
        mWifiMetrics.setDataStallTxTputThrKbps(mDataStallTxTputThrKbps);
        mWifiMetrics.setDataStallRxTputThrKbps(mDataStallRxTputThrKbps);
        mWifiMetrics.setDataStallTxPerThr(mDataStallTxPerThr);
        mWifiMetrics.setDataStallCcaLevelThr(mDataStallCcaLevelThr);
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

    /**
     * Gets the duration of evaluating Wifi condition to trigger a data stall.
     */
    public int getDataStallDurationMs() {
        return mDataStallDurationMs;
    }

    /**
     * Gets the threshold of Tx throughput below which to trigger a data stall.
     */
    public int getDataStallTxTputThrKbps() {
        return mDataStallTxTputThrKbps;
    }

    /**
     * Gets the threshold of Rx throughput below which to trigger a data stall.
     */
    public int getDataStallRxTputThrKbps() {
        return mDataStallRxTputThrKbps;
    }

    /**
     * Gets the threshold of Tx packet error rate above which to trigger a data stall.
     */
    public int getDataStallTxPerThr() {
        return mDataStallTxPerThr;
    }

    /**
     * Gets the threshold of CCA level above which to trigger a data stall.
     */
    public int getDataStallCcaLevelThr() {
        return mDataStallCcaLevelThr;
    }
}
