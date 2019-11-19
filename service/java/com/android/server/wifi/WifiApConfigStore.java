/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.MacAddress;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.Environment;
import android.os.Handler;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.wifi.R;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Random;

import javax.annotation.Nullable;
import javax.crypto.Mac;

/**
 * Provides API for reading/writing soft access point configuration.
 */
public class WifiApConfigStore {

    // Intent when user has interacted with the softap settings change notification
    public static final String ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT =
            "com.android.server.wifi.WifiApConfigStoreUtil.HOTSPOT_CONFIG_USER_TAPPED_CONTENT";

    private static final String TAG = "WifiApConfigStore";

    // Note: This is the legacy Softap config file. This is only used for migrating data out
    // of this file on first reboot.
    private static final String LEGACY_AP_CONFIG_FILE =
            Environment.getDataDirectory() + "/misc/wifi/softap.conf";

    @VisibleForTesting
    public static final int AP_CONFIG_FILE_VERSION = 3;

    private static final int RAND_SSID_INT_MIN = 1000;
    private static final int RAND_SSID_INT_MAX = 9999;

    @VisibleForTesting
    static final int SSID_MIN_LEN = 1;
    @VisibleForTesting
    static final int SSID_MAX_LEN = 32;
    @VisibleForTesting
    static final int PSK_MIN_LEN = 8;
    @VisibleForTesting
    static final int PSK_MAX_LEN = 63;

    @VisibleForTesting
    static final int AP_CHANNEL_DEFAULT = 0;

    private WifiConfiguration mPersistentWifiApConfig = null;

    private ArrayList<Integer> mAllowed2GChannel = null;

    private final Context mContext;
    private final WifiInjector mWifiInjector;
    private final Handler mHandler;
    private final BackupManagerProxy mBackupManagerProxy;
    private final FrameworkFacade mFrameworkFacade;
    private final MacAddressUtil mMacAddressUtil;
    private final Mac mMac;
    private final WifiConfigStore mWifiConfigStore;
    private final WifiConfigManager mWifiConfigManager;
    private boolean mRequiresApBandConversion = false;
    private boolean mHasNewDataToSerialize = false;

    /**
     * Module to interact with the wifi config store.
     */
    private class SoftApStoreDataSource implements SoftApStoreData.DataSource {

        public WifiConfiguration toSerialize() {
            mHasNewDataToSerialize = false;
            return mPersistentWifiApConfig;
        }

        public void fromDeserialized(WifiConfiguration config) {
            mPersistentWifiApConfig = new WifiConfiguration(config);
        }

        public void reset() {
            if (mPersistentWifiApConfig != null) {
                // Note: Reset is invoked when WifiConfigStore.read() is invoked on boot completed.
                // If we had migrated data from the legacy store before that (which is most likely
                // true because we read the legacy file in the constructor here, whereas
                // WifiConfigStore.read() is only triggered on boot completed), trigger a write to
                // persist the migrated data.
                mHandler.post(() -> mWifiConfigManager.saveToStore(true));
            }
        }

        public boolean hasNewDataToSerialize() {
            return mHasNewDataToSerialize;
        }
    }

    WifiApConfigStore(Context context, WifiInjector wifiInjector, Handler handler,
            BackupManagerProxy backupManagerProxy, FrameworkFacade frameworkFacade,
            WifiConfigStore wifiConfigStore, WifiConfigManager wifiConfigManager) {
        this(context, wifiInjector, handler, backupManagerProxy, frameworkFacade, wifiConfigStore,
                wifiConfigManager, LEGACY_AP_CONFIG_FILE);
    }

    WifiApConfigStore(Context context,
                      WifiInjector wifiInjector,
                      Handler handler,
                      BackupManagerProxy backupManagerProxy,
                      FrameworkFacade frameworkFacade,
                      WifiConfigStore wifiConfigStore,
                      WifiConfigManager wifiConfigManager,
                      String apConfigFile) {
        mContext = context;
        mWifiInjector = wifiInjector;
        mHandler = handler;
        mBackupManagerProxy = backupManagerProxy;
        mFrameworkFacade = frameworkFacade;
        mWifiConfigStore = wifiConfigStore;
        mWifiConfigManager = wifiConfigManager;

        String ap2GChannelListStr = mContext.getResources().getString(
                R.string.config_wifi_framework_sap_2G_channel_list);
        Log.d(TAG, "2G band allowed channels are:" + ap2GChannelListStr);

        if (ap2GChannelListStr != null) {
            mAllowed2GChannel = new ArrayList<Integer>();
            String channelList[] = ap2GChannelListStr.split(",");
            for (String tmp : channelList) {
                mAllowed2GChannel.add(Integer.parseInt(tmp));
            }
        }

        mRequiresApBandConversion = mContext.getResources().getBoolean(
                R.bool.config_wifi_convert_apband_5ghz_to_any);

        // One time migration from legacy config store.
        try {
            File file = new File(apConfigFile);
            FileInputStream fis = new FileInputStream(apConfigFile);
            /* Load AP configuration from persistent storage. */
            WifiConfiguration config = loadApConfigurationFromLegacyFile(fis);
            if (config != null) {
                // Persist in the new store.
                persistConfigAndTriggerBackupManagerProxy(config);
                Log.i(TAG, "Migrated data out of legacy store file " + apConfigFile);
                // delete the legacy file.
                file.delete();
            }
        } catch (FileNotFoundException e) {
            // Expected on further reboots after the first reboot.
        }

        // Register store data listener
        mWifiConfigStore.registerStoreData(
                mWifiInjector.makeSoftApStoreData(new SoftApStoreDataSource()));

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT);
        mContext.registerReceiver(
                mBroadcastReceiver, filter, null /* broadcastPermission */, mHandler);
        mMacAddressUtil = mWifiInjector.getMacAddressUtil();
        mMac = mMacAddressUtil.obtainMacRandHashFunctionForSap(Process.WIFI_UID);
        if (mMac == null) {
            Log.wtf(TAG, "Failed to obtain secret for SAP MAC randomization."
                    + " All randomized MAC addresses are lost!");
        }
    }

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // For now we only have one registered listener, but we easily could expand this
                    // to support multiple signals.  Starting off with a switch to support trivial
                    // expansion.
                    switch(intent.getAction()) {
                        case ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT:
                            handleUserHotspotConfigTappedContent();
                            break;
                        default:
                            Log.e(TAG, "Unknown action " + intent.getAction());
                    }
                }
            };

    /**
     * Return the current soft access point configuration.
     */
    public synchronized WifiConfiguration getApConfiguration() {
        if (mPersistentWifiApConfig == null) {
            /* Use default configuration. */
            Log.d(TAG, "Fallback to use default AP configuration");
            persistConfigAndTriggerBackupManagerProxy(getDefaultApConfiguration());
        }
        WifiConfiguration sanitizedPersistentconfig =
                sanitizePersistentApConfig(mPersistentWifiApConfig);
        if (mPersistentWifiApConfig != sanitizedPersistentconfig) {
            Log.d(TAG, "persisted config was converted, need to resave it");
            persistConfigAndTriggerBackupManagerProxy(sanitizedPersistentconfig);
        }
        return mPersistentWifiApConfig;
    }

    /**
     * Update the current soft access point configuration.
     * Restore to default AP configuration if null is provided.
     * This can be invoked under context of binder threads (WifiManager.setWifiApConfiguration)
     * and the main Wifi thread (CMD_START_AP).
     */
    public synchronized void setApConfiguration(WifiConfiguration config) {
        if (config == null) {
            config = getDefaultApConfiguration();
        } else {
            config = sanitizePersistentApConfig(config);
        }
        persistConfigAndTriggerBackupManagerProxy(config);
    }

    public ArrayList<Integer> getAllowed2GChannel() {
        return mAllowed2GChannel;
    }

    /**
     * Helper method to create and send notification to user of apBand conversion.
     *
     * @param packageName name of the calling app
     */
    public void notifyUserOfApBandConversion(String packageName) {
        Log.w(TAG, "ready to post notification - triggered by " + packageName);
        Notification notification = createConversionNotification();
        NotificationManager notificationManager = (NotificationManager)
                    mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(SystemMessage.NOTE_SOFTAP_CONFIG_CHANGED, notification);
    }

    private Notification createConversionNotification() {
        CharSequence title =
                mContext.getResources().getText(R.string.wifi_softap_config_change);
        CharSequence contentSummary =
                mContext.getResources().getText(R.string.wifi_softap_config_change_summary);
        CharSequence content =
                mContext.getResources().getText(R.string.wifi_softap_config_change_detailed);
        int color =
                mContext.getResources().getColor(
                        android.R.color.system_notification_accent_color, mContext.getTheme());

        return new Notification.Builder(mContext, WifiStackService.NOTIFICATION_NETWORK_STATUS)
                .setSmallIcon(R.drawable.ic_wifi_settings)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setContentTitle(title)
                .setContentText(contentSummary)
                .setContentIntent(getPrivateBroadcast(ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT))
                .setTicker(title)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setColor(color)
                .setStyle(new Notification.BigTextStyle().bigText(content)
                                                         .setBigContentTitle(title)
                                                         .setSummaryText(contentSummary))
                .build();
    }

    private WifiConfiguration sanitizePersistentApConfig(WifiConfiguration config) {
        WifiConfiguration convertedConfig = null;

        // Persistent config may not set BSSID.
        if (config.BSSID != null) {
            convertedConfig = new WifiConfiguration(config);
            convertedConfig.BSSID = null;
        }

        if (mRequiresApBandConversion) {
            // some devices are unable to support 5GHz only operation, check for 5GHz and
            // move to ANY if apBand conversion is required.
            if (config.apBand == WifiConfiguration.AP_BAND_5GHZ) {
                Log.w(TAG, "Supplied ap config band was 5GHz only, converting to ANY");
                if (convertedConfig == null) {
                    convertedConfig = new WifiConfiguration(config);
                }
                convertedConfig.apBand = WifiConfiguration.AP_BAND_ANY;
                convertedConfig.apChannel = AP_CHANNEL_DEFAULT;
            }
        } else {
            // this is a single mode device, we do not support ANY.  Convert all ANY to 5GHz
            if (config.apBand == WifiConfiguration.AP_BAND_ANY) {
                Log.w(TAG, "Supplied ap config band was ANY, converting to 5GHz");
                if (convertedConfig == null) {
                    convertedConfig = new WifiConfiguration(config);
                }
                convertedConfig.apBand = WifiConfiguration.AP_BAND_5GHZ;
                convertedConfig.apChannel = AP_CHANNEL_DEFAULT;
            }
        }
        return convertedConfig == null ? config : convertedConfig;
    }

    private void persistConfigAndTriggerBackupManagerProxy(WifiConfiguration config) {
        mPersistentWifiApConfig = config;
        mHasNewDataToSerialize = true;
        mWifiConfigManager.saveToStore(true);
        mBackupManagerProxy.notifyDataChanged();
    }

    /**
     * Load AP configuration from legacy persistent storage.
     * Note: This is deprecated and only used for migrating data once on reboot.
     */
    private static WifiConfiguration loadApConfigurationFromLegacyFile(FileInputStream fis) {
        WifiConfiguration config = null;
        DataInputStream in = null;
        try {
            config = new WifiConfiguration();
            in = new DataInputStream(new BufferedInputStream(fis));

            int version = in.readInt();
            if (version < 1 || version > AP_CONFIG_FILE_VERSION) {
                Log.e(TAG, "Bad version on hotspot configuration file");
                return null;
            }
            config.SSID = in.readUTF();

            if (version >= 2) {
                config.apBand = in.readInt();
                config.apChannel = in.readInt();
            }

            if (version >= 3) {
                config.hiddenSSID = in.readBoolean();
            }

            int authType = in.readInt();
            config.allowedKeyManagement.set(authType);
            if (authType != KeyMgmt.NONE) {
                config.preSharedKey = in.readUTF();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading hotspot configuration " + e);
            config = null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing hotspot configuration during read" + e);
                }
            }
        }
        return config;
    }

    /**
     * Generate a default WPA2 based configuration with a random password.
     * We are changing the Wifi Ap configuration storage from secure settings to a
     * flat file accessible only by the system. A WPA2 based default configuration
     * will keep the device secure after the update.
     */
    private WifiConfiguration getDefaultApConfiguration() {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_2GHZ;
        config.SSID = mContext.getResources().getString(
                R.string.wifi_tether_configure_ssid_default) + "_" + getRandomIntForDefaultSsid();
        config.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
        config.preSharedKey = generatePassword();
        return config;
    }

    private static int getRandomIntForDefaultSsid() {
        Random random = new Random();
        return random.nextInt((RAND_SSID_INT_MAX - RAND_SSID_INT_MIN) + 1) + RAND_SSID_INT_MIN;
    }

    private static String generateLohsSsid(Context context) {
        return context.getResources().getString(
                R.string.wifi_localhotspot_configure_ssid_default) + "_"
                + getRandomIntForDefaultSsid();
    }

    /**
     * Generate a temporary WPA2 based configuration for use by the local only hotspot.
     * This config is not persisted and will not be stored by the WifiApConfigStore.
     */
    public static WifiConfiguration generateLocalOnlyHotspotConfig(Context context, int apBand,
            @Nullable SoftApConfiguration customConfig) {
        WifiConfiguration config = new WifiConfiguration();

        config.apBand = apBand;
        config.networkId = WifiConfiguration.LOCAL_ONLY_NETWORK_ID;

        if (customConfig == null || customConfig.getSsid() == null) {
            config.SSID = generateLohsSsid(context);
        } else {
            config.SSID = customConfig.getSsid();
        }
        if (customConfig == null) {
            config.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
            config.preSharedKey = generatePassword();
        } else if (customConfig.getWpa2Passphrase() != null) {
            config.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
            config.preSharedKey = customConfig.getWpa2Passphrase();
        } else {
            config.allowedKeyManagement.set(KeyMgmt.NONE);
        }
        if (customConfig != null && customConfig.getBssid() != null) {
            config.BSSID = customConfig.getBssid().toString();
        } else {
            // use factory or random BSSID
            config.BSSID = null;
        }
        return config;
    }

    /**
     * @return a copy of the given WifiConfig with the BSSID randomized, unless a custom BSSID is
     * already set.
     */
    WifiConfiguration randomizeBssidIfUnset(Context context, WifiConfiguration config) {
        config = new WifiConfiguration(config);
        if (config.BSSID == null && context.getResources().getBoolean(
                R.bool.config_wifi_ap_mac_randomization_supported)) {
            MacAddress macAddress = mMacAddressUtil.calculatePersistentMac(config.SSID, mMac);
            if (macAddress == null) {
                Log.e(TAG, "Failed to calculate MAC from SSID. "
                        + "Generating new random MAC instead.");
                macAddress = MacAddress.createRandomUnicastAddress();
            }
            config.BSSID = macAddress.toString();
        }
        return config;
    }

    /**
     * Verify provided SSID for existence, length and conversion to bytes
     *
     * @param ssid String ssid name
     * @return boolean indicating ssid met requirements
     */
    private static boolean validateApConfigSsid(String ssid) {
        if (TextUtils.isEmpty(ssid)) {
            Log.d(TAG, "SSID for softap configuration must be set.");
            return false;
        }

        try {
            byte[] ssid_bytes = ssid.getBytes(StandardCharsets.UTF_8);

            if (ssid_bytes.length < SSID_MIN_LEN || ssid_bytes.length > SSID_MAX_LEN) {
                Log.d(TAG, "softap SSID is defined as UTF-8 and it must be at least "
                        + SSID_MIN_LEN + " byte and not more than " + SSID_MAX_LEN + " bytes");
                return false;
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "softap config SSID verification failed: malformed string " + ssid);
            return false;
        }
        return true;
    }

    /**
     * Verify provided preSharedKey in ap config for WPA2_PSK network meets requirements.
     */
    private static boolean validateApConfigPreSharedKey(String preSharedKey) {
        if (preSharedKey.length() < PSK_MIN_LEN || preSharedKey.length() > PSK_MAX_LEN) {
            Log.d(TAG, "softap network password string size must be at least " + PSK_MIN_LEN
                    + " and no more than " + PSK_MAX_LEN);
            return false;
        }

        try {
            preSharedKey.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "softap network password verification failed: malformed string");
            return false;
        }
        return true;
    }

    /**
     * Validate a WifiConfiguration is properly configured for use by SoftApManager.
     *
     * This method checks the length of the SSID and for sanity between security settings (if it
     * requires a password, was one provided?).
     *
     * @param apConfig {@link WifiConfiguration} to use for softap mode
     * @return boolean true if the provided config meets the minimum set of details, false
     * otherwise.
     */
    static boolean validateApWifiConfiguration(@NonNull WifiConfiguration apConfig) {
        // first check the SSID
        if (!validateApConfigSsid(apConfig.SSID)) {
            // failed SSID verificiation checks
            return false;
        }

        // now check security settings: settings app allows open and WPA2 PSK
        if (apConfig.allowedKeyManagement == null) {
            Log.d(TAG, "softap config key management bitset was null");
            return false;
        }

        String preSharedKey = apConfig.preSharedKey;
        boolean hasPreSharedKey = !TextUtils.isEmpty(preSharedKey);
        int authType;

        try {
            authType = apConfig.getAuthType();
        } catch (IllegalStateException e) {
            Log.d(TAG, "Unable to get AuthType for softap config: " + e.getMessage());
            return false;
        }

        if (authType == KeyMgmt.NONE) {
            // open networks should not have a password
            if (hasPreSharedKey) {
                Log.d(TAG, "open softap network should not have a password");
                return false;
            }
        } else if (authType == KeyMgmt.WPA2_PSK) {
            // this is a config that should have a password - check that first
            if (!hasPreSharedKey) {
                Log.d(TAG, "softap network password must be set");
                return false;
            }

            if (!validateApConfigPreSharedKey(preSharedKey)) {
                // failed preSharedKey checks
                return false;
            }
        } else {
            // this is not a supported security type
            Log.d(TAG, "softap configs must either be open or WPA2 PSK networks");
            return false;
        }

        return true;
    }

    /**
     * Helper method to start up settings on the softap config page.
     */
    private void startSoftApSettings() {
        mContext.startActivity(
                new Intent("com.android.settings.WIFI_TETHER_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * Helper method to trigger settings to open the softap config page
     */
    private void handleUserHotspotConfigTappedContent() {
        startSoftApSettings();
        NotificationManager notificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(SystemMessage.NOTE_SOFTAP_CONFIG_CHANGED);
    }

    private PendingIntent getPrivateBroadcast(String action) {
        Intent intent = new Intent(action)
                .setPackage(mWifiInjector.getWifiStackPackageName());
        return mFrameworkFacade.getBroadcast(
                mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String generatePassword() {
        // Characters that will be used for password generation. Some characters commonly known to
        // be confusing like 0 and O excluded from this list.
        final String allowed = "23456789abcdefghijkmnpqrstuvwxyz";
        final int passLength = 15;

        StringBuilder sb = new StringBuilder(passLength);
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < passLength; i++) {
            sb.append(allowed.charAt(random.nextInt(allowed.length())));
        }
        return sb.toString();
    }
}
