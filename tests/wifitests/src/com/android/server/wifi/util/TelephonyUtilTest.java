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

package com.android.server.wifi.util;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.telephony.ImsiEncryptionInfo;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.wifi.WifiBaseTest;
import com.android.server.wifi.WifiConfigurationTestUtil;
import com.android.server.wifi.util.TelephonyUtil.SimAuthRequestData;
import com.android.server.wifi.util.TelephonyUtil.SimAuthResponseData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;

/**
 * Unit tests for {@link com.android.server.wifi.util.TelephonyUtil}.
 */
@SmallTest
public class TelephonyUtilTest extends WifiBaseTest {
    private TelephonyUtil mTelephonyUtil;

    private static final int DATA_SUBID = 1;
    private static final int NON_DATA_SUBID = 2;
    private static final int INVALID_SUBID = -1;
    private static final int DATA_CARRIER_ID = 10;
    private static final int NON_DATA_CARRIER_ID = 20;
    private static final int DEACTIVE_CARRIER_ID = 30;

    private List<SubscriptionInfo> mSubInfoList;

    MockitoSession mMockingSession = null;

    @Mock
    TelephonyManager mTelephonyManager;
    @Mock
    TelephonyManager mDataTelephonyManager;
    @Mock
    TelephonyManager mNonDataTelephonyManager;
    @Mock
    SubscriptionManager mSubscriptionManager;
    @Mock
    SubscriptionInfo mDataSubscriptionInfo;
    @Mock
    SubscriptionInfo mNonDataSubscriptionInfo;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTelephonyUtil = new TelephonyUtil(mTelephonyManager, mSubscriptionManager);
        mSubInfoList = new ArrayList<>();
        mSubInfoList.add(mDataSubscriptionInfo);
        mSubInfoList.add(mNonDataSubscriptionInfo);
        when(mTelephonyManager.createForSubscriptionId(eq(DATA_SUBID)))
                .thenReturn(mDataTelephonyManager);
        when(mTelephonyManager.createForSubscriptionId(eq(NON_DATA_SUBID)))
                .thenReturn(mNonDataTelephonyManager);
        when(mSubscriptionManager.getActiveSubscriptionInfoList()).thenReturn(mSubInfoList);
        mMockingSession = ExtendedMockito.mockitoSession().strictness(Strictness.LENIENT)
                .mockStatic(SubscriptionManager.class).startMocking();

        doReturn(DATA_SUBID).when(
                () -> SubscriptionManager.getDefaultDataSubscriptionId());
        doReturn(true).when(
                () -> SubscriptionManager.isValidSubscriptionId(DATA_SUBID));
        doReturn(true).when(
                () -> SubscriptionManager.isValidSubscriptionId(NON_DATA_SUBID));

        when(mDataSubscriptionInfo.getCarrierId()).thenReturn(DATA_CARRIER_ID);
        when(mDataSubscriptionInfo.getSubscriptionId()).thenReturn(DATA_SUBID);
        when(mNonDataSubscriptionInfo.getCarrierId()).thenReturn(NON_DATA_CARRIER_ID);
        when(mNonDataSubscriptionInfo.getSubscriptionId()).thenReturn(NON_DATA_SUBID);
    }

    @After
    public void cleanUp() throws Exception {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void getSimIdentityEapSim() {
        final Pair<String, String> expectedIdentity = Pair.create(
                "13214561234567890@wlan.mnc456.mcc321.3gppnetwork.org", "");

        when(mDataTelephonyManager.getSubscriberId()).thenReturn("3214561234567890");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        when(mDataTelephonyManager.getSimOperator()).thenReturn("321456");
        when(mDataTelephonyManager.getCarrierInfoForImsiEncryption(anyInt())).thenReturn(null);
        WifiConfiguration simConfig =
                WifiConfigurationTestUtil.createEapNetwork(WifiEnterpriseConfig.Eap.SIM,
                        WifiEnterpriseConfig.Phase2.NONE);
        simConfig.carrierId = DATA_CARRIER_ID;

        assertEquals(expectedIdentity, mTelephonyUtil.getSimIdentity(simConfig));

        WifiConfiguration peapSimConfig =
                WifiConfigurationTestUtil.createEapNetwork(WifiEnterpriseConfig.Eap.PEAP,
                        WifiEnterpriseConfig.Phase2.SIM);
        peapSimConfig.carrierId = DATA_CARRIER_ID;

        assertEquals(expectedIdentity, mTelephonyUtil.getSimIdentity(peapSimConfig));
    }

    @Test
    public void getSimIdentityEapAka() {
        final Pair<String, String> expectedIdentity = Pair.create(
                "03214561234567890@wlan.mnc456.mcc321.3gppnetwork.org", "");
        when(mDataTelephonyManager.getSubscriberId()).thenReturn("3214561234567890");

        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        when(mDataTelephonyManager.getSimOperator()).thenReturn("321456");
        when(mDataTelephonyManager.getCarrierInfoForImsiEncryption(anyInt())).thenReturn(null);
        WifiConfiguration akaConfig =
                WifiConfigurationTestUtil.createEapNetwork(WifiEnterpriseConfig.Eap.AKA,
                        WifiEnterpriseConfig.Phase2.NONE);
        akaConfig.carrierId = DATA_CARRIER_ID;

        assertEquals(expectedIdentity, mTelephonyUtil.getSimIdentity(akaConfig));

        WifiConfiguration peapAkaConfig =
                WifiConfigurationTestUtil.createEapNetwork(WifiEnterpriseConfig.Eap.PEAP,
                        WifiEnterpriseConfig.Phase2.AKA);
        peapAkaConfig.carrierId = DATA_CARRIER_ID;

        assertEquals(expectedIdentity, mTelephonyUtil.getSimIdentity(peapAkaConfig));
    }

    @Test
    public void getSimIdentityEapAkaPrime() {
        final Pair<String, String> expectedIdentity = Pair.create(
                "63214561234567890@wlan.mnc456.mcc321.3gppnetwork.org", "");

        when(mDataTelephonyManager.getSubscriberId()).thenReturn("3214561234567890");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        when(mDataTelephonyManager.getSimOperator()).thenReturn("321456");
        when(mDataTelephonyManager.getCarrierInfoForImsiEncryption(anyInt())).thenReturn(null);
        WifiConfiguration akaPConfig =
                WifiConfigurationTestUtil.createEapNetwork(WifiEnterpriseConfig.Eap.AKA_PRIME,
                        WifiEnterpriseConfig.Phase2.NONE);
        akaPConfig.carrierId = DATA_CARRIER_ID;

        assertEquals(expectedIdentity, mTelephonyUtil.getSimIdentity(akaPConfig));

        WifiConfiguration peapAkaPConfig =
                WifiConfigurationTestUtil.createEapNetwork(WifiEnterpriseConfig.Eap.PEAP,
                        WifiEnterpriseConfig.Phase2.AKA_PRIME);
        peapAkaPConfig.carrierId = DATA_CARRIER_ID;

        assertEquals(expectedIdentity, mTelephonyUtil.getSimIdentity(peapAkaPConfig));
    }

    /**
     * Verify that an expected identity is returned when using the encrypted identity
     * encoded by RFC4648.
     */
    @Test
    public void getEncryptedIdentity_WithRfc4648() throws Exception {
        Cipher cipher = mock(Cipher.class);
        PublicKey key = null;
        String imsi = "3214561234567890";
        String permanentIdentity = "03214561234567890@wlan.mnc456.mcc321.3gppnetwork.org";
        String encryptedImsi = Base64.encodeToString(permanentIdentity.getBytes(), 0,
                permanentIdentity.getBytes().length, Base64.NO_WRAP);
        String encryptedIdentity = "\0" + encryptedImsi;
        final Pair<String, String> expectedIdentity = Pair.create(permanentIdentity,
                encryptedIdentity);

        // static mocking
        MockitoSession session = ExtendedMockito.mockitoSession().mockStatic(
                Cipher.class).startMocking();
        try {
            when(Cipher.getInstance(anyString())).thenReturn(cipher);
            when(cipher.doFinal(any(byte[].class))).thenReturn(permanentIdentity.getBytes());
            when(mDataTelephonyManager.getSubscriberId()).thenReturn(imsi);
            when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
            when(mDataTelephonyManager.getSimOperator()).thenReturn("321456");
            ImsiEncryptionInfo info = new ImsiEncryptionInfo("321", "456",
                    TelephonyManager.KEY_TYPE_WLAN, null, key, null);
            when(mDataTelephonyManager.getCarrierInfoForImsiEncryption(
                    eq(TelephonyManager.KEY_TYPE_WLAN)))
                    .thenReturn(info);
            WifiConfiguration config =
                    WifiConfigurationTestUtil.createEapNetwork(WifiEnterpriseConfig.Eap.AKA,
                            WifiEnterpriseConfig.Phase2.NONE);
            config.carrierId = DATA_CARRIER_ID;

            assertEquals(expectedIdentity, mTelephonyUtil.getSimIdentity(config));
        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify that {@code null} will be returned when IMSI encryption failed.
     *
     * @throws Exception
     */
    @Test
    public void getEncryptedIdentityFailed() throws Exception {
        Cipher cipher = mock(Cipher.class);
        String keyIdentifier = "key=testKey";
        String imsi = "3214561234567890";
        // static mocking
        MockitoSession session = ExtendedMockito.mockitoSession().mockStatic(
                Cipher.class).startMocking();
        try {
            when(Cipher.getInstance(anyString())).thenReturn(cipher);
            when(cipher.doFinal(any(byte[].class))).thenThrow(BadPaddingException.class);
            when(mDataTelephonyManager.getSubscriberId()).thenReturn(imsi);
            when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
            when(mDataTelephonyManager.getSimOperator()).thenReturn("321456");
            ImsiEncryptionInfo info = new ImsiEncryptionInfo("321", "456",
                    TelephonyManager.KEY_TYPE_WLAN, keyIdentifier, (PublicKey) null, null);
            when(mDataTelephonyManager.getCarrierInfoForImsiEncryption(
                    eq(TelephonyManager.KEY_TYPE_WLAN)))
                    .thenReturn(info);

            WifiConfiguration config =
                    WifiConfigurationTestUtil.createEapNetwork(WifiEnterpriseConfig.Eap.AKA,
                            WifiEnterpriseConfig.Phase2.NONE);
            config.carrierId = DATA_CARRIER_ID;

            assertNull(mTelephonyUtil.getSimIdentity(config));
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void getSimIdentity2DigitMnc() {
        final Pair<String, String> expectedIdentity = Pair.create(
                "1321560123456789@wlan.mnc056.mcc321.3gppnetwork.org", "");

        when(mDataTelephonyManager.getSubscriberId()).thenReturn("321560123456789");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        when(mDataTelephonyManager.getSimOperator()).thenReturn("32156");
        when(mDataTelephonyManager.getCarrierInfoForImsiEncryption(anyInt())).thenReturn(null);
        WifiConfiguration config =
                WifiConfigurationTestUtil.createEapNetwork(WifiEnterpriseConfig.Eap.SIM,
                        WifiEnterpriseConfig.Phase2.NONE);
        config.carrierId = DATA_CARRIER_ID;

        assertEquals(expectedIdentity, mTelephonyUtil.getSimIdentity(config));
    }

    @Test
    public void getSimIdentityUnknownMccMnc() {
        final Pair<String, String> expectedIdentity = Pair.create(
                "13214560123456789@wlan.mnc456.mcc321.3gppnetwork.org", "");

        when(mDataTelephonyManager.getSubscriberId()).thenReturn("3214560123456789");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_UNKNOWN);
        when(mDataTelephonyManager.getSimOperator()).thenReturn(null);
        when(mDataTelephonyManager.getCarrierInfoForImsiEncryption(anyInt())).thenReturn(null);
        WifiConfiguration config =
                WifiConfigurationTestUtil.createEapNetwork(WifiEnterpriseConfig.Eap.SIM,
                        WifiEnterpriseConfig.Phase2.NONE);
        config.carrierId = DATA_CARRIER_ID;

        assertEquals(expectedIdentity, mTelephonyUtil.getSimIdentity(config));
    }

    @Test
    public void getSimIdentityNonTelephonyConfig() {
        when(mDataTelephonyManager.getSubscriberId()).thenReturn("321560123456789");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        when(mDataTelephonyManager.getSimOperator()).thenReturn("32156");

        assertEquals(null,
                mTelephonyUtil.getSimIdentity(WifiConfigurationTestUtil.createEapNetwork(
                        WifiEnterpriseConfig.Eap.TTLS, WifiEnterpriseConfig.Phase2.SIM)));
        assertEquals(null,
                mTelephonyUtil.getSimIdentity(WifiConfigurationTestUtil.createEapNetwork(
                        WifiEnterpriseConfig.Eap.PEAP, WifiEnterpriseConfig.Phase2.MSCHAPV2)));
        assertEquals(null,
                mTelephonyUtil.getSimIdentity(WifiConfigurationTestUtil.createEapNetwork(
                        WifiEnterpriseConfig.Eap.TLS, WifiEnterpriseConfig.Phase2.NONE)));
        assertEquals(null,
                mTelephonyUtil.getSimIdentity(new WifiConfiguration()));
    }

    /**
     * Produce a base64 encoded length byte + data.
     */
    private static String createSimChallengeRequest(byte[] challengeValue) {
        byte[] challengeLengthAndValue = new byte[challengeValue.length + 1];
        challengeLengthAndValue[0] = (byte) challengeValue.length;
        for (int i = 0; i < challengeValue.length; ++i) {
            challengeLengthAndValue[i + 1] = challengeValue[i];
        }
        return Base64.encodeToString(challengeLengthAndValue, android.util.Base64.NO_WRAP);
    }

    /**
     * Produce a base64 encoded data without length.
     */
    private static String create2gUsimChallengeRequest(byte[] challengeValue) {
        return Base64.encodeToString(challengeValue, android.util.Base64.NO_WRAP);
    }

    /**
     * Produce a base64 encoded sres length byte + sres + kc length byte + kc.
     */
    private static String createGsmSimAuthResponse(byte[] sresValue, byte[] kcValue) {
        int overallLength = sresValue.length + kcValue.length + 2;
        byte[] result = new byte[sresValue.length + kcValue.length + 2];
        int idx = 0;
        result[idx++] = (byte) sresValue.length;
        for (int i = 0; i < sresValue.length; ++i) {
            result[idx++] = sresValue[i];
        }
        result[idx++] = (byte) kcValue.length;
        for (int i = 0; i < kcValue.length; ++i) {
            result[idx++] = kcValue[i];
        }
        return Base64.encodeToString(result, Base64.NO_WRAP);
    }

    /**
     * Produce a base64 encoded sres + kc without length.
     */
    private static String create2gUsimAuthResponse(byte[] sresValue, byte[] kcValue) {
        int overallLength = sresValue.length + kcValue.length;
        byte[] result = new byte[sresValue.length + kcValue.length];
        int idx = 0;
        for (int i = 0; i < sresValue.length; ++i) {
            result[idx++] = sresValue[i];
        }
        for (int i = 0; i < kcValue.length; ++i) {
            result[idx++] = kcValue[i];
        }
        return Base64.encodeToString(result, Base64.NO_WRAP);
    }

    @Test
    public void getGsmSimAuthResponseInvalidRequest() {
        final String[] invalidRequests = { null, "", "XXXX" };
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals("", mTelephonyUtil.getGsmSimAuthResponse(invalidRequests, config));
    }

    @Test
    public void getGsmSimAuthResponseFailedSimResponse() {
        final String[] failedRequests = { "5E5F" };
        when(mDataTelephonyManager.getIccAuthentication(anyInt(), anyInt(),
                eq(createSimChallengeRequest(new byte[] { 0x5e, 0x5f })))).thenReturn(null);
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals(null, mTelephonyUtil.getGsmSimAuthResponse(failedRequests, config));
    }

    @Test
    public void getGsmSimAuthResponseUsim() {
        when(mDataTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                        TelephonyManager.AUTHTYPE_EAP_SIM,
                        createSimChallengeRequest(new byte[] { 0x1b, 0x2b })))
                .thenReturn(createGsmSimAuthResponse(new byte[] { 0x1D, 0x2C },
                                new byte[] { 0x3B, 0x4A }));
        when(mDataTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                        TelephonyManager.AUTHTYPE_EAP_SIM,
                        createSimChallengeRequest(new byte[] { 0x01, 0x22 })))
                .thenReturn(createGsmSimAuthResponse(new byte[] { 0x11, 0x11 },
                                new byte[] { 0x12, 0x34 }));
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals(":3b4a:1d2c:1234:1111", mTelephonyUtil.getGsmSimAuthResponse(
                        new String[] { "1B2B", "0122" }, config));
    }

    @Test
    public void getGsmSimpleSimAuthResponseInvalidRequest() {
        final String[] invalidRequests = { null, "", "XXXX" };
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals("",
                mTelephonyUtil.getGsmSimpleSimAuthResponse(invalidRequests, config));
    }

    @Test
    public void getGsmSimpleSimAuthResponseFailedSimResponse() {
        final String[] failedRequests = { "5E5F" };
        when(mDataTelephonyManager.getIccAuthentication(anyInt(), anyInt(),
                eq(createSimChallengeRequest(new byte[] { 0x5e, 0x5f })))).thenReturn(null);
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals(null,
                mTelephonyUtil.getGsmSimpleSimAuthResponse(failedRequests, config));
    }

    @Test
    public void getGsmSimpleSimAuthResponse() {
        when(mDataTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_SIM,
                        TelephonyManager.AUTHTYPE_EAP_SIM,
                        createSimChallengeRequest(new byte[] { 0x1a, 0x2b })))
                .thenReturn(createGsmSimAuthResponse(new byte[] { 0x1D, 0x2C },
                                new byte[] { 0x3B, 0x4A }));
        when(mDataTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_SIM,
                        TelephonyManager.AUTHTYPE_EAP_SIM,
                        createSimChallengeRequest(new byte[] { 0x01, 0x23 })))
                .thenReturn(createGsmSimAuthResponse(new byte[] { 0x33, 0x22 },
                                new byte[] { 0x11, 0x00 }));
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals(":3b4a:1d2c:1100:3322", mTelephonyUtil.getGsmSimpleSimAuthResponse(
                        new String[] { "1A2B", "0123" }, config));
    }

    @Test
    public void getGsmSimpleSimNoLengthAuthResponseInvalidRequest() {
        final String[] invalidRequests = { null, "", "XXXX" };
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals("", mTelephonyUtil.getGsmSimpleSimNoLengthAuthResponse(invalidRequests,
                config));
    }

    @Test
    public void getGsmSimpleSimNoLengthAuthResponseFailedSimResponse() {
        final String[] failedRequests = { "5E5F" };
        when(mDataTelephonyManager.getIccAuthentication(anyInt(), anyInt(),
                eq(create2gUsimChallengeRequest(new byte[] { 0x5e, 0x5f })))).thenReturn(null);
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals(null, mTelephonyUtil.getGsmSimpleSimNoLengthAuthResponse(failedRequests,
                config));
    }

    @Test
    public void getGsmSimpleSimNoLengthAuthResponse() {
        when(mDataTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_SIM,
                        TelephonyManager.AUTHTYPE_EAP_SIM,
                        create2gUsimChallengeRequest(new byte[] { 0x1a, 0x2b })))
                .thenReturn(create2gUsimAuthResponse(new byte[] { 0x1a, 0x2b, 0x3c, 0x4d },
                                new byte[] { 0x1a, 0x2b, 0x3c, 0x4d, 0x5e, 0x6f, 0x7a, 0x1a }));
        when(mDataTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_SIM,
                        TelephonyManager.AUTHTYPE_EAP_SIM,
                        create2gUsimChallengeRequest(new byte[] { 0x01, 0x23 })))
                .thenReturn(create2gUsimAuthResponse(new byte[] { 0x12, 0x34, 0x56, 0x78 },
                                new byte[] { 0x12, 0x34, 0x56, 0x78, 0x12, 0x34, 0x56, 0x78 }));
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals(":1a2b3c4d5e6f7a1a:1a2b3c4d:1234567812345678:12345678",
                mTelephonyUtil.getGsmSimpleSimNoLengthAuthResponse(
                        new String[] { "1A2B", "0123" }, config));
    }

    /**
     * Produce a base64 encoded tag + res length byte + res + ck length byte + ck + ik length byte +
     * ik.
     */
    private static String create3GSimAuthUmtsAuthResponse(byte[] res, byte[] ck, byte[] ik) {
        byte[] result = new byte[res.length + ck.length + ik.length + 4];
        int idx = 0;
        result[idx++] = (byte) 0xdb;
        result[idx++] = (byte) res.length;
        for (int i = 0; i < res.length; ++i) {
            result[idx++] = res[i];
        }
        result[idx++] = (byte) ck.length;
        for (int i = 0; i < ck.length; ++i) {
            result[idx++] = ck[i];
        }
        result[idx++] = (byte) ik.length;
        for (int i = 0; i < ik.length; ++i) {
            result[idx++] = ik[i];
        }
        return Base64.encodeToString(result, Base64.NO_WRAP);
    }

    private static String create3GSimAuthUmtsAutsResponse(byte[] auts) {
        byte[] result = new byte[auts.length + 2];
        int idx = 0;
        result[idx++] = (byte) 0xdc;
        result[idx++] = (byte) auts.length;
        for (int i = 0; i < auts.length; ++i) {
            result[idx++] = auts[i];
        }
        return Base64.encodeToString(result, Base64.NO_WRAP);
    }

    @Test
    public void get3GAuthResponseInvalidRequest() {
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals(null, mTelephonyUtil.get3GAuthResponse(
                new SimAuthRequestData(0, 0, "SSID", new String[]{"0123"}), config));
        assertEquals(null, mTelephonyUtil.get3GAuthResponse(
                new SimAuthRequestData(0, 0, "SSID", new String[]{"xyz2", "1234"}),
                config));
        verifyNoMoreInteractions(mDataTelephonyManager);
    }

    @Test
    public void get3GAuthResponseNullIccAuthentication() {
        when(mDataTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                        TelephonyManager.AUTHTYPE_EAP_AKA, "AgEjAkVn")).thenReturn(null);
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);
        SimAuthResponseData response = mTelephonyUtil.get3GAuthResponse(
                new SimAuthRequestData(0, 0, "SSID", new String[]{"0123", "4567"}),
                config);

        assertNull(response);
    }

    @Test
    public void get3GAuthResponseIccAuthenticationTooShort() {
        when(mDataTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                        TelephonyManager.AUTHTYPE_EAP_AKA, "AgEjAkVn"))
                .thenReturn(Base64.encodeToString(new byte[] {(byte) 0xdc}, Base64.NO_WRAP));
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);
        SimAuthResponseData response = mTelephonyUtil.get3GAuthResponse(
                new SimAuthRequestData(0, 0, "SSID", new String[]{"0123", "4567"}),
                config);

        assertNull(response);
    }

    @Test
    public void get3GAuthResponseBadTag() {
        when(mDataTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                        TelephonyManager.AUTHTYPE_EAP_AKA, "AgEjAkVn"))
                .thenReturn(Base64.encodeToString(new byte[] {0x31, 0x1, 0x2, 0x3, 0x4},
                                Base64.NO_WRAP));
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);
        SimAuthResponseData response = mTelephonyUtil.get3GAuthResponse(
                new SimAuthRequestData(0, 0, "SSID", new String[]{"0123", "4567"}),
                config);

        assertNull(response);
    }

    @Test
    public void get3GAuthResponseUmtsAuth() {
        when(mDataTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                        TelephonyManager.AUTHTYPE_EAP_AKA, "AgEjAkVn"))
                .thenReturn(create3GSimAuthUmtsAuthResponse(new byte[] {0x11, 0x12},
                                new byte[] {0x21, 0x22, 0x23}, new byte[] {0x31}));
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);
        SimAuthResponseData response = mTelephonyUtil.get3GAuthResponse(
                new SimAuthRequestData(0, 0, "SSID", new String[]{"0123", "4567"}),
                config);

        assertNotNull(response);
        assertEquals("UMTS-AUTH", response.type);
        assertEquals(":31:212223:1112", response.response);
    }

    @Test
    public void get3GAuthResponseUmtsAuts() {
        when(mDataTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                        TelephonyManager.AUTHTYPE_EAP_AKA, "AgEjAkVn"))
                .thenReturn(create3GSimAuthUmtsAutsResponse(new byte[] {0x22, 0x33}));
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);
        SimAuthResponseData response = mTelephonyUtil.get3GAuthResponse(
                new SimAuthRequestData(0, 0, "SSID", new String[]{"0123", "4567"}),
                config);
        assertNotNull(response);
        assertEquals("UMTS-AUTS", response.type);
        assertEquals(":2233", response.response);
    }

    /**
     * Verify that anonymous identity should be a valid format based on MCC/MNC of current SIM.
     */
    @Test
    public void getAnonymousIdentityWithSim() {
        String mccmnc = "123456";
        String expectedIdentity = "anonymous@wlan.mnc456.mcc123.3gppnetwork.org";
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        when(mDataTelephonyManager.getSimOperator()).thenReturn(mccmnc);
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals(expectedIdentity,
                mTelephonyUtil.getAnonymousIdentityWith3GppRealm(config));
    }

    /**
     * Verify that anonymous identity should be {@code null} when SIM is absent.
     */
    @Test
    public void getAnonymousIdentityWithoutSim() {
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_ABSENT);
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);

        assertNull(mTelephonyUtil.getAnonymousIdentityWith3GppRealm(config));
    }

    /**
     * Verify SIM is present.
     */
    @Test
    public void isSimPresentWithValidSubscriptionIdList() {
        SubscriptionInfo subInfo1 = mock(SubscriptionInfo.class);
        when(subInfo1.getSubscriptionId()).thenReturn(DATA_SUBID);
        SubscriptionInfo subInfo2 = mock(SubscriptionInfo.class);
        when(subInfo2.getSubscriptionId()).thenReturn(NON_DATA_SUBID);
        when(mSubscriptionManager.getActiveSubscriptionInfoList())
                .thenReturn(Arrays.asList(subInfo1, subInfo2));

        assertTrue(mTelephonyUtil.isSimPresent(DATA_SUBID));
    }

    /**
     * Verify SIM is not present.
     */
    @Test
    public void isSimPresentWithInvalidOrEmptySubscriptionIdList() {
        when(mSubscriptionManager.getActiveSubscriptionInfoList())
                .thenReturn(Collections.emptyList());

        assertFalse(mTelephonyUtil.isSimPresent(DATA_SUBID));

        SubscriptionInfo subInfo = mock(SubscriptionInfo.class);
        when(subInfo.getSubscriptionId()).thenReturn(NON_DATA_SUBID);
        when(mSubscriptionManager.getActiveSubscriptionInfoList())
                .thenReturn(Arrays.asList(subInfo));

        assertFalse(mTelephonyUtil.isSimPresent(DATA_SUBID));
    }

    /**
     * The active SubscriptionInfo List may be null or empty from Telephony.
     */
    @Test
    public void getBestMatchSubscriptionIdWithEmptyActiveSubscriptionInfoList() {
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);
        when(mSubscriptionManager.getActiveSubscriptionInfoList()).thenReturn(null);

        assertEquals(INVALID_SUBID, mTelephonyUtil.getBestMatchSubscriptionId(config));

        when(mSubscriptionManager.getActiveSubscriptionInfoList())
                .thenReturn(Collections.emptyList());

        assertEquals(INVALID_SUBID, mTelephonyUtil.getBestMatchSubscriptionId(config));
    }

    /**
     * The matched Subscription ID should be that of data SIM when carrier ID is not specified.
     */
    @Test
    public void getBestMatchSubscriptionIdWithoutCarrierIdFieldForSimConfig() {
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);

        assertEquals(DATA_SUBID, mTelephonyUtil.getBestMatchSubscriptionId(config));
    }

    /**
     * The matched Subscription ID should be invalid if the configuration does not require
     * SIM card and the carrier ID is not specified.
     */
    @Test
    public void getBestMatchSubscriptionIdWithoutCarrierIdFieldForNonSimConfig() {
        WifiConfiguration config = new WifiConfiguration();

        assertEquals(INVALID_SUBID, mTelephonyUtil.getBestMatchSubscriptionId(config));
    }

    /**
     * If the carrier ID is specifed for EAP-SIM configuration, the corresponding Subscription ID
     * should be returned.
     */
    @Test
    public void getBestMatchSubscriptionIdWithNonDataCarrierId() {
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);
        config.carrierId = NON_DATA_CARRIER_ID;

        assertEquals(NON_DATA_SUBID, mTelephonyUtil.getBestMatchSubscriptionId(config));

        config.carrierId = DATA_CARRIER_ID;
        assertEquals(DATA_SUBID, mTelephonyUtil.getBestMatchSubscriptionId(config));
    }

    /**
     * The matched Subscription ID should be invalid if the SIM card for the specified carrier ID
     * is absent.
     */
    @Test
    public void getBestMatchSubscriptionIdWithDeactiveCarrierId() {
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.AKA, WifiEnterpriseConfig.Phase2.NONE);
        config.carrierId = DEACTIVE_CARRIER_ID;

        assertEquals(INVALID_SUBID, mTelephonyUtil.getBestMatchSubscriptionId(config));
    }
}
