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

import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.util.BackupUtils;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Class used to backup/restore data using the SettingsBackupAgent.
 * There are 2 symmetric API's exposed here:
 * 1. retrieveBackupDataFromSoftApConfiguration: Retrieve the configuration data to be backed up.
 * 2. retrieveSoftApConfigurationFromBackupData: Restore the configuration using the provided data.
 * The byte stream to be backed up is versioned to migrate the data easily across
 * revisions.
 */
public class SoftApBackupRestore {
    private static final String TAG = "SoftApBackupRestore";

    /**
     * Current backup data version.
     */
    private static final int CURRENT_SAP_BACKUP_DATA_VERSION = 4;

    public SoftApBackupRestore() {
    }

    /**
     * Retrieve a byte stream representing the data that needs to be backed up from the
     * provided softap configuration.
     *
     * @param config saved soft ap config that needs to be backed up.
     * @return Raw byte stream that needs to be backed up.
     */
    public byte[] retrieveBackupDataFromSoftApConfiguration(SoftApConfiguration config) {
        if (config == null) {
            Log.e(TAG, "Invalid configuration received");
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(baos);

            out.writeInt(CURRENT_SAP_BACKUP_DATA_VERSION);
            BackupUtils.writeString(out, config.getSsid());
            out.writeInt(config.getBand());
            out.writeInt(config.getChannel());
            BackupUtils.writeString(out, config.getWpa2Passphrase());
            out.writeInt(config.getSecurityType());
            out.writeBoolean(config.isHiddenSsid());
        } catch (IOException io) {
            Log.e(TAG, "Invalid configuration received, IOException " + io);
            return new byte[0];
        }
        return baos.toByteArray();
    }

    /**
     * Parse out the configurations from the back up data.
     *
     * @param data raw byte stream representing the data.
     * @return Soft ap config retrieved from the backed up data.
     */
    public SoftApConfiguration retrieveSoftApConfigurationFromBackupData(byte[] data) {
        if (data == null || data.length == 0) {
            Log.e(TAG, "Invalid backup data received");
            return null;
        }
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int version = in.readInt();
            if (version < 1 || version > CURRENT_SAP_BACKUP_DATA_VERSION) {
                throw new BackupUtils.BadVersionException("Unknown Backup Serialization Version");
            }

            if (version == 1) return null; // Version 1 is a bad dataset.

            configBuilder.setSsid(BackupUtils.readString(in));
            configBuilder.setBand(in.readInt());
            configBuilder.setChannel(in.readInt());
            String wpa2Passphrase = BackupUtils.readString(in);
            int securityType = in.readInt();
            if ((version < 4 && securityType == WifiConfiguration.KeyMgmt.WPA2_PSK) || (
                    version >= 4 && securityType == SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)) {
                configBuilder.setWpa2Passphrase(wpa2Passphrase);
            }
            if (version >= 3) {
                configBuilder.setHiddenSsid(in.readBoolean());
            }
        } catch (IOException io) {
            Log.e(TAG, "Invalid backup data received, IOException: " + io);
            return null;
        } catch (BackupUtils.BadVersionException badVersion) {
            Log.e(TAG, "Invalid backup data received, BadVersionException: " + badVersion);
            return null;
        }
        return configBuilder.build();
    }
}
