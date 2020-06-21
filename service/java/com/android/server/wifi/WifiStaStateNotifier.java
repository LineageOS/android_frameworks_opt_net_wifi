/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.net.wifi.IStaStateCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.wifi.util.ExternalCallbackTracker;


public class WifiStaStateNotifier {
    private final ExternalCallbackTracker<IStaStateCallback> mRegisteredCallbacks;
    private static WifiInjector mWifiInjector;
    private static final String TAG = "WifiStaStateNotifier";
    private static final boolean DEBUG = false;

    WifiStaStateNotifier(@NonNull Looper looper, WifiInjector wifiInjector) {
        mRegisteredCallbacks = new ExternalCallbackTracker<IStaStateCallback>(new Handler(looper));
        mWifiInjector = wifiInjector;
    }

    public void addCallback(IBinder binder, IStaStateCallback callback,
                            int callbackIdentifier) {
        if (DEBUG) Log.d(TAG, "addCallback");
        if (mRegisteredCallbacks.getNumCallbacks() > 0) {
            if (DEBUG) Log.e(TAG, "Failed to add callback, only support single request!");
            return;
        }
        if (!mRegisteredCallbacks.add(binder, callback, callbackIdentifier)) {
            if (DEBUG) Log.e(TAG, "Failed to add callback");
            return;
        }
        mWifiInjector.getActiveModeWarden().registerStaEventCallback();
    }

    public void removeCallback(int callbackIdentifier) {
        if (DEBUG) Log.d(TAG, "removeCallback");
        mRegisteredCallbacks.remove(callbackIdentifier);
        mWifiInjector.getActiveModeWarden().unregisterStaEventCallback();
    }

    public void onStaToBeOff() {
        if (DEBUG) Log.d(TAG, "onStaToBeOff");
        for (IStaStateCallback callback : mRegisteredCallbacks.getCallbacks()) {
            try {
                if (DEBUG) Log.d(TAG, "callback onStaToBeOff");
                callback.onStaToBeOff();
            } catch (RemoteException e) {
                // do nothing
            }
        }
    }
}
