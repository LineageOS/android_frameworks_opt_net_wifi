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
import android.net.wifi.SoftApConfiguration;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.wifi.util.ApConfigUtil;
import com.android.server.wifi.util.WifiConfigStoreEncryptionUtil;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

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
    private static final String XML_TAG_SECURITY_TYPE = "SecurityType";
    private static final String XML_TAG_WPA2_PASSPHRASE = "Wpa2Passphrase";
    private static final String XML_TAG_AP_BAND = "ApBand";

    private final DataSource mDataSource;

    /**
     * Interface define the data source for the notifier store data.
     */
    public interface DataSource {
        /**
         * Retrieve the SoftAp configuration from the data source to serialize them to disk.
         *
         * @return {@link SoftApConfiguration} Instance of SoftApConfiguration.
         */
        SoftApConfiguration toSerialize();

        /**
         * Set the SoftAp configuration in the data source after serializing them from disk.
         *
         * @param config {@link SoftApConfiguration} Instance of SoftApConfiguration.
         */
        void fromDeserialized(SoftApConfiguration config);

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
        SoftApConfiguration softApConfig = mDataSource.toSerialize();
        if (softApConfig != null) {
            XmlUtil.writeNextValue(out, XML_TAG_SSID, softApConfig.getSsid());
            XmlUtil.writeNextValue(out, XML_TAG_AP_BAND, softApConfig.getBand());
            XmlUtil.writeNextValue(out, XML_TAG_CHANNEL, softApConfig.getChannel());
            XmlUtil.writeNextValue(out, XML_TAG_HIDDEN_SSID, softApConfig.isHiddenSsid());
            XmlUtil.writeNextValue(out, XML_TAG_SECURITY_TYPE, softApConfig.getSecurityType());
            if (softApConfig.getSecurityType() == SoftApConfiguration.SECURITY_TYPE_WPA2_PSK) {
                XmlUtil.writeNextValue(out, XML_TAG_WPA2_PASSPHRASE,
                        softApConfig.getWpa2Passphrase());
            }
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
        SoftApConfiguration.Builder softApConfigBuilder = new SoftApConfiguration.Builder();
        int securityType = SoftApConfiguration.SECURITY_TYPE_OPEN;
        String wpa2Passphrase = null;
        String ssid = null;
        // Note that, during deserializaion, we may read the old band encoding (XML_TAG_BAND)
        // or the new band encoding (XML_TAG_AP_BAND) that is used after the introduction of the
        // 6GHz band. If the old encoding is found, a conversion is done.
        int channel = -1;
        int apBand = -1;
        try {
            while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
                String[] valueName = new String[1];
                Object value = XmlUtil.readCurrentValue(in, valueName);
                if (TextUtils.isEmpty(valueName[0])) {
                    throw new XmlPullParserException("Missing value name");
                }
                switch (valueName[0]) {
                    case XML_TAG_SSID:
                        ssid = (String) value;
                        softApConfigBuilder.setSsid((String) value);
                        break;
                    case XML_TAG_BAND:
                        apBand = ApConfigUtil.convertWifiConfigBandToSoftApConfigBand((int) value);
                        break;
                    case XML_TAG_AP_BAND:
                        apBand = (int) value;
                        break;
                    case XML_TAG_CHANNEL:
                        channel = (int) value;
                        break;
                    case XML_TAG_HIDDEN_SSID:
                        softApConfigBuilder.setHiddenSsid((boolean) value);
                        break;
                    case XML_TAG_SECURITY_TYPE:
                        securityType = (int) value;
                        break;
                    case XML_TAG_WPA2_PASSPHRASE:
                        wpa2Passphrase = (String) value;
                        break;
                    default:
                        Log.w(TAG, "Ignoring unknown value name " + valueName[0]);
                        break;
                }
            }

            // Set channel and band
            if (channel == 0) {
                softApConfigBuilder.setBand(apBand);
            } else {
                softApConfigBuilder.setChannel(channel, apBand);
            }

            // We should at-least have SSID restored from store.
            if (ssid == null) {
                Log.e(TAG, "Failed to parse SSID");
                return;
            }
            if (securityType == SoftApConfiguration.SECURITY_TYPE_WPA2_PSK) {
                softApConfigBuilder.setWpa2Passphrase(wpa2Passphrase);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to parse configuration" + e);
            return;
        }
        mDataSource.fromDeserialized(softApConfigBuilder.setSsid(ssid).build());
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
