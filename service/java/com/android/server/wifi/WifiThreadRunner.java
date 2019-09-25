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
import android.annotation.Nullable;
import android.os.Handler;
import android.util.Log;

import com.android.server.wifi.util.GeneralUtil.Mutable;

import java.util.function.Supplier;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Runs code on the main Wifi thread from another thread, in order to prevent race conditions.
 */
@ThreadSafe
public class WifiThreadRunner {
    private static final String TAG = "WifiThreadRunner";

    /** Max wait time for posting blocking runnables */
    private static final int RUN_WITH_SCISSORS_TIMEOUT_MILLIS = 4000;

    private final Handler mHandler;

    public WifiThreadRunner(Handler handler) {
        mHandler = handler;
    }

    /**
     * Synchronously runs code on the main Wifi thread and return a value.
     * <b>Blocks</b> the calling thread until the callable completes execution on the main Wifi
     * thread.
     *
     * BEWARE OF DEADLOCKS!!!
     *
     * @param <T> the return type
     * @param supplier the lambda that should be run on the main Wifi thread
     *                 e.g. wifiThreadRunner.call(() -> mWifiApConfigStore.getApConfiguration())
     *                 or wifiThreadRunner.call(mWifiApConfigStore::getApConfiguration)
     * @param valueToReturnOnTimeout If the lambda provided could not be run within the timeout (
     *                 {@link #RUN_WITH_SCISSORS_TIMEOUT_MILLIS}), will return this provided value
     *                 instead.
     * @return value retrieved from Wifi thread, or |valueToReturnOnTimeout| if the call failed.
     *         Beware of NullPointerExceptions when expecting a primitive (e.g. int, long) return
     *         type, it may still return null and throw a NullPointerException when auto-unboxing!
     *         Recommend capturing the return value in an Integer or Long instead and explicitly
     *         handling nulls.
     */
    @Nullable
    public <T> T call(@NonNull Supplier<T> supplier, T valueToReturnOnTimeout) {
        Mutable<T> result = new Mutable<>();
        boolean runWithScissorsSuccess = mHandler.runWithScissors(
                () -> result.value = supplier.get(),
                RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        if (runWithScissorsSuccess) {
            return result.value;
        } else {
            Log.e(TAG, "WifiThreadRunner.call() timed out!", new Throwable("Stack trace:"));
            return valueToReturnOnTimeout;
        }
    }

    /**
     * Runs a Runnable on the main Wifi thread and <b>blocks</b> the calling thread until the
     * Runnable completes execution on the main Wifi thread.
     *
     * BEWARE OF DEADLOCKS!!!
     *
     * @return true if the runnable executed successfully, false otherwise
     */
    public boolean run(@NonNull Runnable runnable) {
        boolean runWithScissorsSuccess =
                mHandler.runWithScissors(runnable, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        if (runWithScissorsSuccess) {
            return true;
        } else {
            Log.e(TAG, "WifiThreadRunner.run() timed out!", new Throwable("Stack trace:"));
            return false;
        }
    }

    /**
     * Asynchronously runs a Runnable on the main Wifi thread.
     *
     * @return true if the runnable was successfully posted <b>(not executed)</b> to the main Wifi
     * thread, false otherwise
     */
    public boolean post(@NonNull Runnable runnable) {
        return mHandler.post(runnable);
    }
}
