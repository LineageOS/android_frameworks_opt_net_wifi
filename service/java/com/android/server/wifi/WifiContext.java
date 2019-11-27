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

import android.annotation.NonNull;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.util.Log;

/**
 * Wrapper for context to override getResources method. Resources for wifi mainline jar needs to be
 * fetched from the resources APK.
 */
public class WifiContext extends ContextWrapper {
    private static final String TAG = "WifiContext";
    private static final String WIFI_OVERLAY_APK_PKG_NAME = "com.android.wifi.resources";

    // Cached resources from the resources APK.
    private Resources mWifiResourcesFromApk;

    public WifiContext(@NonNull Context contextBase) {
        super(contextBase);
    }

    /**
     * Retrieve resources held in the wifi resources APK.
     */
    @Override
    public Resources getResources() {
        if (mWifiResourcesFromApk == null) {
            try {
                Context overlayAppContext =
                        createPackageContext(WIFI_OVERLAY_APK_PKG_NAME, 0);
                mWifiResourcesFromApk = overlayAppContext.getResources();
            } catch (PackageManager.NameNotFoundException e) {
                Log.wtf(TAG, "Failed to load resources", e);
            }
        }
        return mWifiResourcesFromApk;
    }
}
