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

package com.android.server.wifi;

import android.annotation.IntDef;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.UserHandle;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base class for available WiFi operating modes.
 *
 * Currently supported modes include Client, ScanOnly and SoftAp.
 */
public interface ActiveModeManager {
    String TAG = "ActiveModeManager";

    /**
     * Method used to start the Manager for a given Wifi operational mode.
     */
    void start();

    /**
     * Method used to stop the Manager for a given Wifi operational mode.
     */
    void stop();


    /** Scan Modes */
    int SCAN_NONE = 0;
    int SCAN_WITHOUT_HIDDEN_NETWORKS = 1;
    int SCAN_WITH_HIDDEN_NETWORKS = 2;

    @IntDef({SCAN_NONE, SCAN_WITHOUT_HIDDEN_NETWORKS, SCAN_WITH_HIDDEN_NETWORKS})
    @Retention(RetentionPolicy.SOURCE)
    @interface ScanMode{}

    /**
     * Method to get the scan mode for a given Wifi operation mode.
     */
    @ScanMode int getScanMode();

    /**
     * Method to dump for logging state.
     */
    void dump(FileDescriptor fd, PrintWriter pw, String[] args);

    /**
     * Method that allows Mode Managers to update WifiScanner about the current state.
     *
     * @param context Context to use for the notification
     * @param available boolean indicating if scanning is available
     */
    default void sendScanAvailableBroadcast(Context context, boolean available) {
        Log.d(TAG, "sending scan available broadcast: " + available);
        final Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        if (available) {
            intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_ENABLED);
        } else {
            intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_DISABLED);
        }
        context.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }
}
