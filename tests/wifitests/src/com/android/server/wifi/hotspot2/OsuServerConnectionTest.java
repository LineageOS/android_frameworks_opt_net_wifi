/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Network;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;
import android.util.Pair;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.org.conscrypt.TrustManagerImpl;
import com.android.server.wifi.hotspot2.soap.HttpsServiceConnection;
import com.android.server.wifi.hotspot2.soap.HttpsTransport;
import com.android.server.wifi.hotspot2.soap.SoapParser;
import com.android.server.wifi.hotspot2.soap.SppResponseMessage;

import org.junit.Before;
import org.junit.Test;
import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.io.IOException;
import java.net.Socket;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.OsuServerConnection}.
 */
@SmallTest
public class OsuServerConnectionTest {
    private static final String TEST_VALID_URL = "http://www.google.com";
    private static final String AUTH_TYPE = "ECDHE_RSA";
    private static final String PROVIDER_NAME_VALID = "Boingo";
    private static final String PROVIDER_NAME_INVALID = "Boingo1";
    private static final int ENABLE_VERBOSE_LOGGING = 1;
    private static final int TEST_SESSION_ID = 1;

    private TestLooper mLooper = new TestLooper();
    private OsuServerConnection mOsuServerConnection;
    private URL mValidServerUrl;
    private List<Pair<Locale, String>> mProviderIdentities = new ArrayList<>();
    private ArgumentCaptor<TrustManager[]> mTrustManagerCaptor =
            ArgumentCaptor.forClass(TrustManager[].class);

    @Mock PasspointProvisioner.OsuServerCallbacks mOsuServerCallbacks;
    @Mock Network mNetwork;
    @Mock HttpsURLConnection mUrlConnection;
    @Mock WfaKeyStore mWfaKeyStore;
    @Mock SSLContext mTlsContext;
    @Mock KeyStore mKeyStore;
    @Mock TrustManagerImpl mDelegate;
    @Mock HttpsTransport mHttpsTransport;
    @Mock HttpsServiceConnection mHttpsServiceConnection;
    @Mock SppResponseMessage mSppResponseMessage;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mOsuServerConnection = new OsuServerConnection(mLooper.getLooper());
        mOsuServerConnection.enableVerboseLogging(ENABLE_VERBOSE_LOGGING);
        mProviderIdentities.add(Pair.create(Locale.US, PROVIDER_NAME_VALID));
        mValidServerUrl = new URL(TEST_VALID_URL);
        when(mWfaKeyStore.get()).thenReturn(mKeyStore);
        when(mOsuServerCallbacks.getSessionId()).thenReturn(TEST_SESSION_ID);
        when(mNetwork.openConnection(any(URL.class))).thenReturn(mUrlConnection);
        when(mHttpsTransport.getServiceConnection()).thenReturn(mHttpsServiceConnection);
        when(mDelegate.getTrustedChainForServer(any(X509Certificate[].class), anyString(),
                (Socket) isNull()))
                .thenReturn(PasspointProvisioningTestUtil.getOsuCertsForTest());
    }

    /**
     * Verifies initialization and connecting to the OSU server
     */
    @Test
    public void verifyInitAndConnect() throws Exception {
        // static mocking
        MockitoSession session = ExtendedMockito.mockitoSession().mockStatic(
                ASN1SubjectAltNamesParser.class).startMocking();
        try {
            when(ASN1SubjectAltNamesParser.getProviderNames(any(X509Certificate.class))).thenReturn(
                    mProviderIdentities);

            mOsuServerConnection.init(mTlsContext, mDelegate);
            mOsuServerConnection.setEventCallback(mOsuServerCallbacks);

            assertTrue(mOsuServerConnection.canValidateServer());
            assertTrue(mOsuServerConnection.connect(mValidServerUrl, mNetwork));
            verify(mTlsContext).init(isNull(), mTrustManagerCaptor.capture(), isNull());

            TrustManager[] trustManagers = mTrustManagerCaptor.getValue();
            X509TrustManager trustManager = (X509TrustManager) trustManagers[0];

            trustManager.checkServerTrusted(new X509Certificate[1], AUTH_TYPE);

            verify(mOsuServerCallbacks).onServerValidationStatus(anyInt(), eq(true));
            assertTrue(mOsuServerConnection.validateProvider(Locale.US, PROVIDER_NAME_VALID));
        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verifies initialization of the HTTPS connection with invalid TLSContext
     */
    @Test
    public void verifyInvalidTlsContext() {
        mOsuServerConnection.init(null, mDelegate);
        mOsuServerConnection.setEventCallback(mOsuServerCallbacks);

        assertFalse(mOsuServerConnection.canValidateServer());
    }

    /**
     * Verifies initialization of the HTTPS connection when TlsContext init fails
     */
    @Test
    public void verifyTlsContextInitFailure() throws Exception {
        doThrow(new KeyManagementException()).when(mTlsContext).init(any(), any(), any());

        mOsuServerConnection.init(mTlsContext, mDelegate);
        mOsuServerConnection.setEventCallback(mOsuServerCallbacks);

        assertFalse(mOsuServerConnection.canValidateServer());
    }

    /**
     * Verifies initialization and opening URL connection failed on the network
     */
    @Test
    public void verifyInitAndNetworkOpenURLConnectionFailed() throws Exception {
        doThrow(new IOException()).when(mNetwork).openConnection(any(URL.class));

        mOsuServerConnection.init(mTlsContext, mDelegate);
        mOsuServerConnection.setEventCallback(mOsuServerCallbacks);

        assertTrue(mOsuServerConnection.canValidateServer());
        assertFalse(mOsuServerConnection.connect(mValidServerUrl, mNetwork));
    }

    /**
     * Verifies initialization and connection failure to OSU server
     */
    @Test
    public void verifyInitAndServerConnectFailure() throws Exception {
        doThrow(new IOException()).when(mUrlConnection).connect();

        mOsuServerConnection.init(mTlsContext, mDelegate);
        mOsuServerConnection.setEventCallback(mOsuServerCallbacks);

        assertTrue(mOsuServerConnection.canValidateServer());
        assertFalse(mOsuServerConnection.connect(mValidServerUrl, mNetwork));
    }

    /**
     * Verifies initialization and connecting to the OSU server, cert validation failure
     */
    @Test
    public void verifyInitAndConnectCertValidationFailure() throws Exception {
        mOsuServerConnection.init(mTlsContext, mDelegate);
        mOsuServerConnection.setEventCallback(mOsuServerCallbacks);

        assertTrue(mOsuServerConnection.canValidateServer());
        assertTrue(mOsuServerConnection.connect(mValidServerUrl, mNetwork));
        verify(mTlsContext).init(isNull(), mTrustManagerCaptor.capture(), isNull());

        TrustManager[] trustManagers = mTrustManagerCaptor.getValue();
        X509TrustManager trustManager = (X509TrustManager) trustManagers[0];
        doThrow(new CertificateException()).when(mDelegate)
                .getTrustedChainForServer(any(X509Certificate[].class), anyString(),
                        (Socket) isNull());

        trustManager.checkServerTrusted(new X509Certificate[1], AUTH_TYPE);

        verify(mOsuServerCallbacks).onServerValidationStatus(anyInt(), eq(false));
    }

    /**
     * Verifies initialization and connecting to the OSU server, friendly name mismatch
     */
    @Test
    public void verifyInitAndConnectInvalidProviderIdentity() throws Exception {
        // static mocking
        MockitoSession session = ExtendedMockito.mockitoSession().mockStatic(
                ASN1SubjectAltNamesParser.class).startMocking();
        try {
            when(ASN1SubjectAltNamesParser.getProviderNames(any(X509Certificate.class))).thenReturn(
                    mProviderIdentities);

            mOsuServerConnection.init(mTlsContext, mDelegate);
            mOsuServerConnection.setEventCallback(mOsuServerCallbacks);

            assertTrue(mOsuServerConnection.canValidateServer());
            assertTrue(mOsuServerConnection.connect(mValidServerUrl, mNetwork));
            verify(mTlsContext).init(isNull(), mTrustManagerCaptor.capture(), isNull());

            TrustManager[] trustManagers = mTrustManagerCaptor.getValue();
            X509TrustManager trustManager = (X509TrustManager) trustManagers[0];

            trustManager.checkServerTrusted(new X509Certificate[1], AUTH_TYPE);

            verify(mOsuServerCallbacks).onServerValidationStatus(anyInt(), eq(true));
            assertFalse(mOsuServerConnection.validateProvider(Locale.US, PROVIDER_NAME_INVALID));
        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verifies {@code ExchangeSoapMessage} should return {@code false} if there is no connection.
     */
    @Test
    public void verifyExchangeSoapMessageWithoutConnection() {
        assertFalse(mOsuServerConnection.exchangeSoapMessage(
                new SoapSerializationEnvelope(SoapEnvelope.VER12)));
    }

    /**
     * Verifies {@code ExchangeSoapMessage} should return {@code false} if {@code soapEnvelope} is
     * {@code null}
     */
    @Test
    public void verifyExchangeSoapMessageWithInvalidArgument() {
        mOsuServerConnection.init(mTlsContext, mDelegate);
        mOsuServerConnection.setEventCallback(mOsuServerCallbacks);

        assertTrue(mOsuServerConnection.connect(mValidServerUrl, mNetwork));
        assertFalse(mOsuServerConnection.exchangeSoapMessage(null));
    }

    /**
     * Verifies {@code ExchangeSoapMessage} should get {@code null} message if exception occurs
     * during soap exchange.
     */
    @Test
    public void verifyExchangeSoapMessageWithException() throws Exception {
        // static mocking
        MockitoSession session = ExtendedMockito.mockitoSession().mockStatic(
                HttpsTransport.class).startMocking();
        try {
            mOsuServerConnection.init(mTlsContext, mDelegate);
            mOsuServerConnection.setEventCallback(mOsuServerCallbacks);
            when(HttpsTransport.createInstance(any(Network.class), any(URL.class))).thenReturn(
                    mHttpsTransport);
            doThrow(new IOException()).when(mHttpsTransport).call(any(String.class),
                    any(SoapSerializationEnvelope.class));

            assertTrue(mOsuServerConnection.connect(mValidServerUrl, mNetwork));
            assertTrue(mOsuServerConnection.exchangeSoapMessage(
                    new SoapSerializationEnvelope(SoapEnvelope.VER12)));

            mLooper.dispatchAll();
            verify(mOsuServerCallbacks).onReceivedSoapMessage(anyInt(), isNull());
        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verifies {@code ExchangeSoapMessage} should return an instance of {@link SppResponseMessage}.
     */
    @Test
    public void verifyExchangeSoapMessage() {
        // static mocking
        MockitoSession session = ExtendedMockito.mockitoSession().mockStatic(
                HttpsTransport.class).mockStatic(SoapParser.class).startMocking();
        try {
            mOsuServerConnection.init(mTlsContext, mDelegate);
            mOsuServerConnection.setEventCallback(mOsuServerCallbacks);
            assertTrue(mOsuServerConnection.connect(mValidServerUrl, mNetwork));

            SoapSerializationEnvelope envelope = new SoapSerializationEnvelope(SoapEnvelope.VER12);
            envelope.bodyIn = new SoapObject();
            when(HttpsTransport.createInstance(any(Network.class), any(URL.class))).thenReturn(
                    mHttpsTransport);
            when(SoapParser.getResponse(any(SoapObject.class))).thenReturn(mSppResponseMessage);

            assertTrue(mOsuServerConnection.exchangeSoapMessage(envelope));

            mLooper.dispatchAll();
            verify(mOsuServerCallbacks).onReceivedSoapMessage(anyInt(), eq(mSppResponseMessage));
        } finally {
            session.finishMocking();
        }
    }
}
