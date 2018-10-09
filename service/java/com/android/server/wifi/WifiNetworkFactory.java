/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.server.wifi.util.NativeUtil.addEnclosingQuotes;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.INetworkRequestUserSelectionCallback;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ExternalCallbackTracker;
import com.android.server.wifi.util.WifiPermissionsUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;


/**
 * Network factory to handle trusted wifi network requests.
 */
public class WifiNetworkFactory extends NetworkFactory {
    private static final String TAG = "WifiNetworkFactory";
    @VisibleForTesting
    private static final int SCORE_FILTER = 60;
    @VisibleForTesting
    public static final int PERIODIC_SCAN_INTERVAL_MS = 10 * 1000; // 10 seconds

    private final Context mContext;
    private final ActivityManager mActivityManager;
    private final AlarmManager mAlarmManager;
    private final Clock mClock;
    private final Handler mHandler;
    private final WifiInjector mWifiInjector;
    private final WifiConnectivityManager mWifiConnectivityManager;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final WifiScanner.ScanSettings mScanSettings;
    private final NetworkFactoryScanListener mScanListener;
    private final NetworkFactoryAlarmListener mPeriodicScanTimerListener;
    private final ExternalCallbackTracker<INetworkRequestMatchCallback> mRegisteredCallbacks;

    private int mGenericConnectionReqCount = 0;
    private NetworkRequest mActiveSpecificNetworkRequest;
    private WifiNetworkSpecifier mActiveSpecificNetworkRequestSpecifier;
    private WifiScanner mWifiScanner;
    // Verbose logging flag.
    private boolean mVerboseLoggingEnabled = false;
    private boolean mPeriodicScanTimerSet = false;

    // Scan listener for scan requests.
    private class NetworkFactoryScanListener implements WifiScanner.ScanListener {
        @Override
        public void onSuccess() {
            // Scan request succeeded, wait for results to report to external clients.
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Scan request succeeded");
            }
        }

        @Override
        public void onFailure(int reason, String description) {
            Log.e(TAG, "Scan failure received. reason: " + reason
                    + ", description: " + description);
            // TODO(b/113878056): Retry scan to workaround any transient scan failures.
            scheduleNextPeriodicScan();
        }

        @Override
        public void onResults(WifiScanner.ScanData[] scanDatas) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Scan results received");
            }
            // For single scans, the array size should always be 1.
            if (scanDatas.length != 1) {
                Log.wtf(TAG, "Found more than 1 batch of scan results, Ignoring...");
                return;
            }
            WifiScanner.ScanData scanData = scanDatas[0];
            ScanResult[] scanResults = scanData.getResults();
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Received " + scanResults.length + " scan results");
            }
            // TODO(b/113878056): Find network match in scan results

            // Didn't find a match, schedule the next scan.
            scheduleNextPeriodicScan();
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
            // Ignore for single scans.
        }

        @Override
        public void onPeriodChanged(int periodInMs) {
            // Ignore for single scans.
        }
    };

    private class NetworkFactoryAlarmListener implements AlarmManager.OnAlarmListener {
        @Override
        public void onAlarm() {
            // Trigger the next scan.
            startScan();
        }
    }

    // Callback result from settings UI.
    private class NetworkFactoryUserSelectionCallback extends
            INetworkRequestUserSelectionCallback.Stub {
        @Override
        public void select(WifiConfiguration wifiConfiguration) {
            mHandler.post(() -> {
                if (mActiveSpecificNetworkRequest == null) {
                    Log.e(TAG, "Stale callback select received");
                    return;
                }
                // TODO(b/113878056): Trigger network connection to |wificonfiguration|.
            });
        }

        @Override
        public void reject() {
            mHandler.post(() -> {
                if (mActiveSpecificNetworkRequest == null) {
                    Log.e(TAG, "Stale callback reject received");
                    return;
                }
                // TODO(b/113878056): Clear the active network request.
            });
        }
    }

    public WifiNetworkFactory(Looper looper, Context context, NetworkCapabilities nc,
                              ActivityManager activityManager, AlarmManager alarmManager,
                              Clock clock, WifiInjector wifiInjector,
                              WifiConnectivityManager connectivityManager,
                              WifiPermissionsUtil wifiPermissionsUtil) {
        super(looper, context, TAG, nc);
        mContext = context;
        mActivityManager = activityManager;
        mAlarmManager = alarmManager;
        mClock = clock;
        mHandler = new Handler(looper);
        mWifiInjector = wifiInjector;
        mWifiConnectivityManager = connectivityManager;
        mWifiPermissionsUtil = wifiPermissionsUtil;
        // Create the scan settings.
        mScanSettings = new WifiScanner.ScanSettings();
        mScanSettings.type = WifiScanner.TYPE_HIGH_ACCURACY;
        mScanSettings.band = WifiScanner.WIFI_BAND_BOTH_WITH_DFS;
        mScanSettings.reportEvents = WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
        mScanListener = new NetworkFactoryScanListener();
        mPeriodicScanTimerListener = new NetworkFactoryAlarmListener();
        mRegisteredCallbacks = new ExternalCallbackTracker<INetworkRequestMatchCallback>(mHandler);

        setScoreFilter(SCORE_FILTER);
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = (verbose > 0);
    }

    /**
     * Add a new callback for network request match handling.
     */
    public void addCallback(IBinder binder, INetworkRequestMatchCallback callback,
                            int callbackIdentifier) {
        if (!mRegisteredCallbacks.add(binder, callback, callbackIdentifier)) {
            Log.e(TAG, "Failed to add callback");
            return;
        }
        // Register our user selection callback immediately.
        try {
            callback.onUserSelectionCallbackRegistration(new NetworkFactoryUserSelectionCallback());
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to invoke user selection registration callback " + callback, e);
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding callback. Num callbacks: " + mRegisteredCallbacks.getNumCallbacks());
        }
    }

    /**
     * Remove an existing callback for network request match handling.
     */
    public void removeCallback(int callbackIdentifier) {
        mRegisteredCallbacks.remove(callbackIdentifier);
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing callback. Num callbacks: "
                    + mRegisteredCallbacks.getNumCallbacks());
        }
    }

    /**
     * Check whether to accept the new network connection request.
     *
     * All the validation of the incoming request is done in this method.
     */
    @Override
    public boolean acceptRequest(NetworkRequest networkRequest, int score) {
        NetworkSpecifier ns = networkRequest.networkCapabilities.getNetworkSpecifier();
        // Generic wifi request. Always accept.
        if (ns == null) {
            // Generic wifi request. Always accept.
        } else {
            // Invalid network specifier.
            if (!(ns instanceof WifiNetworkSpecifier)) {
                Log.e(TAG, "Invalid network specifier mentioned. Rejecting");
                return false;
            }

            WifiNetworkSpecifier wns = (WifiNetworkSpecifier) ns;
            if (!WifiConfigurationUtil.validateNetworkSpecifier(wns)) {
                Log.e(TAG, "Invalid network specifier."
                        + " Rejecting request from " + wns.requestorUid);
                return false;
            }
            // Only allow specific wifi network request from foreground app/service.
            if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(wns.requestorUid)
                    && !isRequestFromForegroundAppOrService(wns.requestorUid)) {
                Log.e(TAG, "Request not from foreground app or service."
                        + " Rejecting request from " + wns.requestorUid);
                return false;
            }
            // If there is a pending request, only proceed if the new request is from a foreground
            // app.
            if (mActiveSpecificNetworkRequest != null
                    && !mWifiPermissionsUtil.checkNetworkSettingsPermission(wns.requestorUid)
                    && !isRequestFromForegroundApp(wns.requestorUid)) {
                WifiNetworkSpecifier aWns =
                        (WifiNetworkSpecifier) mActiveSpecificNetworkRequest
                                .networkCapabilities
                                .getNetworkSpecifier();
                if (isRequestFromForegroundApp(aWns.requestorUid)) {
                    Log.e(TAG, "Already processing active request from a foreground app "
                            + aWns.requestorUid + ". Rejecting request from " + wns.requestorUid);
                    return false;
                }
            }
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Accepted network request with specifier from fg "
                        + (isRequestFromForegroundApp(wns.requestorUid) ? "app" : "service"));
            }
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Accepted network request " + networkRequest);
        }
        return true;
    }

    /**
     * Handle new network connection requests.
     *
     * The assumption here is that {@link #acceptRequest(NetworkRequest, int)} has already sanitized
     * the incoming request.
     */
    @Override
    protected void needNetworkFor(NetworkRequest networkRequest, int score) {
        NetworkSpecifier ns = networkRequest.networkCapabilities.getNetworkSpecifier();
        if (ns == null) {
            // Generic wifi request. Turn on auto-join if necessary.
            if (++mGenericConnectionReqCount == 1) {
                mWifiConnectivityManager.setTrustedConnectionAllowed(true);
            }
        } else {
            // Invalid network specifier.
            if (!(ns instanceof WifiNetworkSpecifier)) {
                Log.e(TAG, "Invalid network specifier mentioned. Rejecting");
                return;
            }
            retrieveWifiScanner();

            // Store the active network request.
            mActiveSpecificNetworkRequest = new NetworkRequest(networkRequest);
            WifiNetworkSpecifier wns = (WifiNetworkSpecifier) ns;
            mActiveSpecificNetworkRequestSpecifier = new WifiNetworkSpecifier(
                    wns.ssidPatternMatcher, wns.bssidPatternMatcher, wns.wifiConfiguration,
                    wns.requestorUid);

            // Trigger periodic scans for finding a network in the request.
            startPeriodicScans();
            // Disable Auto-join so that NetworkFactory can take control of the network selection.
            // TODO(b/117979585): Defer turning off auto-join.
            mWifiConnectivityManager.enable(false);
        }
    }

    @Override
    protected void releaseNetworkFor(NetworkRequest networkRequest) {
        NetworkSpecifier ns = networkRequest.networkCapabilities.getNetworkSpecifier();
        if (ns == null) {
            // Generic wifi request. Turn off auto-join if necessary.
            if (mGenericConnectionReqCount == 0) {
                Log.e(TAG, "No valid network request to release");
                return;
            }
            if (--mGenericConnectionReqCount == 0) {
                mWifiConnectivityManager.setTrustedConnectionAllowed(false);
            }
        } else {
            // Invalid network specifier.
            if (!(ns instanceof WifiNetworkSpecifier)) {
                Log.e(TAG, "Invalid network specifier mentioned. Ingoring");
                return;
            }
            if (!mActiveSpecificNetworkRequest.equals(networkRequest)) {
                Log.e(TAG, "Network specifier does not match the active request. Ignoring");
                return;
            }
            // Release the active network request.
            mActiveSpecificNetworkRequest = null;
            mActiveSpecificNetworkRequestSpecifier = null;
            // Cancel the periodic scans.
            cancelPeriodicScans();
            // Re-enable Auto-join (if there is a generic request pending).
            if (mGenericConnectionReqCount > 0) {
                mWifiConnectivityManager.enable(true);
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println(TAG + ": mGenericConnectionReqCount " + mGenericConnectionReqCount);
        pw.println(TAG + ": mActiveSpecificNetworkRequest " + mActiveSpecificNetworkRequest);
    }

    /**
     * Check if there is at-least one connection request.
     */
    public boolean hasConnectionRequests() {
        return mGenericConnectionReqCount > 0 || mActiveSpecificNetworkRequest != null;
    }

    /**
     * Check if the request comes from foreground app/service.
     */
    private boolean isRequestFromForegroundAppOrService(int requestorUid) {
        try {
            String requestorPackageName = mContext.getPackageManager().getNameForUid(requestorUid);
            return mActivityManager.getPackageImportance(requestorPackageName)
                    <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to check the app state", e);
            return false;
        }
    }

    /**
     * Check if the request comes from foreground app.
     */
    private boolean isRequestFromForegroundApp(int requestorUid) {
        try {
            String requestorPackageName = mContext.getPackageManager().getNameForUid(requestorUid);
            return mActivityManager.getPackageImportance(requestorPackageName)
                    <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to check the app state", e);
            return false;
        }
    }

    /**
     * Helper method to populate WifiScanner handle. This is done lazily because
     * WifiScanningService is started after WifiService.
     */
    private void retrieveWifiScanner() {
        if (mWifiScanner != null) return;
        mWifiScanner = mWifiInjector.getWifiScanner();
        checkNotNull(mWifiScanner);
    }

    private void startPeriodicScans() {
        if (mActiveSpecificNetworkRequestSpecifier == null) {
            Log.e(TAG, "Periodic scan triggered when there is no active network request. "
                    + "Ignoring...");
            return;
        }
        WifiNetworkSpecifier wns = mActiveSpecificNetworkRequestSpecifier;
        WifiConfiguration wifiConfiguration = wns.wifiConfiguration;
        if (wifiConfiguration.hiddenSSID) {
            mScanSettings.hiddenNetworks = new WifiScanner.ScanSettings.HiddenNetwork[1];
            // Can't search for SSID pattern in hidden networks.
            mScanSettings.hiddenNetworks[0] =
                    new WifiScanner.ScanSettings.HiddenNetwork(
                            addEnclosingQuotes(wns.ssidPatternMatcher.getPath()));
        }
        startScan();
    }

    private void cancelPeriodicScans() {
        if (mPeriodicScanTimerSet) {
            mAlarmManager.cancel(mPeriodicScanTimerListener);
            mPeriodicScanTimerSet = false;
        }
        // Clear the hidden networks field after each request.
        mScanSettings.hiddenNetworks = null;
    }

    private void scheduleNextPeriodicScan() {
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                mClock.getElapsedSinceBootMillis() + PERIODIC_SCAN_INTERVAL_MS,
                TAG, mPeriodicScanTimerListener, mHandler);
        mPeriodicScanTimerSet = true;
    }

    private void startScan() {
        if (mActiveSpecificNetworkRequestSpecifier == null) {
            Log.e(TAG, "Scan triggered when there is no active network request. Ignoring...");
            return;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Starting the next scan for " + mActiveSpecificNetworkRequestSpecifier);
        }
        // Create a worksource using the caller's UID.
        WorkSource workSource = new WorkSource(mActiveSpecificNetworkRequestSpecifier.requestorUid);
        mWifiScanner.startScan(mScanSettings, mScanListener, workSource);
    }
}

