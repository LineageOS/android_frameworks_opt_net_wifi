/*
 * Copyright 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.org.conscrypt.TrustManagerImpl;
import com.android.server.wifi.hotspot2.soap.HttpsServiceConnection;
import com.android.server.wifi.hotspot2.soap.HttpsTransport;
import com.android.server.wifi.hotspot2.soap.SoapParser;
import com.android.server.wifi.hotspot2.soap.SppResponseMessage;

import org.ksoap2.serialization.AttributeInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;

import java.io.IOException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Provides methods to interface with the OSU server
 */
public class OsuServerConnection {
    private static final String TAG = "OsuServerConnection";

    private static final int DNS_NAME = 2;

    private SSLSocketFactory mSocketFactory;
    private URL mUrl;
    private Network mNetwork;
    private WFATrustManager mTrustManager;
    private HttpsTransport mHttpsTransport;
    private HttpsServiceConnection mServiceConnection = null;
    private HttpsURLConnection mUrlConnection = null;
    private HandlerThread mOsuServerHandlerThread;
    private Handler mHandler;
    private PasspointProvisioner.OsuServerCallbacks mOsuServerCallbacks;
    private boolean mSetupComplete = false;
    private boolean mVerboseLoggingEnabled = false;
    private Looper mLooper;

    @VisibleForTesting
    /* package */ OsuServerConnection(Looper looper) {
        mLooper = looper;
    }

    /**
     * Sets up callback for event
     *
     * @param callbacks OsuServerCallbacks to be invoked for server related events
     */
    public void setEventCallback(PasspointProvisioner.OsuServerCallbacks callbacks) {
        mOsuServerCallbacks = callbacks;
    }

    /**
     * Initialize socket factory for server connection using HTTPS
     *
     * @param tlsContext SSLContext that will be used for HTTPS connection
     * @param trustManagerImpl TrustManagerImpl delegate to validate certs
     */
    public void init(SSLContext tlsContext, TrustManagerImpl trustManagerImpl) {
        if (tlsContext == null) {
            return;
        }
        try {
            mTrustManager = new WFATrustManager(trustManagerImpl);
            tlsContext.init(null, new TrustManager[] { mTrustManager }, null);
            mSocketFactory = tlsContext.getSocketFactory();
        } catch (KeyManagementException e) {
            Log.w(TAG, "Initialization failed");
            e.printStackTrace();
            return;
        }
        mSetupComplete = true;

        // If mLooper is already set by unit test, don't overwrite it.
        if (mLooper == null) {
            mOsuServerHandlerThread = new HandlerThread("OsuServerHandler");
            mOsuServerHandlerThread.start();
            mLooper = mOsuServerHandlerThread.getLooper();
        }
        mHandler = new Handler(mLooper);
    }

    /**
     * Provides the capability to run OSU server validation
     *
     * @return boolean true if capability available
     */
    public boolean canValidateServer() {
        return mSetupComplete;
    }

    /**
     * Enables verbose logging
     *
     * @param verbose a value greater than zero enables verbose logging
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0 ? true : false;
    }

    /**
     * Connect to the OSU server
     *
     * @param url Osu Server's URL
     * @param network current network connection
     * @return boolean value, true if connection was successful
     *
     * Note: Relies on the caller to ensure that the capability to validate the OSU
     * Server is available.
     */
    public boolean connect(URL url, Network network) {
        mNetwork = network;
        mUrl = url;
        HttpsURLConnection urlConnection;
        try {
            urlConnection = (HttpsURLConnection) mNetwork.openConnection(mUrl);
            urlConnection.setSSLSocketFactory(mSocketFactory);
            urlConnection.connect();
        } catch (IOException e) {
            Log.e(TAG, "Unable to establish a URL connection");
            e.printStackTrace();
            return false;
        }
        mUrlConnection = urlConnection;
        return true;
    }

    /**
     * Validate the service provider by comparing its identities found in OSU Server cert
     * to the friendlyName obtained from ANQP exchange that is displayed to the user.
     *
     * @param locale       a {@link Locale} object used for matching the friendly name in
     *                     subjectAltName section of the certificate along with
     *                     {@param friendlyName}.
     * @param friendlyName a string of the friendly name used for finding the same name in
     *                     subjectAltName section of the certificate.
     * @return boolean true if friendlyName shows up as one of the identities in the cert
     */
    public boolean validateProvider(Locale locale,
            String friendlyName) {

        if (locale == null || TextUtils.isEmpty(friendlyName)) {
            return false;
        }

        for (Pair<Locale, String> identity : ASN1SubjectAltNamesParser.getProviderNames(
                mTrustManager.getProviderCert())) {
            if (identity.first == null) continue;

            // Compare the language code for ISO-639.
            if (identity.first.getISO3Language().equals(locale.getISO3Language()) &&
                    TextUtils.equals(identity.second, friendlyName)) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "OSU certificate is valid for "
                            + identity.first.getISO3Language() + "/" + identity.second);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * The helper method to exchange a SOAP message.
     *
     * @param soapEnvelope the soap message to be sent.
     * @return {@code true} if {@link Network} is valid and {@code soapEnvelope} is not null,
     * {@code false} otherwise.
     */
    public boolean exchangeSoapMessage(@NonNull SoapSerializationEnvelope soapEnvelope) {
        if (mNetwork == null) {
            Log.e(TAG, "Network is not established");
            return false;
        }

        if (mUrlConnection == null) {
            Log.e(TAG, "Server certificate is not validated");
            return false;
        }

        if (soapEnvelope == null) {
            Log.e(TAG, "soapEnvelope is null");
            return false;
        }

        mHandler.post(() -> performSoapMessageExchange(soapEnvelope));
        return true;
    }

    private void performSoapMessageExchange(@NonNull SoapSerializationEnvelope soapEnvelope) {
        if (mServiceConnection != null) {
            mServiceConnection.disconnect();
        }

        mServiceConnection = getServiceConnection(mUrl, mNetwork);
        if (mServiceConnection == null) {
            Log.e(TAG, "ServiceConnection for https is null");
            if (mOsuServerCallbacks != null) {
                mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(), null);
            }
            return;
        }

        SppResponseMessage sppResponse = null;
        try {
            // Sending the SOAP message
            mHttpsTransport.call("", soapEnvelope);
            Object response = soapEnvelope.bodyIn;
            if (response == null) {
                Log.e(TAG, "SoapObject is null");
                if (mOsuServerCallbacks != null) {
                    mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                            null);
                }
                return;
            }
            if (!(response instanceof SoapObject)) {
                Log.e(TAG, "Not a SoapObject instance");
                if (mOsuServerCallbacks != null) {
                    mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                            null);
                }
                return;
            }
            SoapObject soapResponse = (SoapObject) response;
            if (mVerboseLoggingEnabled) {
                for (int i = 0; i < soapResponse.getAttributeCount(); i++) {
                    AttributeInfo attributeInfo = new AttributeInfo();
                    soapResponse.getAttributeInfo(i, attributeInfo);
                    Log.v(TAG, "Attribute : " + attributeInfo.toString());
                }
                Log.v(TAG, "response : " + soapResponse.toString());
            }

            // Get the parsed SOAP SPP Response message
            sppResponse = SoapParser.getResponse(soapResponse);
        } catch (Exception e) {
            if (e instanceof SSLHandshakeException) {
                Log.e(TAG, "Failed to make TLS connection: " + e);
            } else {
                Log.e(TAG, "Failed to exchange the SOAP message: " + e);
            }
            if (mOsuServerCallbacks != null) {
                mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(), null);
            }
            return;
        } finally {
            mServiceConnection.disconnect();
            mServiceConnection = null;
        }
        if (mOsuServerCallbacks != null) {
            mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                    sppResponse);
        }
    }

    /**
     * Get the HTTPS service connection used for SOAP message exchange.
     *
     * @return {@link HttpsServiceConnection}
     */
    private HttpsServiceConnection getServiceConnection(@NonNull URL url,
            @NonNull Network network) {
        HttpsServiceConnection serviceConnection;
        try {
            // Creates new HTTPS connection.
            mHttpsTransport = HttpsTransport.createInstance(network, url);
            serviceConnection = (HttpsServiceConnection) mHttpsTransport.getServiceConnection();
            if (serviceConnection != null) {
                serviceConnection.setSSLSocketFactory(mSocketFactory);
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to establish a URL connection");
            return null;
        }
        return serviceConnection;
    }

    /**
     * Clean up
     */
    public void cleanup() {
        if (mUrlConnection != null) {
            mUrlConnection.disconnect();
            mUrlConnection = null;
        }

        if (mServiceConnection != null) {
            mServiceConnection.disconnect();
            mServiceConnection = null;
        }
    }

    private class WFATrustManager implements X509TrustManager {
        private TrustManagerImpl mDelegate;
        private List<X509Certificate> mServerCerts;

        WFATrustManager(TrustManagerImpl trustManagerImpl) {
            mDelegate = trustManagerImpl;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "checkClientTrusted " + authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "checkServerTrusted " + authType);
            }
            boolean certsValid = false;
            try {
                // Perform certificate path validation and get validated certs
                mServerCerts = mDelegate.getTrustedChainForServer(chain, authType,
                        (SSLSocket) null);
                certsValid = true;
            } catch (CertificateException e) {
                Log.e(TAG, "Unable to validate certs " + e);
                if (mVerboseLoggingEnabled) {
                    e.printStackTrace();
                }
            }
            if (mOsuServerCallbacks != null) {
                mOsuServerCallbacks.onServerValidationStatus(mOsuServerCallbacks.getSessionId(),
                        certsValid);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "getAcceptedIssuers ");
            }
            return null;
        }

        /**
         * Returns the OSU certificate matching the FQDN of the OSU server
         *
         * @return {@link X509Certificate} OSU certificate matching FQDN of OSU server
         */
        public X509Certificate getProviderCert() {
            if (mServerCerts == null || mServerCerts.size() <= 0) {
                return null;
            }
            X509Certificate providerCert = null;
            String fqdn = mUrl.getHost();
            try {
                for (X509Certificate certificate : mServerCerts) {
                    Collection<List<?>> col = certificate.getSubjectAlternativeNames();
                    if (col == null) {
                        continue;
                    }
                    for (List<?> name : col) {
                        if (name == null) {
                            continue;
                        }
                        if (name.size() >= DNS_NAME
                                && name.get(0).getClass() == Integer.class
                                && name.get(1).toString().equals(fqdn)) {
                            providerCert = certificate;
                            if (mVerboseLoggingEnabled) {
                                Log.v(TAG, "OsuCert found");
                            }
                            break;
                        }
                    }
                }
            } catch (CertificateParsingException e) {
                Log.e(TAG, "Unable to match certificate to " + fqdn);
                if (mVerboseLoggingEnabled) {
                    e.printStackTrace();
                }
            }
            return providerCert;
        }
    }
}

