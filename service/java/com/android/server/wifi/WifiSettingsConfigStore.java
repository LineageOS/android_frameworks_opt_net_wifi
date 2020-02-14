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
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.content.Context;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.wifi.util.WifiConfigStoreEncryptionUtil;
import com.android.server.wifi.util.XmlUtil;


import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;

/**
 * Store data for storing wifi settings. These are key (string) / value pairs that are stored in
 * WifiConfigStore.xml file in a separate section.
 */
public class WifiSettingsConfigStore {
    private static final String TAG = "WifiSettingsConfigStore";

    /******** Wifi shared pref keys ***************/
    @StringDef({
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WifiSettingsKey {}

    /******** Wifi shared pref keys ***************/

    private final Context mContext;
    private final Handler mHandler;
    private final WifiConfigManager mWifiConfigManager;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<String, Object> mSettings = new HashMap<>();
    @GuardedBy("mLock")
    private final Map<String, Map<OnSettingsChangedListener, Handler>> mListeners =
            new HashMap<>();

    private boolean mHasNewDataToSerialize = false;

    /**
     * Interface for a settings change listener.
     */
    public interface OnSettingsChangedListener {
        /**
         * Invoked when a particular key settings changes.
         *
         * @param key Key that was changed.
         * @param newValue New value that was assigned to the key.
         */
        void onSettingsChanged(@NonNull @WifiSettingsKey String key, @NonNull Object newValue);
    }

    public WifiSettingsConfigStore(@NonNull Context context, @NonNull Handler handler,
            @NonNull WifiConfigManager wifiConfigManager,
            @NonNull WifiConfigStore wifiConfigStore) {
        mContext = context;
        mHandler = handler;
        mWifiConfigManager = wifiConfigManager;

        // Register our data store.
        wifiConfigStore.registerStoreData(new StoreData());
    }

    private void invokeAllListeners() {
        synchronized (mLock) {
            for (String key : mSettings.keySet()) {
                invokeListeners(key);
            }
        }
    }

    private void invokeListeners(@NonNull @WifiSettingsKey String key) {
        synchronized (mLock) {
            Object newValue = mSettings.get(key);
            Map<OnSettingsChangedListener, Handler> listeners = mListeners.get(key);
            if (listeners == null || listeners.isEmpty()) return;
            for (Map.Entry<OnSettingsChangedListener, Handler> listener
                    : listeners.entrySet()) {
                // Trigger the callback in the appropriate handler.
                listener.getValue().post(() -> listener.getKey().onSettingsChanged(key, newValue));
            }
        }
    }

    /**
     * Trigger config store writes and invoke listeners in the main wifi service looper's handler.
     */
    private void triggerSaveToStoreAndInvokeListeners(@NonNull @WifiSettingsKey String key) {
        mHandler.post(() -> {
            mHasNewDataToSerialize = true;
            mWifiConfigManager.saveToStore(true);

            invokeListeners(key);
        });
    }

    /**
     * Store an int value to the stored settings.
     *
     * @param key One of the settings keys.
     * @param value Value to be stored.
     */
    public void putInt(@NonNull @WifiSettingsKey String key, int value) {
        synchronized (mLock) {
            mSettings.put(key, value);
        }
        triggerSaveToStoreAndInvokeListeners(key);
    }

    /**
     * Store a boolean value to the stored settings.
     *
     * @param key One of the settings keys.
     * @param value Value to be stored.
     */
    public void putBoolean(@NonNull @WifiSettingsKey String key, boolean value) {
        synchronized (mLock) {
            mSettings.put(key, value);
        }
        triggerSaveToStoreAndInvokeListeners(key);
    }

    /**
     * Store a String value to the stored settings.
     *
     * @param key One of the settings keys.
     * @param value Value to be stored.
     */
    public void putString(@NonNull @WifiSettingsKey String key, @NonNull String value) {
        synchronized (mLock) {
            mSettings.put(key, value);
        }
        triggerSaveToStoreAndInvokeListeners(key);
    }

    /**
     * Retrieve an int value from the stored settings.
     *
     * @param key One of the settings keys.
     * @param defValue Default value to be returned if the key does not exist.
     * @return value stored in settings, defValue if the key does not exist.
     */
    public int getInt(@NonNull @WifiSettingsKey String key, int defValue) {
        synchronized (mLock) {
            return (int) mSettings.getOrDefault(key, defValue);
        }
    }

    /**
     * Retrieve a boolean value from the stored settings.
     *
     * @param key One of the settings keys.
     * @param defValue Default value to be returned if the key does not exist.
     * @return value stored in settings, defValue if the key does not exist.
     */
    public boolean getBoolean(@NonNull @WifiSettingsKey String key, boolean defValue) {
        synchronized (mLock) {
            return (boolean) mSettings.getOrDefault(key, defValue);
        }
    }

    /**
     * Retrieve a string value from the stored settings.
     *
     * @param key One of the settings keys.
     * @param defValue Default value to be returned if the key does not exist.
     * @return value stored in settings, defValue if the key does not exist.
     */
    public @Nullable String getString(@NonNull @WifiSettingsKey String key,
            @Nullable String defValue) {
        synchronized (mLock) {
            return (String) mSettings.getOrDefault(key, defValue);
        }
    }

    /**
     * Register for settings change listener.
     *
     * @param key One of the settings keys.
     * @param listener Listener to be registered.
     * @param handler Handler to post the listener
     */
    public void registerChangeListener(@NonNull @WifiSettingsKey String key,
            @NonNull OnSettingsChangedListener listener, @NonNull Handler handler) {
        synchronized (mLock) {
            mListeners.computeIfAbsent(key, ignore -> new HashMap<>()).put(listener, handler);
        }
    }

    /**
     * Unregister for settings change listener.
     *
     * @param key One of the settings keys.
     * @param listener Listener to be unregistered.
     */
    public void unregisterChangeListener(@NonNull @WifiSettingsKey String key,
            @NonNull OnSettingsChangedListener listener) {
        synchronized (mLock) {
            Map<OnSettingsChangedListener, Handler> listeners = mListeners.get(key);
            if (listeners == null || listeners.isEmpty()) {
                Log.e(TAG, "No listeners for " + key);
                return;
            }
            if (listeners.remove(listener) == null) {
                Log.e(TAG, "Unknown listener for " + key);
            }
        }
    }

    /**
     * Store data for persisting the settings data to config store.
     */
    private class StoreData implements WifiConfigStore.StoreData {
        private static final String XML_TAG_SECTION_HEADER = "Settings";
        private static final String XML_TAG_VALUES = "Values";

        @Override
        public void serializeData(XmlSerializer out,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            synchronized (mLock) {
                XmlUtil.writeNextValue(out, XML_TAG_VALUES, mSettings);
            }
        }

        @Override
        public void deserializeData(XmlPullParser in, int outerTagDepth,
                @WifiConfigStore.Version int version,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            // Ignore empty reads.
            if (in == null) {
                return;
            }
            Map<String, Object> values = null;
            while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
                String[] valueName = new String[1];
                Object value = XmlUtil.readCurrentValue(in, valueName);
                if (TextUtils.isEmpty(valueName[0])) {
                    throw new XmlPullParserException("Missing value name");
                }
                switch (valueName[0]) {
                    case XML_TAG_VALUES:
                        values = (Map) value;
                        break;
                    default:
                        Log.w(TAG, "Ignoring unknown tag under " + XML_TAG_SECTION_HEADER + ": "
                                + valueName[0]);
                        break;
                }
            }
            if (values != null) {
                synchronized (mLock) {
                    mSettings.putAll(values);
                    // Invoke all the registered listeners.
                    invokeAllListeners();
                }
            }
        }

        @Override
        public void resetData() {
            synchronized (mLock) {
                mSettings.clear();
            }
        }

        @Override
        public boolean hasNewDataToSerialize() {
            return mHasNewDataToSerialize;
        }

        @Override
        public String getName() {
            return XML_TAG_SECTION_HEADER;
        }

        @Override
        public @WifiConfigStore.StoreFileId int getStoreFileId() {
            // Shared general store.
            return WifiConfigStore.STORE_FILE_USER_GENERAL;
        }
    }
}
