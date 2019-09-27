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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.concurrent.GuardedBy;

/**
 * Receives boot complete broadcast (registered in AndroidManifest.xml).
 *
 * Ensures that if WifiStack is initialized after boot complete, we can check whether boot was
 * already completed, since if we start listening for the boot complete broadcast now it will be too
 * late and we will never get the broadcast.
 *
 * This BroadcastReceiver can be registered multiple times in different places, and it will ensure
 * that all registered callbacks are triggered exactly once.
 */
public class BootCompleteReceiver extends BroadcastReceiver {
    private static final String TAG = "WifiBootCompleteReceiver";

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static boolean sIsBootCompleted = false;
    @GuardedBy("sLock")
    private static final List<Runnable> sCallbacks = new ArrayList<>(1);

    public BootCompleteReceiver() {
        Log.d(TAG, "Constructed BootCompleteReceiver");
    }

    /**
     * Registers a callback that will be triggered when boot is completed. Note that if boot has
     * already been completed when the callback is registered, the callback will be triggered
     * immediately.
     *
     * No guarantees are made about which thread the callback is triggered on. Please do not
     * perform expensive operations in the callback, instead post to other threads.
     */
    public static void registerCallback(Runnable callback) {
        boolean runImmediately = false;

        synchronized (sLock) {
            if (sIsBootCompleted) {
                runImmediately = true;
            } else {
                sCallbacks.add(callback);
            }
        }

        // run callback outside of synchronized block
        if (runImmediately) {
            Log.d(TAG, "Triggering callback immediately since boot is already complete.");
            callback.run();
        } else {
            Log.d(TAG, "Enqueuing callback since boot is not yet complete.");
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Received boot complete broadcast");

        List<Runnable> callbacks = new ArrayList<>(1);

        synchronized (sLock) {
            sIsBootCompleted = true;
            callbacks.addAll(sCallbacks);
            sCallbacks.clear();
        }

        // run callbacks outside of synchronized block
        for (Runnable r : callbacks) {
            Log.d(TAG, "Triggered callback");
            r.run();
        }
    }
}
