/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static android.net.NetworkInfo.DetailedState.CONNECTED;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.ITrafficStateCallback;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.wifi.util.ExternalCallbackTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls for traffic stats and notifies the clients
 */
public class WifiTrafficPoller {

    private static final boolean DBG = false;
    private static final String TAG = "WifiTrafficPoller";
    /**
     * Interval in milliseconds between polling for traffic
     * statistics
     */
    private static final int POLL_TRAFFIC_STATS_INTERVAL_MSECS = 1000;

    private static final int ENABLE_TRAFFIC_STATS_POLL  = 1;
    private static final int TRAFFIC_STATS_POLL         = 2;

    private boolean mEnableTrafficStatsPoll = false;
    private int mTrafficStatsPollToken = 0;
    private long mTxPkts;
    private long mRxPkts;
    /* Tracks last reported data activity */
    private int mDataActivity;

    private final ExternalCallbackTracker<ITrafficStateCallback> mRegisteredCallbacks;
    // err on the side of updating at boot since screen on broadcast may be missed
    // the first time
    private AtomicBoolean mScreenOn = new AtomicBoolean(true);
    private final TrafficHandler mTrafficHandler;
    private final WifiNative mWifiNative;
    private NetworkInfo mNetworkInfo;

    private boolean mVerboseLoggingEnabled = false;

    WifiTrafficPoller(@NonNull Context context, @NonNull Looper looper,
                      @NonNull WifiNative wifiNative) {
        mTrafficHandler = new TrafficHandler(looper);
        mWifiNative = wifiNative;
        mRegisteredCallbacks = new ExternalCallbackTracker<ITrafficStateCallback>(mTrafficHandler);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);

        context.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent == null) {
                            return;
                        }
                        if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(
                                intent.getAction())) {
                            mNetworkInfo = (NetworkInfo) intent.getParcelableExtra(
                                    WifiManager.EXTRA_NETWORK_INFO);
                        } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                            mScreenOn.set(false);
                        } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                            mScreenOn.set(true);
                        }
                        evaluateTrafficStatsPolling();
                    }
                }, filter);
    }

    /**
     * Add a new callback to the traffic poller.
     */
    public void addCallback(IBinder binder, ITrafficStateCallback callback,
                            int callbackIdentifier) {
        if (!mRegisteredCallbacks.add(binder, callback, callbackIdentifier)) {
            Log.e(TAG, "Failed to add callback");
            return;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding callback. Num callbacks: " + mRegisteredCallbacks.getNumCallbacks());
        }
    }

    /**
     * Remove an existing callback from the traffic poller.
     */
    public void removeCallback(int callbackIdentifier) {
        mRegisteredCallbacks.remove(callbackIdentifier);
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing callback. Num callbacks: "
                    + mRegisteredCallbacks.getNumCallbacks());
        }
    }

    void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
        } else {
            mVerboseLoggingEnabled = false;
        }
    }

    private class TrafficHandler extends Handler {
        public TrafficHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            String ifaceName;
            switch (msg.what) {
                case ENABLE_TRAFFIC_STATS_POLL:
                    mEnableTrafficStatsPoll = (msg.arg1 == 1);
                    if (mVerboseLoggingEnabled) {
                        Log.d(TAG, "ENABLE_TRAFFIC_STATS_POLL "
                                + mEnableTrafficStatsPoll + " Token "
                                + Integer.toString(mTrafficStatsPollToken));
                    }
                    mTrafficStatsPollToken++;
                    ifaceName = mWifiNative.getClientInterfaceName();
                    if (mEnableTrafficStatsPoll && !TextUtils.isEmpty(ifaceName)) {
                        notifyOnDataActivity(ifaceName);
                        sendMessageDelayed(Message.obtain(this, TRAFFIC_STATS_POLL,
                                mTrafficStatsPollToken, 0), POLL_TRAFFIC_STATS_INTERVAL_MSECS);
                    }
                    break;
                case TRAFFIC_STATS_POLL:
                    if (mVerboseLoggingEnabled) {
                        Log.d(TAG, "TRAFFIC_STATS_POLL "
                                + mEnableTrafficStatsPoll + " Token "
                                + Integer.toString(mTrafficStatsPollToken)
                                + " num clients " + mRegisteredCallbacks.getNumCallbacks());
                    }
                    if (msg.arg1 == mTrafficStatsPollToken) {
                        ifaceName = mWifiNative.getClientInterfaceName();
                        if (!TextUtils.isEmpty(ifaceName)) {
                            notifyOnDataActivity(ifaceName);
                            sendMessageDelayed(Message.obtain(this, TRAFFIC_STATS_POLL,
                                    mTrafficStatsPollToken, 0), POLL_TRAFFIC_STATS_INTERVAL_MSECS);
                        }
                    }
                    break;
            }
        }
    }

    private void evaluateTrafficStatsPolling() {
        Message msg;
        if (mNetworkInfo == null) return;
        if (mNetworkInfo.getDetailedState() == CONNECTED && mScreenOn.get()) {
            msg = Message.obtain(mTrafficHandler,
                    ENABLE_TRAFFIC_STATS_POLL, 1, 0);
        } else {
            msg = Message.obtain(mTrafficHandler,
                    ENABLE_TRAFFIC_STATS_POLL, 0, 0);
        }
        msg.sendToTarget();
    }

    private void notifyOnDataActivity(@NonNull String ifaceName) {
        long sent, received;
        long preTxPkts = mTxPkts, preRxPkts = mRxPkts;
        int dataActivity = WifiManager.TrafficStateCallback.DATA_ACTIVITY_NONE;

        // TODO (b/111691443): Use WifiInfo instead of making the native calls here.
        mTxPkts = mWifiNative.getTxPackets(ifaceName);
        mRxPkts = mWifiNative.getRxPackets(ifaceName);

        if (DBG) {
            Log.d(TAG, " packet count Tx="
                    + Long.toString(mTxPkts)
                    + " Rx="
                    + Long.toString(mRxPkts));
        }

        if (preTxPkts > 0 || preRxPkts > 0) {
            sent = mTxPkts - preTxPkts;
            received = mRxPkts - preRxPkts;
            if (sent > 0) {
                dataActivity |= WifiManager.TrafficStateCallback.DATA_ACTIVITY_OUT;
            }
            if (received > 0) {
                dataActivity |= WifiManager.TrafficStateCallback.DATA_ACTIVITY_IN;
            }

            if (dataActivity != mDataActivity && mScreenOn.get()) {
                mDataActivity = dataActivity;
                if (mVerboseLoggingEnabled) {
                    Log.e(TAG, "notifying of data activity "
                            + Integer.toString(mDataActivity));
                }
                for (ITrafficStateCallback callback : mRegisteredCallbacks.getCallbacks()) {
                    try {
                        callback.onStateChanged(mDataActivity);
                    } catch (RemoteException e) {
                        // Failed to reach, skip
                        // Client removal is handled in WifiService
                    }
                }
            }
        }
    }

    /**
     * Dump method for traffic poller.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mEnableTrafficStatsPoll " + mEnableTrafficStatsPoll);
        pw.println("mTrafficStatsPollToken " + mTrafficStatsPollToken);
        pw.println("mTxPkts " + mTxPkts);
        pw.println("mRxPkts " + mRxPkts);
        pw.println("mDataActivity " + mDataActivity);
        pw.println("mRegisteredCallbacks " + mRegisteredCallbacks.getNumCallbacks());
    }

}
