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

import static com.android.wifitrackerlib.StandardWifiEntry.createStandardWifiEntryKey;

import static java.util.stream.Collectors.groupingBy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps track of the state of Wi-Fi and supplies {@link WifiEntry} for use in Wi-Fi picker lists.
 *
 * Clients should use WifiTracker2/WifiEntry for all information regarding Wi-Fi.
 *
 * This class runs on two threads:
 *
 * The main thread processes lifecycle events (onStart, onStop), as well as listener callbacks since
 * these directly manipulate the UI.
 *
 * The worker thread is responsible for driving the periodic scan requests and updating internal
 * data in reaction to system broadcasts. After a data update, the listener is notified on the main
 * thread.
 *
 * To keep synchronization simple, this means that the vast majority of work is done within the
 * worker thread. Synchronized blocks should then be used for updating/accessing only data that is
 * consumed by the client listener.
 */
public class WifiTracker2 implements LifecycleObserver {

    private static final String TAG = "WifiTracker2";

    public static boolean sVerboseLogging;

    private static boolean isVerboseLoggingEnabled() {
        return WifiTracker2.sVerboseLogging || Log.isLoggable(TAG, Log.VERBOSE);
    }

    private final Context mContext;
    private final WifiManager mWifiManager;
    private final ConnectivityManager mConnectivityManager;
    private final NetworkScoreManager mNetworkScoreManager;
    private final Handler mMainHandler;
    private final Handler mWorkerHandler;
    private final long mMaxScanAgeMillis;
    private final long mScanIntervalMillis;
    private final WifiTrackerCallback mListener;
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        /**
         * TODO (b/70983952): Add the rest of the broadcast handling.
         *      WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
         *      WifiManager.LINK_CONFIGURATION_CHANGED_ACTION);
         *      WifiManager.NETWORK_STATE_CHANGED_ACTION);
         *      WifiManager.RSSI_CHANGED_ACTION);
         */
        @Override
        @WorkerThread
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (isVerboseLoggingEnabled()) {
                Log.v(TAG, "Received broadcast: " + action);
            }

            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                if (mWifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED) {
                    mScanner.start();
                } else {
                    mScanner.stop();
                }
                notifyOnWifiStateChanged();
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                final boolean lastScanSucceeded =
                        intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true);
                if (lastScanSucceeded) updateScanResults();
                updateWifiEntries(lastScanSucceeded);
                notifyOnWifiEntriesChanged();
            }
        }
    };
    private final ScanResultUpdater mScanResultUpdater;
    private final Scanner mScanner;

    // Lock object for mWifiEntries
    private final Object mLock = new Object();

    @GuardedBy("mLock") private final List<WifiEntry> mWifiEntries = new ArrayList<>();
    private final Map<String, StandardWifiEntry> mStandardWifiEntryCache = new HashMap<>();

    /**
     * Constructor for WifiTracker2.
     *
     * @param lifecycle Lifecycle this is tied to for lifecycle callbacks.
     * @param context Context to retrieve WifiManager and resource strings.
     * @param wifiManager Provides all Wi-Fi info.
     * @param connectivityManager Provides network info.
     * @param networkScoreManager Provides network scores for network badging.
     * @param mainHandler Handler for processing listener callbacks.
     * @param workerHandler Handler for processing all broadcasts and running the Scanner.
     * @param clock Clock used for evaluating the age of scans
     * @param maxScanAgeMillis Max age for tracked WifiEntries.
     * @param scanIntervalMillis Interval between initiating scans.
     * @param listener WifiTrackerCallback listening on changes to WifiTracker2 data.
     */
    public WifiTracker2(@NonNull Lifecycle lifecycle, @NonNull Context context,
            @NonNull WifiManager wifiManager,
            @NonNull ConnectivityManager connectivityManager,
            @NonNull NetworkScoreManager networkScoreManager,
            @NonNull Handler mainHandler,
            @NonNull Handler workerHandler,
            @NonNull Clock clock,
            long maxScanAgeMillis,
            long scanIntervalMillis,
            @Nullable WifiTrackerCallback listener) {
        lifecycle.addObserver(this);
        mContext = context;
        mWifiManager = wifiManager;
        mConnectivityManager = connectivityManager;
        mNetworkScoreManager = networkScoreManager;
        mMainHandler = mainHandler;
        mWorkerHandler = workerHandler;
        mMaxScanAgeMillis = maxScanAgeMillis;
        mScanIntervalMillis = scanIntervalMillis;
        mListener = listener;

        mScanResultUpdater = new ScanResultUpdater(clock,
                maxScanAgeMillis + scanIntervalMillis);
        mScanner = new Scanner(workerHandler.getLooper());

        sVerboseLogging = mWifiManager.getVerboseLoggingLevel() > 0;
    }

    /**
     * Registers the broadcast receiver and network callbacks and starts the scanning mechanism.
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    @MainThread
    public void onStart() {
        // TODO (b/70983952): Register score cache and receivers for network callbacks.
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mContext.registerReceiver(mBroadcastReceiver, filter,
                /* broadcastPermission */ null, mWorkerHandler);

        // Populate data now so we don't have to wait for the next broadcast.
        mWorkerHandler.post(() -> {
            if (mWifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED) {
                mScanner.start();
            } else {
                mScanner.stop();
            }
            notifyOnWifiStateChanged();

            updateScanResults();
            updateWifiEntries(true);
            notifyOnWifiEntriesChanged();
        });
    }

    /**
     * Unregisters the broadcast receiver, network callbacks, and pauses the scanning mechanism.
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    @MainThread
    public void onStop() {
        // TODO (b/70983952): Unregister score cache and receivers for network callbacks.
        mScanner.stop();
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    /**
     * Returns the state of Wi-Fi as one of the following values.
     *
     * <li>{@link WifiManager#WIFI_STATE_DISABLED}</li>
     * <li>{@link WifiManager#WIFI_STATE_ENABLED}</li>
     * <li>{@link WifiManager#WIFI_STATE_DISABLING}</li>
     * <li>{@link WifiManager#WIFI_STATE_ENABLING}</li>
     * <li>{@link WifiManager#WIFI_STATE_UNKNOWN}</li>
     */
    @AnyThread
    public int getWifiState() {
        return mWifiManager.getWifiState();
    }

    /**
     * Returns the WifiEntry representing the current connection.
     */
    @AnyThread
    public @Nullable WifiEntry getConnectedWifiEntry() {
        // TODO (b/70983952): Fill in this method.
        return null;
    }

    /**
     * Returns a list of in-range WifiEntries.
     *
     * The currently connected entry is omitted and may be accessed through
     * {@link #getConnectedWifiEntry()}
     */
    @AnyThread
    public @NonNull List<WifiEntry> getWifiEntries() {
        synchronized (mLock) {
            return new ArrayList<>(mWifiEntries);
        }
    }

    /**
     * Returns a list of WifiEntries representing saved networks.
     */
    @AnyThread
    public @NonNull List<WifiEntry> getSavedWifiEntries() {
        // TODO (b/70983952): Fill in this method.
        return new ArrayList<>();
    }

    /**
     * Returns a list of WifiEntries representing network subscriptions.
     */
    @AnyThread
    public @NonNull List<WifiEntry> getSubscriptionEntries() {
        // TODO (b/70983952): Fill in this method.
        return new ArrayList<>();
    }

    @WorkerThread
    private void updateScanResults() {
        mScanResultUpdater.update(mWifiManager.getScanResults());
        if (isVerboseLoggingEnabled()) {
            Log.v(TAG, "Updated scans: " + Arrays.toString(
                    mScanResultUpdater.getScanResults(mMaxScanAgeMillis).toArray()));
        }
    }

    @WorkerThread
    private void updateWifiEntries(boolean lastScanSucceeded) {
        updateStandardWifiEntryCache(lastScanSucceeded);
        // TODO (b/70983952): Update Passpoint/Suggestion entries here.
        // updatePasspointWifiEntries();
        // updateCarrierWifiEntries();
        // updateSuggestionWifiEntries();
        synchronized (mLock) {
            mWifiEntries.clear();
            mWifiEntries.addAll(mStandardWifiEntryCache.values());
            // mWifiEntries.addAll(mPasspointWifiEntries);
            // mWifiEntries.addAll(mCarrierWifiEntries);
            // mWifiEntries.addAll(mSuggestionWifiEntries);
            Collections.sort(mWifiEntries);
            if (isVerboseLoggingEnabled()) {
                Log.v(TAG, "Updated WifiEntries: " + Arrays.toString(mWifiEntries.toArray()));
            }
        }
    }

    /**
     * Updates mStandardWifiEntryCache with fresh scans.
     */
    @WorkerThread
    private void updateStandardWifiEntryCache(boolean lastScanSucceeded) {
        // If the current scan failed, use results from the previous scan to prevent flicker.
        final List<ScanResult> scanResults = mScanResultUpdater.getScanResults(
                lastScanSucceeded ? mMaxScanAgeMillis : mMaxScanAgeMillis + mScanIntervalMillis);
        final Map<String, StandardWifiEntry> updatedStandardWifiEntries = new HashMap<>();

        // Group scans by StandardWifiEntry Key
        final Map<String, List<ScanResult>> scanResultsByKey = scanResults.stream()
                .filter(scanResult -> !TextUtils.isEmpty(scanResult.SSID))
                .collect(groupingBy(StandardWifiEntry::createStandardWifiEntryKey));

        // Create or get cached StandardWifiEntry by key
        for (String key : scanResultsByKey.keySet()) {
            StandardWifiEntry entry = mStandardWifiEntryCache.get(key);
            if (entry == null) {
                entry = new StandardWifiEntry(mMainHandler, scanResultsByKey.get(key));
            } else {
                entry.updateScanResultInfo(scanResultsByKey.get(key));
            }
            updatedStandardWifiEntries.put(key, entry);
        }
        mStandardWifiEntryCache.clear();
        mStandardWifiEntryCache.putAll(updatedStandardWifiEntries);
    }

    /**
     * Posts onWifiEntryChanged callback on the main thread.
     */
    @WorkerThread
    private void notifyOnWifiEntriesChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onWifiEntriesChanged);
        }
    }

    /**
     * Posts onWifiStateChanged callback on the main thread.
     */
    @WorkerThread
    private void notifyOnWifiStateChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onWifiStateChanged);
        }
    }

    /**
     * Scanner to handle starting scans every SCAN_INTERVAL_MILLIS
     */
    private class Scanner extends Handler {
        private static final int SCAN_RETRY_TIMES = 3;

        private int mRetry = 0;

        private Scanner(Looper looper) {
            super(looper);
        }

        @AnyThread
        private void start() {
            if (isVerboseLoggingEnabled()) {
                Log.v(TAG, "Scanner start");
            }
            post(this::postScan);
        }

        @AnyThread
        private void stop() {
            if (isVerboseLoggingEnabled()) {
                Log.v(TAG, "Scanner stop");
            }
            mRetry = 0;
            removeCallbacksAndMessages(null);
        }

        @WorkerThread
        private void postScan() {
            if (mWifiManager.startScan()) {
                mRetry = 0;
            } else if (++mRetry >= SCAN_RETRY_TIMES) {
                // TODO(b/70983952): See if toast is needed here
                if (isVerboseLoggingEnabled()) {
                    Log.v(TAG, "Scanner failed to start scan " + mRetry + " times!");
                }
                mRetry = 0;
                return;
            }
            postDelayed(this::postScan, mScanIntervalMillis);
        }
    }

    /**
     * Listener for changes to the Wi-Fi state or lists of WifiEntries.
     *
     * These callbacks must be run on the MainThread.
     *
     * TODO (b/70983952): Investigate need for callbacks for saved/subscription entry updates.
     */
    public interface WifiTrackerCallback {
        /**
         * Called when there are changes to
         *      {@link #getConnectedWifiEntry()}
         *      {@link #getWifiEntries()}
         */
        @MainThread
        void onWifiEntriesChanged();

        /**
         * Called when the state of Wi-Fi has changed. The new value can be read through
         * {@link #getWifiState()}
         */
        @MainThread
        void onWifiStateChanged();
    }
}
