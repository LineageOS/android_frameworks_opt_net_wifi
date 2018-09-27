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

import android.app.ActivityManager;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Looper;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Network factory to handle trusted wifi network requests.
 */
public class WifiNetworkFactory extends NetworkFactory {
    private static final String TAG = "WifiNetworkFactory";
    private static final int SCORE_FILTER = 60;
    private final WifiConnectivityManager mWifiConnectivityManager;
    private final Context mContext;
    private final ActivityManager mActivityManager;

    private int mGenericConnectionReqCount = 0;
    NetworkRequest mActiveSpecificNetworkRequest;
    // Verbose logging flag.
    private boolean mVerboseLoggingEnabled = false;

    public WifiNetworkFactory(Looper looper, Context context, NetworkCapabilities nc,
                              ActivityManager activityManager,
                              WifiConnectivityManager connectivityManager) {
        super(looper, context, TAG, nc);
        mContext = context;
        mActivityManager = activityManager;
        mWifiConnectivityManager = connectivityManager;

        setScoreFilter(SCORE_FILTER);
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = (verbose > 0);
    }

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
            if (!isRequestFromForegroundAppOrService(wns.requestorUid)) {
                Log.e(TAG, "Request not from foreground app or service."
                        + " Rejecting request from " + wns.requestorUid);
                return false;
            }
            // If there is a pending request, only proceed if the new request is from a foreground
            // app.
            if (mActiveSpecificNetworkRequest != null
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
            // Store the active network request.
            mActiveSpecificNetworkRequest = new NetworkRequest(networkRequest);
            // TODO (b/113878056): Complete handling.
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
            // TODO (b/113878056): Complete handling.
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
}

