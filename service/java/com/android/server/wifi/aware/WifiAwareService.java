/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.aware;

import android.content.Context;
import android.os.Binder;
import android.os.HandlerThread;
import android.util.Log;

import com.android.server.wifi.HalDeviceManager;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiServiceBase;

/**
 * Service implementing Wi-Fi Aware functionality. Delegates actual interface
 * implementation to WifiAwareServiceImpl.
 */
public final class WifiAwareService implements WifiServiceBase {
    private static final String TAG = "WifiAwareService";
    final WifiAwareServiceImpl mImpl;

    public WifiAwareService(Context context) {
        mImpl = new WifiAwareServiceImpl(context);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Starting " + Context.WIFI_AWARE_SERVICE);
        WifiInjector wifiInjector = WifiInjector.getInstance();
        if (wifiInjector == null) {
            Log.e(TAG, "NULL injector!");
            return;
        }

        HalDeviceManager halDeviceManager = wifiInjector.getHalDeviceManager();

        WifiAwareStateManager wifiAwareStateManager = new WifiAwareStateManager();
        WifiAwareNativeCallback wifiAwareNativeCallback = new WifiAwareNativeCallback(
                wifiAwareStateManager);
        WifiAwareNativeManager wifiAwareNativeManager = new WifiAwareNativeManager(
                wifiAwareStateManager, halDeviceManager, wifiAwareNativeCallback);
        WifiAwareNativeApi wifiAwareNativeApi = new WifiAwareNativeApi(wifiAwareNativeManager);
        wifiAwareStateManager.setNative(wifiAwareNativeManager, wifiAwareNativeApi);
        WifiAwareShellCommand wifiAwareShellCommand = new WifiAwareShellCommand();
        wifiAwareShellCommand.register("native_api", wifiAwareNativeApi);
        wifiAwareShellCommand.register("native_cb", wifiAwareNativeCallback);
        wifiAwareShellCommand.register("state_mgr", wifiAwareStateManager);

        HandlerThread awareHandlerThread = wifiInjector.getWifiAwareHandlerThread();
        mImpl.start(awareHandlerThread, wifiAwareStateManager, wifiAwareShellCommand,
                wifiInjector.getWifiMetrics().getWifiAwareMetrics(),
                wifiInjector.getWifiPermissionsUtil(),
                wifiInjector.getWifiPermissionsWrapper(), wifiInjector.getFrameworkFacade(),
                wifiAwareNativeManager, wifiAwareNativeApi, wifiAwareNativeCallback);

        // TODO: This 2 step initialization is no longer necessary because of service ordering in
        // WifiStackService.
        mImpl.startLate();
    }

    @Override
    public Binder retrieveImpl() {
        return mImpl;
    }
}
