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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.MacAddress;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.Build;
import android.os.Handler;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * Unit tests for {@link com.android.server.wifi.WifiApConfigStore}.
 */
@SmallTest
public class WifiApConfigStoreTest extends WifiBaseTest {

    private static final String TAG = "WifiApConfigStoreTest";

    private static final String TEST_AP_CONFIG_FILE_PREFIX = "APConfig_";
    private static final String TEST_DEFAULT_2G_CHANNEL_LIST = "1,2,3,4,5,6";
    private static final String TEST_DEFAULT_AP_SSID = "TestAP";
    private static final String TEST_DEFAULT_HOTSPOT_SSID = "TestShare";
    private static final String TEST_DEFAULT_HOTSPOT_PSK = "TestPassword";
    private static final int RAND_SSID_INT_MIN = 1000;
    private static final int RAND_SSID_INT_MAX = 9999;
    private static final String TEST_CHAR_SET_AS_STRING = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String TEST_STRING_UTF8_WITH_30_BYTES = "智者務其實愚者爭虛名";
    private static final String TEST_STRING_UTF8_WITH_32_BYTES = "ΣωκράτηςΣωκράτης";
    private static final String TEST_STRING_UTF8_WITH_33_BYTES = "一片汪洋大海中的一條魚";
    private static final String TEST_STRING_UTF8_WITH_34_BYTES = "Ευπροσηγοροςγινου";
    private static final MacAddress TEST_RANDOMIZED_MAC =
            MacAddress.fromString("d2:11:19:34:a5:20");

    @Mock private Context mContext;
    @Mock private WifiInjector mWifiInjector;
    private TestLooper mLooper;
    private Handler mHandler;
    @Mock private BackupManagerProxy mBackupManagerProxy;
    @Mock private WifiConfigStore mWifiConfigStore;
    @Mock private WifiConfigManager mWifiConfigManager;
    private File mLegacyApConfigFile;
    private Random mRandom;
    private MockResources mResources;
    @Mock private ApplicationInfo mMockApplInfo;
    @Mock private MacAddressUtil mMacAddressUtil;
    private SoftApStoreData.DataSource mDataStoreSource;
    private ArrayList<Integer> mKnownGood2GChannelList;

    @Before
    public void setUp() throws Exception {
        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());
        MockitoAnnotations.initMocks(this);
        mMockApplInfo.targetSdkVersion = Build.VERSION_CODES.P;
        when(mContext.getApplicationInfo()).thenReturn(mMockApplInfo);

        /* Setup expectations for Resources to return some default settings. */
        mResources = new MockResources();
        mResources.setString(R.string.config_wifi_framework_sap_2G_channel_list,
                             TEST_DEFAULT_2G_CHANNEL_LIST);
        mResources.setString(R.string.wifi_tether_configure_ssid_default,
                             TEST_DEFAULT_AP_SSID);
        mResources.setString(R.string.wifi_localhotspot_configure_ssid_default,
                             TEST_DEFAULT_HOTSPOT_SSID);
        /* Default to device that does not require ap band conversion */
        mResources.setBoolean(R.bool.config_wifi_convert_apband_5ghz_to_any, false);
        when(mContext.getResources()).thenReturn(mResources);

        // build the known good 2G channel list: TEST_DEFAULT_2G_CHANNEL_LIST
        mKnownGood2GChannelList = new ArrayList(Arrays.asList(1, 2, 3, 4, 5, 6));

        mRandom = new Random();
        when(mWifiInjector.getMacAddressUtil()).thenReturn(mMacAddressUtil);
        when(mMacAddressUtil.calculatePersistentMac(any(), any())).thenReturn(TEST_RANDOMIZED_MAC);
    }

    /**
     * Helper method to create and verify actions for the ApConfigStore used in the following tests.
     */
    private WifiApConfigStore createWifiApConfigStore(String legacyFilePath) {
        WifiApConfigStore store;
        if (legacyFilePath == null) {
            store = new WifiApConfigStore(
                    mContext, mWifiInjector, mHandler, mBackupManagerProxy,
                    mWifiConfigStore, mWifiConfigManager);
        } else {
            store = new WifiApConfigStore(
                    mContext, mWifiInjector, mHandler, mBackupManagerProxy,
                    mWifiConfigStore, mWifiConfigManager, legacyFilePath);
        }

        verify(mWifiConfigStore).registerStoreData(any());
        ArgumentCaptor<SoftApStoreData.DataSource> dataStoreSourceArgumentCaptor =
                ArgumentCaptor.forClass(SoftApStoreData.DataSource.class);
        verify(mWifiInjector).makeSoftApStoreData(dataStoreSourceArgumentCaptor.capture());
        mDataStoreSource = dataStoreSourceArgumentCaptor.getValue();

        return store;
    }

    private WifiApConfigStore createWifiApConfigStore() {
        return createWifiApConfigStore(null);
    }

    /**
     * Generate a WifiConfiguration based on the specified parameters.
     */
    private WifiConfiguration setupApConfig(
            String ssid, String preSharedKey, int keyManagement, int band, int channel,
            boolean hiddenSSID) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = ssid;
        config.preSharedKey = preSharedKey;
        config.allowedKeyManagement.set(keyManagement);
        config.apBand = band;
        config.apChannel = channel;
        config.hiddenSSID = hiddenSSID;
        return config;
    }

    private void writeLegacyApConfigFile(WifiConfiguration config) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(mLegacyApConfigFile)))) {
            out.writeInt(WifiApConfigStore.AP_CONFIG_FILE_VERSION);
            out.writeUTF(config.SSID);
            out.writeInt(config.apBand);
            out.writeInt(config.apChannel);
            out.writeBoolean(config.hiddenSSID);
            int authType = config.getAuthType();
            out.writeInt(authType);
            if (authType != KeyMgmt.NONE) {
                out.writeUTF(config.preSharedKey);
            }
        } catch (IOException e) {
            fail("Error writing hotspot configuration" + e);
        }
    }

    private void verifyApConfig(WifiConfiguration config1, WifiConfiguration config2) {
        assertEquals(config1.SSID, config2.SSID);
        assertEquals(config1.preSharedKey, config2.preSharedKey);
        assertEquals(config1.allowedKeyManagement, config2.allowedKeyManagement);
        assertEquals(config1.getAuthType(), config2.getAuthType());
        assertEquals(config1.apBand, config2.apBand);
        assertEquals(config1.apChannel, config2.apChannel);
        assertEquals(config1.hiddenSSID, config2.hiddenSSID);
    }

    private void verifyDefaultApConfig(WifiConfiguration config, String expectedSsid) {
        String[] splitSsid = config.SSID.split("_");
        assertEquals(2, splitSsid.length);
        assertEquals(expectedSsid, splitSsid[0]);
        assertEquals(WifiConfiguration.AP_BAND_2GHZ, config.apBand);
        assertFalse(config.hiddenSSID);
        int randomPortion = Integer.parseInt(splitSsid[1]);
        assertTrue(randomPortion >= RAND_SSID_INT_MIN && randomPortion <= RAND_SSID_INT_MAX);
        assertTrue(config.allowedKeyManagement.get(KeyMgmt.WPA2_PSK));
        assertEquals(15, config.preSharedKey.length());
    }

    private void verifyDefaultLocalOnlyApConfig(WifiConfiguration config, String expectedSsid,
            int expectedApBand) {
        String[] splitSsid = config.SSID.split("_");
        assertEquals(2, splitSsid.length);
        assertEquals(expectedSsid, splitSsid[0]);
        assertEquals(expectedApBand, config.apBand);
        int randomPortion = Integer.parseInt(splitSsid[1]);
        assertTrue(randomPortion >= RAND_SSID_INT_MIN && randomPortion <= RAND_SSID_INT_MAX);
        assertTrue(config.allowedKeyManagement.get(KeyMgmt.WPA2_PSK));
        assertEquals(15, config.preSharedKey.length());
    }


    /**
     * AP Configuration is not specified in the config file,
     * WifiApConfigStore should fallback to use the default configuration.
     */
    @Test
    public void initWithDefaultConfiguration() throws Exception {
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);
    }

    /**
     * Verify WifiApConfigStore can correctly load the existing configuration
     * from the legacy config file and migrate it to the new config store.
     */
    @Test
    public void initWithExistingConfigurationInLegacyFile() throws Exception {
        WifiConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",    /* SSID */
                "randomKey",       /* preshared key */
                KeyMgmt.WPA_EAP,   /* key management */
                1,                 /* AP band (5GHz) */
                40,                /* AP channel */
                true               /* Hidden SSID */);
        /* Create a temporary file for AP config file storage. */
        mLegacyApConfigFile = File.createTempFile(TEST_AP_CONFIG_FILE_PREFIX, "");

        writeLegacyApConfigFile(expectedConfig);
        WifiApConfigStore store = createWifiApConfigStore(mLegacyApConfigFile.getPath());
        verify(mWifiConfigManager).saveToStore(true);
        verify(mBackupManagerProxy).notifyDataChanged();
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        // Simulate the config store read to trigger the write to new config store.
        mDataStoreSource.reset();
        mLooper.dispatchAll();
        // Triggers write twice:
        // a) On reading the legacy file (new config store not ready yet)
        // b) When the new config store is ready.
        verify(mWifiConfigManager, times(2)).saveToStore(true);

        // The temporary legacy AP config file should be removed after migration.
        assertFalse(mLegacyApConfigFile.exists());
    }

    /**
     * Verify the handling of setting a null ap configuration.
     * WifiApConfigStore should fallback to the default configuration when
     * null ap configuration is provided.
     */
    @Test
    public void setNullApConfiguration() throws Exception {
        /* Initialize WifiApConfigStore with existing configuration. */
        WifiConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",    /* SSID */
                "randomKey",       /* preshared key */
                KeyMgmt.WPA_EAP,   /* key management */
                1,                 /* AP band (5GHz) */
                40,                /* AP channel */
                true               /* Hidden SSID */);
        WifiApConfigStore store = createWifiApConfigStore();
        mDataStoreSource.fromDeserialized(expectedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());

        store.setApConfiguration(null);
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verifyDefaultApConfig(mDataStoreSource.toSerialize(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);
        verify(mBackupManagerProxy).notifyDataChanged();
    }

    /**
     * Verify AP configuration is correctly updated via setApConfiguration call.
     */
    @Test
    public void updateApConfiguration() throws Exception {
        /* Initialize WifiApConfigStore with default configuration. */
        WifiApConfigStore store = createWifiApConfigStore();

        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);

        /* Update with a valid configuration. */
        WifiConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                   /* SSID */
                "randomKey",                      /* preshared key */
                KeyMgmt.WPA_EAP,                  /* key management */
                WifiConfiguration.AP_BAND_2GHZ,   /* AP band */
                40,                               /* AP channel */
                true                              /* Hidden SSID */);
        store.setApConfiguration(expectedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager, times(2)).saveToStore(true);
        verify(mBackupManagerProxy, times(2)).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a single mode device will have apBand =
     * ANY converted to 5GHZ.
     */
    @Test
    public void convertSingleModeDeviceAnyTo5Ghz() throws Exception {
        /* Initialize WifiApConfigStore with default configuration. */
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);

        /* Update with a valid configuration. */
        WifiConfiguration providedConfig = setupApConfig(
                "ConfiguredAP",                /* SSID */
                "randomKey",                   /* preshared key */
                KeyMgmt.WPA_EAP,               /* key management */
                WifiConfiguration.AP_BAND_ANY, /* AP band (ANY) */
                40,                            /* AP channel */
                false                          /* Hidden SSID */);

        WifiConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                       /* SSID */
                "randomKey",                          /* preshared key */
                KeyMgmt.WPA_EAP,                      /* key management */
                WifiConfiguration.AP_BAND_5GHZ,       /* AP band (5GHz) */
                WifiApConfigStore.AP_CHANNEL_DEFAULT, /* AP channel */
                false                                 /* Hidden SSID */);
        store.setApConfiguration(providedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager, times(2)).saveToStore(true);
        verify(mBackupManagerProxy, times(2)).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a single mode device does not convert
     * apBand to ANY.
     */
    @Test
    public void singleModeDevice5GhzNotConverted() throws Exception {
        /* Initialize WifiApConfigStore with default configuration. */
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);

        /* Update with a valid configuration. */
        WifiConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                 /* SSID */
                "randomKey",                    /* preshared key */
                KeyMgmt.WPA_EAP,                /* key management */
                WifiConfiguration.AP_BAND_5GHZ, /* AP band */
                40,                             /* AP channel */
                false                           /* Hidden SSID */);
        store.setApConfiguration(expectedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager, times(2)).saveToStore(true);
        verify(mBackupManagerProxy, times(2)).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a dual mode device will have apBand =
     * 5GHz converted to ANY.
     */
    @Test
    public void convertDualModeDevice5GhzToAny() throws Exception {
        mResources.setBoolean(R.bool.config_wifi_convert_apband_5ghz_to_any, true);

        /* Initialize WifiApConfigStore with default configuration. */
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);

        /* Update with a valid configuration. */
        WifiConfiguration providedConfig = setupApConfig(
                "ConfiguredAP",                 /* SSID */
                "randomKey",                    /* preshared key */
                KeyMgmt.WPA_EAP,                /* key management */
                WifiConfiguration.AP_BAND_5GHZ, /* AP band */
                40,                             /* AP channel */
                false                           /* Hidden SSID */);

        WifiConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                       /* SSID */
                "randomKey",                          /* preshared key */
                KeyMgmt.WPA_EAP,                      /* key management */
                WifiConfiguration.AP_BAND_ANY,        /* AP band */
                WifiApConfigStore.AP_CHANNEL_DEFAULT, /* AP channel */
                false                                 /* Hidden SSID */);
        store.setApConfiguration(providedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager, times(2)).saveToStore(true);
        verify(mBackupManagerProxy, times(2)).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a dual mode device does not convert
     * apBand to 5Ghz.
     */
    @Test
    public void dualModeDeviceAnyNotConverted() throws Exception {
        mResources.setBoolean(R.bool.config_wifi_convert_apband_5ghz_to_any, true);

        /* Initialize WifiApConfigStore with default configuration. */
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);

        /* Update with a valid configuration. */
        WifiConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                 /* SSID */
                "randomKey",                    /* preshared key */
                KeyMgmt.WPA_EAP,                /* key management */
                WifiConfiguration.AP_BAND_ANY,  /* AP band */
                40,                             /* AP channel */
                false                           /* Hidden SSID */);
        store.setApConfiguration(expectedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager, times(2)).saveToStore(true);
        verify(mBackupManagerProxy, times(2)).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a single mode device converts a persisted
     * ap config with ANY set for the apBand to 5GHz.
     */
    @Test
    public void singleModeDeviceAnyConvertedTo5GhzAtRetrieval() throws Exception {

        WifiConfiguration persistedConfig = setupApConfig(
                "ConfiguredAP",                 /* SSID */
                "randomKey",                    /* preshared key */
                KeyMgmt.WPA_EAP,                /* key management */
                WifiConfiguration.AP_BAND_ANY,  /* AP band */
                40,                             /* AP channel */
                false                           /* Hidden SSID */);
        WifiConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                        /* SSID */
                "randomKey",                           /* preshared key */
                KeyMgmt.WPA_EAP,                       /* key management */
                WifiConfiguration.AP_BAND_5GHZ,        /* AP band */
                WifiApConfigStore.AP_CHANNEL_DEFAULT,  /* AP channel */
                false                                  /* Hidden SSID */);
        WifiApConfigStore store = createWifiApConfigStore();
        mDataStoreSource.fromDeserialized(persistedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager).saveToStore(true);
        verify(mBackupManagerProxy).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a single mode device does not convert
     * a persisted ap config with 5GHz set for the apBand.
     */
    @Test
    public void singleModeDeviceNotConvertedAtRetrieval() throws Exception {
        WifiConfiguration persistedConfig = setupApConfig(
                "ConfiguredAP",                  /* SSID */
                "randomKey",                     /* preshared key */
                KeyMgmt.WPA_EAP,                 /* key management */
                WifiConfiguration.AP_BAND_5GHZ,  /* AP band */
                40,                              /* AP channel */
                false                            /* Hidden SSID */);

        WifiApConfigStore store = createWifiApConfigStore();
        mDataStoreSource.fromDeserialized(persistedConfig);
        verifyApConfig(persistedConfig, store.getApConfiguration());
        verify(mWifiConfigManager, never()).saveToStore(true);
        verify(mBackupManagerProxy, never()).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a dual mode device converts a persisted ap
     * config with 5GHz only set for the apBand to ANY.
     */
    @Test
    public void dualModeDevice5GhzConvertedToAnyAtRetrieval() throws Exception {
        mResources.setBoolean(R.bool.config_wifi_convert_apband_5ghz_to_any, true);

        WifiConfiguration persistedConfig = setupApConfig(
                "ConfiguredAP",                  /* SSID */
                "randomKey",                     /* preshared key */
                KeyMgmt.WPA_EAP,                 /* key management */
                WifiConfiguration.AP_BAND_5GHZ,  /* AP band */
                40,                              /* AP channel */
                false                            /* Hidden SSID */);
        WifiConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                       /* SSID */
                "randomKey",                          /* preshared key */
                KeyMgmt.WPA_EAP,                      /* key management */
                WifiConfiguration.AP_BAND_ANY,        /* AP band */
                WifiApConfigStore.AP_CHANNEL_DEFAULT, /* AP channel */
                false                                 /* Hidden SSID */);

        WifiApConfigStore store = createWifiApConfigStore();
        mDataStoreSource.fromDeserialized(persistedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager).saveToStore(true);
        verify(mBackupManagerProxy).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a dual mode device does not convert
     * a persisted ap config with ANY set for the apBand.
     */
    @Test
    public void dualModeDeviceNotConvertedAtRetrieval() throws Exception {
        mResources.setBoolean(R.bool.config_wifi_convert_apband_5ghz_to_any, true);

        WifiConfiguration persistedConfig = setupApConfig(
                "ConfiguredAP",                 /* SSID */
                "randomKey",                    /* preshared key */
                KeyMgmt.WPA_EAP,                /* key management */
                WifiConfiguration.AP_BAND_ANY,  /* AP band */
                40,                             /* AP channel */
                false                           /* Hidden SSID */);

        WifiApConfigStore store = createWifiApConfigStore();
        mDataStoreSource.fromDeserialized(persistedConfig);
        verifyApConfig(persistedConfig, store.getApConfiguration());
        verify(mWifiConfigManager, never()).saveToStore(true);
        verify(mBackupManagerProxy, never()).notifyDataChanged();
    }

    /**
     * Verify a proper WifiConfiguration is generate by getDefaultApConfiguration().
     */
    @Test
    public void getDefaultApConfigurationIsValid() {
        WifiApConfigStore store = createWifiApConfigStore();
        WifiConfiguration config = store.getApConfiguration();
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
    }

    /**
     * Verify a proper local only hotspot config is generated when called properly with the valid
     * context.
     */
    @Test
    public void generateLocalOnlyHotspotConfigIsValid() {
        WifiConfiguration config = WifiApConfigStore
                .generateLocalOnlyHotspotConfig(mContext, WifiConfiguration.AP_BAND_2GHZ, null);
        verifyDefaultLocalOnlyApConfig(config, TEST_DEFAULT_HOTSPOT_SSID,
                WifiConfiguration.AP_BAND_2GHZ);
        // The LOHS config should also have a specific network id set - check that as well.
        assertEquals(WifiConfiguration.LOCAL_ONLY_NETWORK_ID, config.networkId);

        // verify that the config passes the validateApWifiConfiguration check
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
    }

    /**
     * Verify a proper local only hotspot config is generated for 5Ghz band.
     */
    @Test
    public void generateLocalOnlyHotspotConfigIsValid5G() {
        WifiConfiguration config = WifiApConfigStore
                .generateLocalOnlyHotspotConfig(mContext, WifiConfiguration.AP_BAND_5GHZ, null);
        verifyDefaultLocalOnlyApConfig(config, TEST_DEFAULT_HOTSPOT_SSID,
                WifiConfiguration.AP_BAND_5GHZ);
        // The LOHS config should also have a specific network id set - check that as well.
        assertEquals(WifiConfiguration.LOCAL_ONLY_NETWORK_ID, config.networkId);

        // verify that the config passes the validateApWifiConfiguration check
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
    }

    @Test
    public void generateLohsConfig_forwardsCustomMac() {
        SoftApConfiguration customConfig = new SoftApConfiguration.Builder()
                .setBssid(MacAddress.fromString("11:22:33:44:55:66"))
                .build();
        WifiConfiguration wifiConfig = WifiApConfigStore.generateLocalOnlyHotspotConfig(
                mContext, WifiConfiguration.AP_BAND_2GHZ, customConfig);
        assertThat(wifiConfig.BSSID).isNotEmpty();
        assertThat(MacAddress.fromString(wifiConfig.BSSID)).isEqualTo(
                MacAddress.fromString("11:22:33:44:55:66"));
    }

    @Test
    public void randomizeBssid_randomizesWhenEnabled() {
        mResources.setBoolean(R.bool.config_wifi_ap_mac_randomization_supported, true);
        WifiConfiguration baseConfig = new WifiConfiguration();

        WifiApConfigStore store = createWifiApConfigStore();
        WifiConfiguration config = store.randomizeBssidIfUnset(mContext, baseConfig);

        assertEquals(TEST_RANDOMIZED_MAC.toString(), config.BSSID);
    }

    @Test
    public void randomizeBssid_usesFactoryMacWhenRandomizationOff() {
        mResources.setBoolean(R.bool.config_wifi_ap_mac_randomization_supported, false);
        WifiConfiguration baseConfig = new WifiConfiguration();

        WifiApConfigStore store = createWifiApConfigStore();
        WifiConfiguration config = store.randomizeBssidIfUnset(mContext, baseConfig);

        assertThat(config.BSSID).isNull();
    }

    @Test
    public void randomizeBssid_forwardsCustomMac() {
        mResources.setBoolean(R.bool.config_wifi_ap_mac_randomization_supported, true);
        WifiConfiguration baseConfig = new WifiConfiguration();
        baseConfig.BSSID = "11:22:33:44:55:66";

        WifiApConfigStore store = createWifiApConfigStore();
        WifiConfiguration config = store.randomizeBssidIfUnset(mContext, baseConfig);

        assertThat(config.BSSID).isNotEmpty();
        assertThat(MacAddress.fromString(config.BSSID)).isEqualTo(
                MacAddress.fromString("11:22:33:44:55:66"));
    }

    /**
     * Helper method to generate random SSIDs.
     *
     * Note: this method has limited use as a random SSID generator.  The characters used in this
     * method do no not cover all valid inputs.
     * @param length number of characters to generate for the name
     * @return String generated string of random characters
     */
    private String generateRandomString(int length) {

        StringBuilder stringBuilder = new StringBuilder(length);
        int index = -1;
        while (stringBuilder.length() < length) {
            index = mRandom.nextInt(TEST_CHAR_SET_AS_STRING.length());
            stringBuilder.append(TEST_CHAR_SET_AS_STRING.charAt(index));
        }
        return stringBuilder.toString();
    }

    /**
     * Verify the SSID checks in validateApWifiConfiguration.
     *
     * Cases to check and verify they trigger failed verification:
     * null WifiConfiguration.SSID
     * empty WifiConfiguration.SSID
     * invalid WifiConfiguaration.SSID length
     *
     * Additionally check a valid SSID with a random (within valid ranges) length.
     */
    @Test
    public void testSsidVerificationInValidateApWifiConfigurationCheck() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = null;
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));
        config.SSID = "";
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));
        // check a string if it's larger than 32 bytes with UTF-8 encode
        // Case 1 : one byte per character (use english words and Arabic numerals)
        config.SSID = generateRandomString(WifiApConfigStore.SSID_MAX_LEN + 1);
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));
        // Case 2 : two bytes per character
        config.SSID = TEST_STRING_UTF8_WITH_34_BYTES;
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));
        // Case 3 : three bytes per character
        config.SSID = TEST_STRING_UTF8_WITH_33_BYTES;
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));

        // now check a valid SSID within 32 bytes
        // Case 1 :  one byte per character with random length
        int validLength = WifiApConfigStore.SSID_MAX_LEN - WifiApConfigStore.SSID_MIN_LEN;
        config.SSID = generateRandomString(
                mRandom.nextInt(validLength) + WifiApConfigStore.SSID_MIN_LEN);
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
        // Case 2 : two bytes per character
        config.SSID = TEST_STRING_UTF8_WITH_32_BYTES;
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
        // Case 3 : three bytes per character
        config.SSID = TEST_STRING_UTF8_WITH_30_BYTES;
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
    }

    /**
     * Verify the Open network checks in validateApWifiConfiguration.
     *
     * If the configured network is open, it should not have a password set.
     *
     * Additionally verify a valid open network passes verification.
     */
    @Test
    public void testOpenNetworkConfigInValidateApWifiConfigurationCheck() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = TEST_DEFAULT_HOTSPOT_SSID;

        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.preSharedKey = TEST_DEFAULT_HOTSPOT_PSK;
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));

        // open networks should not have a password set
        config.preSharedKey = null;
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
        config.preSharedKey = "";
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
    }

    /**
     * Verify the WPA2_PSK network checks in validateApWifiConfiguration.
     *
     * If the configured network is configured with a preSharedKey, verify that the passwork is set
     * and it meets length requirements.
     */
    @Test
    public void testWpa2PskNetworkConfigInValidateApWifiConfigurationCheck() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = TEST_DEFAULT_HOTSPOT_SSID;

        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK);
        config.preSharedKey = null;
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));
        config.preSharedKey = "";
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));

        // test too short
        config.preSharedKey =
                generateRandomString(WifiApConfigStore.PSK_MIN_LEN - 1);
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));

        // test too long
        config.preSharedKey =
                generateRandomString(WifiApConfigStore.PSK_MAX_LEN + 1);
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));

        // explicitly test min length
        config.preSharedKey =
            generateRandomString(WifiApConfigStore.PSK_MIN_LEN);
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));

        // explicitly test max length
        config.preSharedKey =
                generateRandomString(WifiApConfigStore.PSK_MAX_LEN);
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));

        // test random (valid length)
        int maxLen = WifiApConfigStore.PSK_MAX_LEN;
        int minLen = WifiApConfigStore.PSK_MIN_LEN;
        config.preSharedKey =
                generateRandomString(mRandom.nextInt(maxLen - minLen) + minLen);
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
    }

    /**
     * Verify an invalid AuthType setting (that would trigger an IllegalStateException)
     * returns false when triggered in the validateApWifiConfiguration.
     */
    @Test
    public void testInvalidAuthTypeInValidateApWifiConfigurationCheck() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = TEST_DEFAULT_HOTSPOT_SSID;

        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));
    }

    /**
     * Verify an unsupported authType returns false for validateApWifiConfigurationCheck.
     */
    @Test
    public void testUnsupportedAuthTypeInValidateApWifiConfigurationCheck() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = TEST_DEFAULT_HOTSPOT_SSID;

        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(config));
    }

    /**
     * Verify the default 2GHz channel list is properly returned.
     */
    @Test
    public void testDefault2GHzChannelListReturned() {
        // first build known good list
        WifiApConfigStore store = createWifiApConfigStore();
        ArrayList<Integer> channels = store.getAllowed2GChannel();

        assertEquals(mKnownGood2GChannelList.size(), channels.size());
        for (int channel : channels) {
            assertTrue(mKnownGood2GChannelList.contains(channel));
        }
    }
}
