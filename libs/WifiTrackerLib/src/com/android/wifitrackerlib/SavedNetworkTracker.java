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

import static com.android.wifitrackerlib.StandardWifiEntry.wifiConfigToStandardWifiEntryKey;

import static java.util.stream.Collectors.groupingBy;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Lifecycle;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wi-Fi tracker that provides all Wi-Fi related data to the Saved Networks page.
 *
 * These include
 * - List of WifiEntries for all saved networks, dynamically updated with ScanResults
 * - List of WifiEntries for all saved subscriptions, dynamically updated with ScanResults
 */
public class SavedNetworkTracker extends BaseWifiTracker {

    private static final String TAG = "SavedNetworkTracker";

    private final SavedNetworkTrackerCallback mListener;

    // Lock object for data returned by the public API
    private final Object mLock = new Object();

    @GuardedBy("mLock") private final List<WifiEntry> mSavedWifiEntries = new ArrayList<>();
    @GuardedBy("mLock") private final List<WifiEntry> mSubscriptionWifiEntries = new ArrayList<>();

    // Cache containing visible StandardWifiEntries. Must be accessed only by the worker thread.
    private final Map<String, StandardWifiEntry> mStandardWifiEntryCache = new HashMap<>();

    public SavedNetworkTracker(@NonNull Lifecycle lifecycle, @NonNull Context context,
            @NonNull WifiManager wifiManager,
            @NonNull ConnectivityManager connectivityManager,
            @NonNull NetworkScoreManager networkScoreManager,
            @NonNull Handler mainHandler,
            @NonNull Handler workerHandler,
            @NonNull Clock clock,
            long maxScanAgeMillis,
            long scanIntervalMillis,
            @Nullable SavedNetworkTracker.SavedNetworkTrackerCallback listener) {
        super(lifecycle, context, wifiManager, connectivityManager, networkScoreManager,
                mainHandler, workerHandler, clock, maxScanAgeMillis, scanIntervalMillis, listener,
                TAG);
        mListener = listener;
    }

    /**
     * Returns a list of WifiEntries for all saved networks. If a network is in range, the
     * corresponding WifiEntry will be updated with live ScanResult data.
     * @return
     */
    @AnyThread
    @NonNull
    public List<WifiEntry> getSavedWifiEntries() {
        synchronized (mLock) {
            return new ArrayList<>(mSavedWifiEntries);
        }
    }

    /**
     * Returns a list of WifiEntries for all saved subscriptions. If a subscription network is in
     * range, the corresponding WifiEntry will be updated with live ScanResult data.
     * @return
     */
    @AnyThread
    @NonNull
    public List<WifiEntry> getSubscriptionWifiEntries() {
        synchronized (mLock) {
            return new ArrayList<>(mSubscriptionWifiEntries);
        }
    }

    @WorkerThread
    @Override
    protected void handleOnStart() {
        updateStandardWifiEntryConfigs(mWifiManager.getConfiguredNetworks());
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
        updateSavedWifiEntries();
        updateSubscriptionWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleWifiStateChangedAction() {
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
        updateSavedWifiEntries();
        updateSubscriptionWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleScanResultsAvailableAction(@Nullable Intent intent) {
        //TODO(b/70983952): Add PasspointWifiEntry and update their scans here.
        checkNotNull(intent, "Intent cannot be null!");
        conditionallyUpdateScanResults(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED,
                true /* defaultValue */));
        updateSavedWifiEntries();
        updateSubscriptionWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleConfiguredNetworksChangedAction(@Nullable Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");

        final WifiConfiguration config =
                (WifiConfiguration) intent.getExtra(WifiManager.EXTRA_WIFI_CONFIGURATION);
        if (config != null) {
            updateStandardWifiEntryConfig(
                    config, (Integer) intent.getExtra(WifiManager.EXTRA_CHANGE_REASON));
        } else {
            updateStandardWifiEntryConfigs(mWifiManager.getConfiguredNetworks());
        }
        updateSavedWifiEntries();
    }

    private void updateSavedWifiEntries() {
        synchronized (mLock) {
            mSavedWifiEntries.clear();
            mSavedWifiEntries.addAll(mStandardWifiEntryCache.values());
            Collections.sort(mSavedWifiEntries);
            if (isVerboseLoggingEnabled()) {
                Log.v(TAG, "Updated SavedWifiEntries: "
                        + Arrays.toString(mSavedWifiEntries.toArray()));
            }
        }
        notifyOnSavedWifiEntriesChanged();
    }

    private void updateSubscriptionWifiEntries() {
        synchronized (mLock) {
            mSubscriptionWifiEntries.clear();
            // TODO(b/70983952): Implement PasspointWifiEntry and add here
            // mSubscriptionWifiEntries.addAll(mPasspointWifiEntryCache.values());
            Collections.sort(mSubscriptionWifiEntries);
            if (isVerboseLoggingEnabled()) {
                Log.v(TAG, "Updated SubscriptionWifiEntries: "
                        + Arrays.toString(mSubscriptionWifiEntries.toArray()));
            }
        }
        notifyOnSubscriptionWifiEntriesChanged();
    }

    private void updateStandardWifiEntryScans(@NonNull List<ScanResult> scanResults) {
        checkNotNull(scanResults, "Scan Result list should not be null!");

        // Group scans by StandardWifiEntry key
        final Map<String, List<ScanResult>> scanResultsByKey = scanResults.stream()
                .filter(scanResult -> !TextUtils.isEmpty(scanResult.SSID))
                .collect(groupingBy(StandardWifiEntry::scanResultToStandardWifiEntryKey));

        // Iterate through current entries and update each entry's scan results
        mStandardWifiEntryCache.entrySet().forEach(entry -> {
            final String key = entry.getKey();
            final StandardWifiEntry wifiEntry = entry.getValue();
            // Update scan results if available, or set to null.
            wifiEntry.updateScanResultInfo(scanResultsByKey.get(key));
        });
    }

    /**
     * Conditionally updates the WifiEntry scan results based on the current wifi state and
     * whether the last scan succeeded or not.
     */
    @WorkerThread
    private void conditionallyUpdateScanResults(boolean lastScanSucceeded) {
        if (mWifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED) {
            updateStandardWifiEntryScans(Collections.emptyList());
            return;
        }

        long scanAgeWindow = mMaxScanAgeMillis;
        if (lastScanSucceeded) {
            // Scan succeeded, cache new scans
            mScanResultUpdater.update(mWifiManager.getScanResults());
        } else {
            // Scan failed, increase scan age window to prevent WifiEntry list from
            // clearing prematurely.
            scanAgeWindow += mScanIntervalMillis;
        }
        updateStandardWifiEntryScans(mScanResultUpdater.getScanResults(scanAgeWindow));
    }

    /**
     * Updates or removes a WifiConfiguration for the corresponding StandardWifiEntry if it exists.
     *
     * If an entry does not exist and the changeReason is ADDED or UPDATED, then a new entry will
     * be created for the new config.
     *
     * @param config WifiConfiguration to update
     * @param changeReason WifiManager.CHANGE_REASON_ADDED, WifiManager.CHANGE_REASON_REMOVED, or
     *                     WifiManager.CHANGE_REASON_CONFIG_CHANGE
     */
    @WorkerThread
    private void updateStandardWifiEntryConfig(@NonNull WifiConfiguration config,
            int changeReason) {
        checkNotNull(config, "Config should not be null!");

        final String key = wifiConfigToStandardWifiEntryKey(config);
        final StandardWifiEntry entry = mStandardWifiEntryCache.get(key);

        if (entry != null) {
            if (changeReason == WifiManager.CHANGE_REASON_REMOVED) {
                entry.updateConfig(null);
                mStandardWifiEntryCache.remove(key);
            } else { // CHANGE_REASON_ADDED || CHANGE_REASON_CONFIG_CHANGE
                entry.updateConfig(config);
            }
        } else {
            if (changeReason != WifiManager.CHANGE_REASON_REMOVED) {
                mStandardWifiEntryCache.put(key,
                        new StandardWifiEntry(mMainHandler, config, mWifiManager));
            }
        }
    }

    private void updateStandardWifiEntryConfigs(@NonNull List<WifiConfiguration> configs) {
        checkNotNull(configs, "Config list should not be null!");

        // Group configs by StandardWifiEntry key
        final Map<String, WifiConfiguration> wifiConfigsByKey =
                configs.stream().collect(Collectors.toMap(
                        StandardWifiEntry::wifiConfigToStandardWifiEntryKey,
                        Function.identity()));

        // Iterate through current entries and update each entry's config
        mStandardWifiEntryCache.entrySet().removeIf((entry) -> {
            final StandardWifiEntry wifiEntry = entry.getValue();
            final String key = wifiEntry.getKey();
            // Update config if available, or set to null (unsaved)
            wifiEntry.updateConfig(wifiConfigsByKey.remove(key));
            // Entry is now unsaved, remove it.
            return !wifiEntry.isSaved();
        });

        // Create new entry for each unmatched config
        for (String key : wifiConfigsByKey.keySet()) {
            mStandardWifiEntryCache.put(key,
                    new StandardWifiEntry(mMainHandler, wifiConfigsByKey.get(key), mWifiManager));
        }
    }

    /**
     * Posts onSavedWifiEntriesChanged callback on the main thread.
     */
    @WorkerThread
    private void notifyOnSavedWifiEntriesChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onSavedWifiEntriesChanged);
        }
    }

    /**
     * Posts onSubscriptionWifiEntriesChanged callback on the main thread.
     */
    @WorkerThread
    private void notifyOnSubscriptionWifiEntriesChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onSubscriptionWifiEntriesChanged);
        }
    }

    /**
     * Listener for changes to the list of saved and subscription WifiEntries
     *
     * These callbacks must be run on the MainThread.
     */
    public interface SavedNetworkTrackerCallback extends BaseWifiTracker.BaseWifiTrackerCallback {
        /**
         * Called when there are changes to
         *      {@link #getSavedWifiEntries()}
         */
        @MainThread
        void onSavedWifiEntriesChanged();

        /**
         * Called when there are changes to
         *      {@link #getSubscriptionWifiEntries()}
         */
        @MainThread
        void onSubscriptionWifiEntriesChanged();
    }
}
