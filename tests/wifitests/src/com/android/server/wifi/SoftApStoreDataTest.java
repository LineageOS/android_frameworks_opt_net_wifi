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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.wifi.WifiConfiguration;
import android.util.Xml;

import androidx.test.filters.SmallTest;

import com.android.internal.util.FastXmlSerializer;
import com.android.server.wifi.util.WifiConfigStoreEncryptionUtil;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for {@link com.android.server.wifi.SoftApStoreData}.
 */
@SmallTest
public class SoftApStoreDataTest extends WifiBaseTest {
    private static final String TEST_SSID = "SSID";
    private static final String TEST_PRESHARED_KEY = "Test";
    private static final boolean TEST_HIDDEN = false;
    private static final int TEST_BAND = WifiConfiguration.AP_BAND_ANY;
    private static final int TEST_CHANNEL = 0;

    private static final String TEST_SOFTAP_CONFIG_XML_STRING =
            "<string name=\"SSID\">" + TEST_SSID + "</string>\n"
                    + "<int name=\"Band\" value=\"" + TEST_BAND + "\" />\n"
                    + "<int name=\"Channel\" value=\"" + TEST_CHANNEL + "\" />\n"
                    + "<boolean name=\"HiddenSSID\" value=\"" + TEST_HIDDEN + "\" />\n"
                    + "<byte-array name=\"AllowedKeyMgmt\" num=\"1\">02</byte-array>\n"
                    + "<string name=\"PreSharedKey\">" + TEST_PRESHARED_KEY + "</string>\n";

    @Mock SoftApStoreData.DataSource mDataSource;
    SoftApStoreData mSoftApStoreData;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSoftApStoreData = new SoftApStoreData(mDataSource);
    }

    /**
     * Helper function for serializing configuration data to a XML block.
     *
     * @return byte[] of the XML data
     * @throws Exception
     */
    private byte[] serializeData() throws Exception {
        final XmlSerializer out = new FastXmlSerializer();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        out.setOutput(outputStream, StandardCharsets.UTF_8.name());
        mSoftApStoreData.serializeData(out, mock(WifiConfigStoreEncryptionUtil.class));
        out.flush();
        return outputStream.toByteArray();
    }

    /**
     * Helper function for parsing configuration data from a XML block.
     *
     * @param data XML data to parse from
     * @throws Exception
     */
    private void deserializeData(byte[] data) throws Exception {
        final XmlPullParser in = Xml.newPullParser();
        final ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
        in.setInput(inputStream, StandardCharsets.UTF_8.name());
        mSoftApStoreData.deserializeData(in, in.getDepth(),
                WifiConfigStore.ENCRYPT_CREDENTIALS_CONFIG_STORE_DATA_VERSION,
                mock(WifiConfigStoreEncryptionUtil.class));
    }

    /**
     * Verify that parsing an empty data doesn't cause any crash and no configuration should
     * be deserialized.
     *
     * @throws Exception
     */
    @Test
    public void deserializeEmptyStoreData() throws Exception {
        deserializeData(new byte[0]);
        verify(mDataSource, never()).fromDeserialized(any());
    }

    /**
     * Verify that SoftApStoreData is written to
     * {@link WifiConfigStore#STORE_FILE_SHARED_SOFTAP}.
     *
     * @throws Exception
     */
    @Test
    public void getUserStoreFileId() throws Exception {
        assertEquals(WifiConfigStore.STORE_FILE_SHARED_SOFTAP,
                mSoftApStoreData.getStoreFileId());
    }

    /**
     * Verify that the store data is serialized correctly, matches the predefined test XML data.
     *
     * @throws Exception
     */
    @Test
    public void serializeSoftAp() throws Exception {
        WifiConfiguration softApConfig = new WifiConfiguration();
        softApConfig.SSID = TEST_SSID;
        softApConfig.preSharedKey = TEST_PRESHARED_KEY;
        softApConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        softApConfig.apBand = TEST_BAND;
        softApConfig.apChannel = TEST_CHANNEL;

        when(mDataSource.toSerialize()).thenReturn(softApConfig);
        byte[] actualData = serializeData();
        assertEquals(TEST_SOFTAP_CONFIG_XML_STRING, new String(actualData));
    }

    /**
     * Verify that the store data is deserialized correctly using the predefined test XML data.
     *
     * @throws Exception
     */
    @Test
    public void deserializeSoftAp() throws Exception {
        deserializeData(TEST_SOFTAP_CONFIG_XML_STRING.getBytes());

        ArgumentCaptor<WifiConfiguration> softapConfigCaptor =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mDataSource).fromDeserialized(softapConfigCaptor.capture());
        WifiConfiguration softApConfig = softapConfigCaptor.getValue();
        assertNotNull(softApConfig);
        assertEquals(softApConfig.SSID, TEST_SSID);
        assertEquals(softApConfig.preSharedKey, TEST_PRESHARED_KEY);
        assertTrue(softApConfig.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK));
        assertEquals(softApConfig.hiddenSSID, TEST_HIDDEN);
        assertEquals(softApConfig.apBand, TEST_BAND);
        assertEquals(softApConfig.apChannel, TEST_CHANNEL);
    }

    /**
     * Verify that the store data is serialized/deserialized correctly.
     *
     * @throws Exception
     */
    @Test
    public void serializeDeserializeSoftAp() throws Exception {
        WifiConfiguration softApConfig = new WifiConfiguration();
        softApConfig.SSID = TEST_SSID;
        softApConfig.preSharedKey = TEST_PRESHARED_KEY;
        softApConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        softApConfig.apBand = TEST_BAND;
        softApConfig.apChannel = TEST_CHANNEL;

        // Serialize first.
        when(mDataSource.toSerialize()).thenReturn(softApConfig);
        byte[] serializedData = serializeData();

        // Now deserialize first.
        deserializeData(serializedData);
        ArgumentCaptor<WifiConfiguration> softapConfigCaptor =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mDataSource).fromDeserialized(softapConfigCaptor.capture());
        WifiConfiguration softApConfigDeserialized = softapConfigCaptor.getValue();
        assertNotNull(softApConfigDeserialized);

        assertEquals(softApConfig.SSID, softApConfigDeserialized.SSID);
        assertEquals(softApConfig.preSharedKey, softApConfigDeserialized.preSharedKey);
        assertEquals(softApConfig.allowedKeyManagement,
                softApConfigDeserialized.allowedKeyManagement);
        assertEquals(softApConfig.hiddenSSID, softApConfigDeserialized.hiddenSSID);
        assertEquals(softApConfig.apBand, softApConfigDeserialized.apBand);
        assertEquals(softApConfig.apChannel, softApConfigDeserialized.apChannel);
    }
}
