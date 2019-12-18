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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.util.BackupUtils;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.util.ApConfigUtil;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Unit tests for {@link com.android.server.wifi.SoftApBackupRestore}.
 */
@SmallTest
public class SoftApBackupRestoreTest extends WifiBaseTest {

    private SoftApBackupRestore mSoftApBackupRestore;
    private static final int LAST_WIFICOFIGURATION_BACKUP_VERSION = 3;

    @Before
    public void setUp() throws Exception {
        mSoftApBackupRestore = new SoftApBackupRestore();
    }

    /**
     * Copy from WifiConfiguration for test backup/restore is backward compatible.
     */
    private byte[] getBytesForBackup(WifiConfiguration wificonfig) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeInt(LAST_WIFICOFIGURATION_BACKUP_VERSION);
        BackupUtils.writeString(out, wificonfig.SSID);
        out.writeInt(wificonfig.apBand);
        out.writeInt(wificonfig.apChannel);
        BackupUtils.writeString(out, wificonfig.preSharedKey);
        out.writeInt(wificonfig.getAuthType());
        out.writeBoolean(wificonfig.hiddenSSID);
        return baos.toByteArray();
    }

    /**
     * Verifies that the serialization/de-serialization for wpa2 softap config works.
     */
    @Test
    public void testSoftApConfigBackupAndRestoreWithWpa2Config() throws Exception {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setSsid("TestAP");
        configBuilder.setChannel(40, SoftApConfiguration.BAND_5GHZ);
        configBuilder.setWpa2Passphrase("TestPsk");
        configBuilder.setHiddenSsid(true);
        SoftApConfiguration config = configBuilder.build();

        byte[] data = mSoftApBackupRestore.retrieveBackupDataFromSoftApConfiguration(config);
        SoftApConfiguration restoredConfig =
                mSoftApBackupRestore.retrieveSoftApConfigurationFromBackupData(data);

        assertThat(config).isEqualTo(restoredConfig);
    }

    /**
     * Verifies that the serialization/de-serialization for open security softap config works.
     */
    @Test
    public void testSoftApConfigBackupAndRestoreWithOpenSecurityConfig() throws Exception {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setSsid("TestAP");
        configBuilder.setChannel(12, SoftApConfiguration.BAND_2GHZ);
        configBuilder.setHiddenSsid(false);
        SoftApConfiguration config = configBuilder.build();

        byte[] data = mSoftApBackupRestore.retrieveBackupDataFromSoftApConfiguration(config);
        SoftApConfiguration restoredConfig =
                mSoftApBackupRestore.retrieveSoftApConfigurationFromBackupData(data);

        assertThat(config).isEqualTo(restoredConfig);
    }

    /**
     * Verifies that the serialization/de-serialization for old softap config works.
     */
    @Test
    public void testSoftApConfigBackupAndRestoreWithOldConfig() throws Exception {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.SSID = "TestAP";
        wifiConfig.apBand = WifiConfiguration.AP_BAND_2GHZ;
        wifiConfig.apChannel = 12;
        wifiConfig.hiddenSSID = true;
        wifiConfig.preSharedKey = "test_pwd";
        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK);
        byte[] data = getBytesForBackup(wifiConfig);
        SoftApConfiguration restoredConfig =
                mSoftApBackupRestore.retrieveSoftApConfigurationFromBackupData(data);

        assertThat(ApConfigUtil.fromWifiConfiguration(wifiConfig)).isEqualTo(restoredConfig);
    }
}
