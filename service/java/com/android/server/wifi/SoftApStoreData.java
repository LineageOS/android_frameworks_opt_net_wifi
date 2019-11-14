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

import android.annotation.Nullable;
import android.net.wifi.WifiConfiguration;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.wifi.util.WifiConfigStoreEncryptionUtil;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.BitSet;

/**
 * Store data for SoftAp
 */
public class SoftApStoreData implements WifiConfigStore.StoreData {
    private static final String TAG = "SoftApStoreData";
    private static final String XML_TAG_SECTION_HEADER_SOFTAP = "SoftAp";
    private static final String XML_TAG_SSID = "SSID";
    private static final String XML_TAG_BAND = "Band";
    private static final String XML_TAG_CHANNEL = "Channel";
    private static final String XML_TAG_HIDDEN_SSID = "HiddenSSID";
    private static final String XML_TAG_ALLOWED_KEY_MGMT = "AllowedKeyMgmt";
    private static final String XML_TAG_PRE_SHARED_KEY = "PreSharedKey";

    private final DataSource mDataSource;

    /**
     * Interface define the data source for the notifier store data.
     */
    public interface DataSource {
        /**
         * Retrieve the SoftAp configuration from the data source to serialize them to disk.
         *
         * @return {@link WifiConfiguration} Instance of WifiConfiguration.
         */
        WifiConfiguration toSerialize();

        /**
         * Set the SoftAp configuration in the data source after serializing them from disk.
         *
         * @param config {@link WifiConfiguration} Instance of WifiConfiguration.
         */
        void fromDeserialized(WifiConfiguration config);

        /**
         * Clear internal data structure in preparation for user switch or initial store read.
         */
        void reset();

        /**
         * Indicates whether there is new data to serialize.
         */
        boolean hasNewDataToSerialize();
    }

    /**
     * Creates the SSID Set store data.
     *
     * @param dataSource The DataSource that implements the update and retrieval of the SSID set.
     */
    SoftApStoreData(DataSource dataSource) {
        mDataSource = dataSource;
    }

    @Override
    public void serializeData(XmlSerializer out,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        WifiConfiguration softApConfig = mDataSource.toSerialize();
        if (softApConfig != null) {
            XmlUtil.writeNextValue(out, XML_TAG_SSID, softApConfig.SSID);
            XmlUtil.writeNextValue(out, XML_TAG_BAND, softApConfig.apBand);
            XmlUtil.writeNextValue(out, XML_TAG_CHANNEL, softApConfig.apChannel);
            XmlUtil.writeNextValue(out, XML_TAG_HIDDEN_SSID, softApConfig.hiddenSSID);
            XmlUtil.writeNextValue(
                    out, XML_TAG_ALLOWED_KEY_MGMT,
                    softApConfig.allowedKeyManagement.toByteArray());
            XmlUtil.writeNextValue(out, XML_TAG_PRE_SHARED_KEY, softApConfig.preSharedKey);
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
        WifiConfiguration softApConfig = new WifiConfiguration();
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (TextUtils.isEmpty(valueName[0])) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_SSID:
                    softApConfig.SSID = (String) value;
                    break;
                case XML_TAG_BAND:
                    softApConfig.apBand = (int) value;
                    break;
                case XML_TAG_CHANNEL:
                    softApConfig.apChannel = (int) value;
                    break;
                case XML_TAG_HIDDEN_SSID:
                    softApConfig.hiddenSSID = (boolean) value;
                    break;
                case XML_TAG_ALLOWED_KEY_MGMT:
                    byte[] allowedKeyMgmt = (byte[]) value;
                    softApConfig.allowedKeyManagement = BitSet.valueOf(allowedKeyMgmt);
                    break;
                case XML_TAG_PRE_SHARED_KEY:
                    softApConfig.preSharedKey = (String) value;
                    break;
                default:
                    Log.w(TAG, "Ignoring unknown value name " + valueName[0]);
                    break;
            }
        }
        // We should at-least have SSID restored from store.
        if (softApConfig.SSID == null) {
            Log.e(TAG, "Failed to parse SSID");
            return;
        }
        mDataSource.fromDeserialized(softApConfig);
    }

    @Override
    public void resetData() {
        mDataSource.reset();
    }

    @Override
    public boolean hasNewDataToSerialize() {
        return mDataSource.hasNewDataToSerialize();
    }

    @Override
    public String getName() {
        return XML_TAG_SECTION_HEADER_SOFTAP;
    }

    @Override
    public @WifiConfigStore.StoreFileId int getStoreFileId() {
        return WifiConfigStore.STORE_FILE_SHARED_SOFTAP; // Shared softap store.
    }
}
