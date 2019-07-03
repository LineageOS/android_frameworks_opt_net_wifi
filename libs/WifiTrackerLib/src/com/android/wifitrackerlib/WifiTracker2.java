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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkScoreManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Log;

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
import java.util.List;

/**
 * Keeps track of the state of Wi-Fi and supplies {@link WifiEntry} for use in Wi-Fi picker lists.
 *
 * Clients should use WifiTracker2/WifiEntry for all information regarding Wi-Fi.
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
         *      WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
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
                Log.i(TAG, "Received broadcast: " + action);
            }

            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                updateWifiState();
            }
        }
    };

    private final ScanResultUpdater mScanResultUpdater;

    // Lock object for mWifiEntries
    private final Object mLock = new Object();

    private int mWifiState;
    @GuardedBy("mLock")
    private final List<WifiEntry> mWifiEntries;

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

        mScanResultUpdater = new ScanResultUpdater(clock, maxScanAgeMillis * 2);

        mWifiState = WifiManager.WIFI_STATE_DISABLED;
        mWifiEntries = new ArrayList<>();

        sVerboseLogging = mWifiManager.getVerboseLoggingLevel() > 0;
    }

    /**
     * Registers the broadcast receiver and network callbacks and starts the scanning mechanism.
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    @MainThread
    public void onStart() {
        // TODO (b/70983952): Register score cache and receivers for network callbacks.
        // TODO (b/70983952): Resume scanner here.
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mContext.registerReceiver(mBroadcastReceiver, filter,
                /* broadcastPermission */ null, mWorkerHandler);

        // Populate data now so we don't have to wait for the next broadcast.
        mWorkerHandler.post(() -> {
            updateWifiState();
            // TODO (b/70983952): Update other info (eg ScanResults) driven by broadcasts here.
        });
    }

    /**
     * Unregisters the broadcast receiver, network callbacks, and pauses the scanning mechanism.
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    @MainThread
    public void onStop() {
        // TODO (b/70983952): Unregister score cache and receivers for network callbacks.
        // TODO (b/70983952): Pause scanner here.
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
    public int getWifiState() {
        return mWifiState;
    }

    /**
     * Returns the WifiEntry representing the current connection.
     */
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
    public @NonNull List<WifiEntry> getWifiEntries() {
        return mWifiEntries;
    }

    /**
     * Returns a list of WifiEntries representing saved networks.
     */
    public @NonNull List<WifiEntry> getSavedWifiEntries() {
        // TODO (b/70983952): Fill in this method.
        return new ArrayList<>();
    }

    /**
     * Returns a list of WifiEntries representing network subscriptions.
     */
    public @NonNull List<WifiEntry> getSubscriptionEntries() {
        // TODO (b/70983952): Fill in this method.
        return new ArrayList<>();
    }

    /**
     * Updates mWifiState and notifies the listener.
     */
    @WorkerThread
    private void updateWifiState() {
        mWifiState = mWifiManager.getWifiState();
        notifyOnWifiStateChanged();
    }

    /**
     * Posts onWifiEntryChanged callback on the main thread.
     */
    private void notifyOnWifiEntriesChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onWifiEntriesChanged);
        }
    }

    /**
     * Posts onWifiStateChanged callback on the main thread.
     */
    private void notifyOnWifiStateChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onWifiStateChanged);
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
