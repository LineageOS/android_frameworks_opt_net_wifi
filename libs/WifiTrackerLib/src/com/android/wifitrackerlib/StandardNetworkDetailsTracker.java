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

package com.android.wifitrackerlib;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.wifitrackerlib.StandardWifiEntry.scanResultToStandardWifiEntryKey;
import static com.android.wifitrackerlib.StandardWifiEntry.wifiConfigToStandardWifiEntryKey;

import static java.util.stream.Collectors.toList;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkScoreManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;

import java.time.Clock;
import java.util.Collections;
import java.util.Optional;

/**
 * Implementation of NetworkDetailsTracker that tracks a single StandardWifiEntry.
 */
class StandardNetworkDetailsTracker extends NetworkDetailsTracker {
    private static final String TAG = "StandardNetworkDetailsTracker";

    private final StandardWifiEntry mChosenEntry;

    StandardNetworkDetailsTracker(@NonNull Lifecycle lifecycle,
            @NonNull Context context,
            @NonNull WifiManager wifiManager,
            @NonNull ConnectivityManager connectivityManager,
            @NonNull NetworkScoreManager networkScoreManager,
            @NonNull Handler mainHandler,
            @NonNull Handler workerHandler,
            @NonNull Clock clock,
            long maxScanAgeMillis,
            long scanIntervalMillis,
            String key) {
        super(lifecycle, context, wifiManager, connectivityManager, networkScoreManager,
                mainHandler, workerHandler, clock, maxScanAgeMillis, scanIntervalMillis, TAG);
        mChosenEntry = new StandardWifiEntry(mMainHandler, key);
    }

    @AnyThread
    @Override
    @NonNull
    public WifiEntry getWifiEntry() {
        return mChosenEntry;
    }

    @Override
    protected void handleOnStart() {
        mScanResultUpdater.update(mWifiManager.getScanResults());
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
        conditionallyUpdateConfig();
    }

    @Override
    protected void handleWifiStateChangedAction() {
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
    }

    @Override
    protected void handleScanResultsAvailableAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        conditionallyUpdateScanResults(
                intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true));
    }

    @Override
    protected void handleConfiguredNetworksChangedAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        final WifiConfiguration updatedConfig =
                (WifiConfiguration) intent.getExtra(WifiManager.EXTRA_WIFI_CONFIGURATION);
        if (updatedConfig != null && TextUtils.equals(
                wifiConfigToStandardWifiEntryKey(updatedConfig), mChosenEntry.getKey())) {
            final int changeReason = intent.getIntExtra(WifiManager.EXTRA_CHANGE_REASON,
                    -1 /* defaultValue*/);
            if (changeReason == WifiManager.CHANGE_REASON_ADDED
                    || changeReason == WifiManager.CHANGE_REASON_CONFIG_CHANGE) {
                mChosenEntry.updateConfig(updatedConfig);
            } else if (changeReason == WifiManager.CHANGE_REASON_REMOVED) {
                mChosenEntry.updateConfig(null);
            }
        } else {
            conditionallyUpdateConfig();
        }
    }

    /**
     * Updates the tracked entry's scan results up to the max scan age (or more, if the last scan
     * was unsuccessful). If Wifi is disabled, the tracked entry's level will be cleared.
     */
    private void conditionallyUpdateScanResults(boolean lastScanSucceeded) {
        if (mWifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED) {
            mChosenEntry.updateScanResultInfo(Collections.emptyList());
            return;
        }

        long scanAgeWindow = mMaxScanAgeMillis;
        if (lastScanSucceeded) {
            // Scan succeeded, cache new scans
            mScanResultUpdater.update(mWifiManager.getScanResults().stream().filter(
                    scan -> TextUtils.equals(
                            scanResultToStandardWifiEntryKey(scan), mChosenEntry.getKey()))
                    .collect(toList()));
        } else {
            // Scan failed, increase scan age window to prevent WifiEntry list from
            // clearing prematurely.
            scanAgeWindow += mScanIntervalMillis;
        }
        mChosenEntry.updateScanResultInfo(mScanResultUpdater.getScanResults(scanAgeWindow));
    }

    /**
     * Updates the tracked entry's WifiConfiguration from getConfiguredNetworks(), or sets it to
     * null if it does not exist.
     */
    private void conditionallyUpdateConfig() {
        Optional<WifiConfiguration> optionalConfig = mWifiManager.getConfiguredNetworks()
                .stream().filter(config -> TextUtils.equals(
                        wifiConfigToStandardWifiEntryKey(config), mChosenEntry.getKey()))
                .findAny();
        mChosenEntry.updateConfig(optionalConfig.orElse(null));
    }
}
