/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.util.Xml;

import androidx.test.filters.SmallTest;

import com.android.internal.util.FastXmlSerializer;

import org.junit.Before;
import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link com.android.server.wifi.DeletedEphemeralSsidsStoreData}.
 */
@SmallTest
public class DeletedEphemeralSsidsStoreDataTest {
    private static final String TEST_SSID1 = "SSID 1";
    private static final String TEST_SSID2 = "SSID 2";
    private static final String TEST_SSID_LIST_XML_STRING =
            "<set name=\"SSIDList\">\n"
            + "<string>" + TEST_SSID1 + "</string>\n"
            + "<string>" + TEST_SSID2 + "</string>\n"
            + "</set>\n";
    private static final byte[] TEST_SSID_LIST_XML_BYTES =
            TEST_SSID_LIST_XML_STRING.getBytes(StandardCharsets.UTF_8);
    private DeletedEphemeralSsidsStoreData mDeletedEphemeralSsidsStoreData;

    @Before
    public void setUp() throws Exception {
        mDeletedEphemeralSsidsStoreData = new DeletedEphemeralSsidsStoreData();
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
        mDeletedEphemeralSsidsStoreData.serializeData(out);
        out.flush();
        return outputStream.toByteArray();
    }

    /**
     * Helper function for parsing configuration data from a XML block.
     *
     * @param data XML data to parse from
     * @return SSID list
     * @throws Exception
     */
    private Set<String> deserializeData(byte[] data) throws Exception {
        final XmlPullParser in = Xml.newPullParser();
        final ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
        in.setInput(inputStream, StandardCharsets.UTF_8.name());
        mDeletedEphemeralSsidsStoreData.deserializeData(in, in.getDepth());
        return mDeletedEphemeralSsidsStoreData.getSsidList();
    }

    /**
     * Verify that serializing the user store data without any configuration doesn't cause any
     * crash and no data should be serialized.
     *
     * @throws Exception
     */
    @Test
    public void serializeEmptyConfigs() throws Exception {
        assertEquals(0, serializeData().length);
    }

    /**
     * Verify that parsing an empty data doesn't cause any crash and no configuration should
     * be deserialized.
     *
     * @throws Exception
     */
    @Test
    public void deserializeEmptyData() throws Exception {
        assertTrue(deserializeData(new byte[0]).isEmpty());
    }

    /**
     * Verify that DeletedEphemeralSsidsStoreData is written to
     * {@link WifiConfigStore#STORE_FILE_NAME_USER_GENERAL}.
     *
     * @throws Exception
     */
    @Test
    public void getUserStoreFileId() throws Exception {
        assertEquals(WifiConfigStore.STORE_FILE_USER_GENERAL,
                mDeletedEphemeralSsidsStoreData.getStoreFileId());
    }

    /**
     * Verify that user store SSID list is serialized correctly, matches the predefined test
     * XML data.
     *
     * @throws Exception
     */
    @Test
    public void serializeSsidList() throws Exception {
        Set<String> ssidList = new HashSet<>();
        ssidList.add(TEST_SSID1);
        ssidList.add(TEST_SSID2);
        mDeletedEphemeralSsidsStoreData.setSsidList(ssidList);
        byte[] actualData = serializeData();
        assertTrue(Arrays.equals(TEST_SSID_LIST_XML_BYTES, actualData));
    }

    /**
     * Verify that user store SSID list is deserialized correctly using the predefined test XML
     * data.
     *
     * @throws Exception
     */
    @Test
    public void deserializeSsidList() throws Exception {
        Set<String> ssidList = new HashSet<>();
        ssidList.add(TEST_SSID1);
        ssidList.add(TEST_SSID2);
        assertEquals(ssidList, deserializeData(TEST_SSID_LIST_XML_BYTES));
    }
}
