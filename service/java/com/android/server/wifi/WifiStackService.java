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

import static com.android.internal.notification.SystemNotificationChannels.NETWORK_ALERTS;
import static com.android.internal.notification.SystemNotificationChannels.NETWORK_AVAILABLE;
import static com.android.internal.notification.SystemNotificationChannels.NETWORK_STATUS;

import android.annotation.NonNull;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.IWifiStackConnector;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.Log;

import com.android.internal.R;
import com.android.server.wifi.aware.WifiAwareService;
import com.android.server.wifi.p2p.WifiP2pService;
import com.android.server.wifi.rtt.RttService;
import com.android.server.wifi.scanner.WifiScanningService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Android service used to start the wifi stack when bound to via an intent.
 */
public class WifiStackService extends Service {
    private static final String TAG = WifiStackService.class.getSimpleName();
    // Ordered list of wifi services. The ordering determines the order in which the events
    // are delivered to the services.
    private final LinkedHashSet<WifiServiceBase> mServices = new LinkedHashSet<>();
    private static WifiStackConnector sConnector;

    private static class WifiStackConnector extends IWifiStackConnector.Stub {
        private final Context mContext;

        WifiStackConnector(Context context) {
            mContext = context;
        }
    }

    private class WifiBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent + " broadcast");
            final String action = intent.getAction();
            if (action == null) {
                Log.w(TAG, "Received null action for broadcast.");
                return;
            }
            int userId;
            switch (action) {
                case Intent.ACTION_USER_SWITCHED:
                    userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    for (WifiServiceBase service : mServices) {
                        service.onSwitchUser(userId);
                    }
                    break;
                case Intent.ACTION_USER_STOPPED:
                    userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    for (WifiServiceBase service : mServices) {
                        service.onStopUser(userId);
                    }
                    break;
                case Intent.ACTION_USER_UNLOCKED:
                    userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    for (WifiServiceBase service : mServices) {
                        service.onUnlockUser(userId);
                    }
                    break;
                default:
                    Log.e(TAG, "Received unexpected action for broadcast.");
                    break;
            }
        }
    }

    // Create notification channels used by wifi.
    private void createNotificationChannels() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        List<NotificationChannel> channelsList = new ArrayList<>();
        final NotificationChannel networkStatusChannel = new NotificationChannel(
                NETWORK_STATUS,
                getString(R.string.notification_channel_network_status),
                NotificationManager.IMPORTANCE_LOW);
        channelsList.add(networkStatusChannel);

        final NotificationChannel networkAlertsChannel = new NotificationChannel(
                NETWORK_ALERTS,
                getString(R.string.notification_channel_network_alerts),
                NotificationManager.IMPORTANCE_HIGH);
        networkAlertsChannel.setBlockableSystem(true);
        channelsList.add(networkAlertsChannel);

        final NotificationChannel networkAvailable = new NotificationChannel(
                NETWORK_AVAILABLE,
                getString(R.string.notification_channel_network_available),
                NotificationManager.IMPORTANCE_LOW);
        networkAvailable.setBlockableSystem(true);
        channelsList.add(networkAvailable);

        nm.createNotificationChannels(channelsList);
    }


    private synchronized boolean initializeServices(Context context) {
        if (UserHandle.myUserId() != 0) {
            Log.w(TAG, "Wifi stack can only be bound from primary user");
            return false;
        }
        // Don't start wifi services if we're in crypt bounce state.
        if (StorageManager.inCryptKeeperBounce()) {
            Log.d(TAG, "Device still encrypted. Need to restart SystemServer."
                    + " Do not start wifi.");
            return false;
        }
        // Top level wifi feature flag.
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            Log.w(TAG, "Wifi not supported on the device");
            return false;
        }

        // Ordering of wifi services.
        // wifi service
        mServices.add(new WifiService(this));
        // wifiscanner service
        mServices.add(new WifiScanningService(this));
        // wifi-p2p service
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            mServices.add(new WifiP2pService(this));
        }
        // wifi-aware service
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            mServices.add(new WifiAwareService(this));
        }
        // wifirtt service
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
            mServices.add(new RttService(this));
        }
        // Start all the services.
        for (WifiServiceBase service : mServices) {
            service.onStart();
        }

        // Register broadcast receiver for system events.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_STOPPED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiver(new WifiBroadcastReceiver(), intentFilter);

        // Create notification channels.
        createNotificationChannels();

        return true;
    }

    /**
     * Create a binder connector for the system server to communicate with the network stack.
     *
     * <p>On platforms where the network stack runs in the system server process, this method may
     * be called directly instead of obtaining the connector by binding to the service.
     */
    private synchronized IBinder makeConnectorAndInitializeServices(Context context) {
        if (sConnector == null) {
            if (!initializeServices(context)) {
                Log.w(TAG, "Failed to initialize services");
                return null;
            }
            sConnector = new WifiStackConnector(context);
        }
        return sConnector;
    }

    @NonNull
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "WifiStack Service onBind");
        return makeConnectorAndInitializeServices(this);
    }
}
