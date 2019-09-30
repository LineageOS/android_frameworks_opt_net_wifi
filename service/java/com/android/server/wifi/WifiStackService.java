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
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.IWifiStackConnector;
import android.net.wifi.WifiApiServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.wifi.aware.WifiAwareService;
import com.android.server.wifi.p2p.WifiP2pService;
import com.android.server.wifi.rtt.RttService;
import com.android.server.wifi.scanner.WifiScanningService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Android service used to start the wifi stack when bound to via an intent.
 */
public class WifiStackService extends Service {
    private static final String TAG = WifiStackService.class.getSimpleName();
    // Ordered list of wifi services. The ordering determines the order in which the events
    // are delivered to the services.
    @GuardedBy("mApiServices")
    private final LinkedHashMap<String, WifiServiceBase> mApiServices = new LinkedHashMap<>();
    private static WifiStackConnector sConnector;

    private class WifiStackConnector extends IWifiStackConnector.Stub {
        private final Context mContext;

        WifiStackConnector(Context context) {
            mContext = context;
        }

        @Override
        public List<WifiApiServiceInfo> getWifiApiServiceInfos() {
            // Ensure this is being invoked from system_server only.
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.NETWORK_STACK, "WifiStackService");
            long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mApiServices) {
                    return mApiServices.entrySet().stream()
                            .map(entry -> {
                                WifiApiServiceInfo service = new WifiApiServiceInfo();
                                service.name = entry.getKey();
                                service.binder = entry.getValue().retrieveImpl();
                                return service;
                            })
                            .collect(Collectors.toList());
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
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
                    synchronized (mApiServices) {
                        for (WifiServiceBase service : mApiServices.values()) {
                            service.onSwitchUser(userId);
                        }
                    }
                    break;
                case Intent.ACTION_USER_STOPPED:
                    userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    synchronized (mApiServices) {
                        for (WifiServiceBase service : mApiServices.values()) {
                            service.onStopUser(userId);
                        }
                    }
                    break;
                case Intent.ACTION_USER_UNLOCKED:
                    userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    synchronized (mApiServices) {
                        for (WifiServiceBase service : mApiServices.values()) {
                            service.onUnlockUser(userId);
                        }
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

        // BootCompleteReceiver is registered in AndroidManifest.xml and here. The receiver
        // registered here is triggered earlier, while the receiver registered in the manifest
        // is more reliable since it is registered earlier, so we are guaranteed to get the
        // broadcast (if we register too late the broadcast may have already triggered and we
        // would have missed it). Register in both places and BootCompleteReceiver will ensure that
        // callbacks are called exactly once.
        Log.d(TAG, "Registering BootCompleteReceiver to listen for ACTION_LOCKED_BOOT_COMPLETED");
        context.registerReceiver(new BootCompleteReceiver(),
                new IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED));

        synchronized (mApiServices) {
            // Top level wifi feature flag.
            if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI)) {
                Log.w(TAG, "Wifi not supported on the device");
                return false;
            }

            // initialize static instance of WifiInjector
            new WifiInjector(this);
            // Ordering of wifi services.
            // wifiscanner service
            mApiServices.put(Context.WIFI_SCANNING_SERVICE, new WifiScanningService(this));
            // wifi service
            mApiServices.put(Context.WIFI_SERVICE, new WifiService(this));
            // wifi-p2p service
            if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
                mApiServices.put(Context.WIFI_P2P_SERVICE, new WifiP2pService(this));
            }
            // wifi-aware service
            if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
                mApiServices.put(Context.WIFI_AWARE_SERVICE, new WifiAwareService(this));
            }
            // wifirtt service
            if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
                mApiServices.put(Context.WIFI_RTT_RANGING_SERVICE, new RttService(this));
            }
        }

        Handler handler = new Handler(Looper.myLooper());
        // register callback to start Wifi services after boot completes
        BootCompleteReceiver.registerCallback(() -> handler.post(() -> {
            int currentUser = ActivityManager.getCurrentUser();
            synchronized (mApiServices) {
                for (WifiServiceBase service : mApiServices.values()) {
                    service.onStart();
                    // The current active user might have switched before the wifi services started
                    // up. So, send a onSwitchUser callback just after onStart callback is invoked.
                    if (currentUser != UserHandle.USER_SYSTEM) {
                        service.onSwitchUser(currentUser);
                    }
                }
            }
        }));

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
