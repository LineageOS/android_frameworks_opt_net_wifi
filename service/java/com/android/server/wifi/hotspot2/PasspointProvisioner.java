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
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.net.wifi.hotspot2.omadm.PpsMoParser;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.wifi.WifiNative;
import com.android.server.wifi.hotspot2.soap.PostDevDataMessage;
import com.android.server.wifi.hotspot2.soap.PostDevDataResponse;
import com.android.server.wifi.hotspot2.soap.RedirectListener;
import com.android.server.wifi.hotspot2.soap.SppConstants;
import com.android.server.wifi.hotspot2.soap.SppResponseMessage;
import com.android.server.wifi.hotspot2.soap.command.BrowserUri;
import com.android.server.wifi.hotspot2.soap.command.PpsMoData;
import com.android.server.wifi.hotspot2.soap.command.SppCommand;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/**
 * Provides methods to carry out provisioning flow
 */
public class PasspointProvisioner {
    private static final String TAG = "PasspointProvisioner";

    // Indicates callback type for caller initiating provisioning
    private static final int PROVISIONING_STATUS = 0;
    private static final int PROVISIONING_FAILURE = 1;

    // TLS version to be used for HTTPS connection with OSU server
    private static final String TLS_VERSION = "TLSv1";
    private static final String OSU_APP_PACKAGE = "com.android.hotspot2";

    private final Context mContext;
    private final ProvisioningStateMachine mProvisioningStateMachine;
    private final OsuNetworkCallbacks mOsuNetworkCallbacks;
    private final OsuNetworkConnection mOsuNetworkConnection;
    private final OsuServerConnection mOsuServerConnection;
    private final WfaKeyStore mWfaKeyStore;
    private final PasspointObjectFactory mObjectFactory;
    private final SystemInfo mSystemInfo;
    private RedirectListener mRedirectListener;
    private int mCurrentSessionId = 0;
    private int mCallingUid;
    private boolean mVerboseLoggingEnabled = false;

    PasspointProvisioner(Context context, WifiNative wifiNative,
            PasspointObjectFactory objectFactory) {
        mContext = context;
        mOsuNetworkConnection = objectFactory.makeOsuNetworkConnection(context);
        mProvisioningStateMachine = new ProvisioningStateMachine();
        mOsuNetworkCallbacks = new OsuNetworkCallbacks();
        mOsuServerConnection = objectFactory.makeOsuServerConnection();
        mWfaKeyStore = objectFactory.makeWfaKeyStore();
        mSystemInfo = objectFactory.getSystemInfo(context, wifiNative);
        mObjectFactory = objectFactory;
    }

    /**
     * Sets up for provisioning
     * @param looper Looper on which the Provisioning state machine will run
     */
    public void init(Looper looper) {
        mProvisioningStateMachine.start(new Handler(looper));
        mOsuNetworkConnection.init(mProvisioningStateMachine.getHandler());
        // Offload the heavy load job to another thread
        mProvisioningStateMachine.getHandler().post(() -> {
            mRedirectListener = RedirectListener.createInstance(looper);
            mWfaKeyStore.load();
            mOsuServerConnection.init(mObjectFactory.getSSLContext(TLS_VERSION),
                    mObjectFactory.getTrustManagerImpl(mWfaKeyStore.get()));
        });
    }

    /**
     * Enable verbose logging to help debug failures
     * @param level integer indicating verbose logging enabled if > 0
     */
    public void enableVerboseLogging(int level) {
        mVerboseLoggingEnabled = (level > 0) ? true : false;
        mOsuNetworkConnection.enableVerboseLogging(level);
        mOsuServerConnection.enableVerboseLogging(level);
    }

    /**
     * Start provisioning flow with a given provider.
     * @param callingUid calling uid.
     * @param provider {@link OsuProvider} to provision with.
     * @param callback {@link IProvisioningCallback} to provide provisioning status.
     * @return boolean value, true if provisioning was started, false otherwise.
     *
     * Implements HS2.0 provisioning flow with a given HS2.0 provider.
     */
    public boolean startSubscriptionProvisioning(int callingUid, OsuProvider provider,
            IProvisioningCallback callback) {
        if (mRedirectListener == null) {
            Log.e(TAG, "RedirectListener is not possible to run");
            return false;
        }
        mCallingUid = callingUid;

        Log.v(TAG, "Provisioning started with " + provider.toString());

        mProvisioningStateMachine.getHandler().post(() -> {
            mProvisioningStateMachine.startProvisioning(provider, callback);
        });

        return true;
    }

    /**
     * Handles the provisioning flow state transitions
     */
    class ProvisioningStateMachine {
        private static final String TAG = "ProvisioningStateMachine";

        static final int STATE_INIT = 1;
        static final int STATE_WAITING_TO_CONNECT = 2;
        static final int STATE_OSU_AP_CONNECTED = 3;
        static final int STATE_OSU_SERVER_CONNECTED = 4;
        static final int STATE_WAITING_FOR_FIRST_SOAP_RESPONSE = 5;
        static final int STATE_WAITING_FOR_REDIRECT_RESPONSE = 6;
        static final int STATE_WAITING_FOR_SECOND_SOAP_RESPONSE = 7;

        private OsuProvider mOsuProvider;
        private IProvisioningCallback mProvisioningCallback;
        private int mState = STATE_INIT;
        private Handler mHandler;
        private URL mServerUrl;
        private Network mNetwork;
        private String mSessionId;
        private String mWebUrl;

        /**
         * Initializes and starts the state machine with a handler to handle incoming events
         */
        public void start(Handler handler) {
            mHandler = handler;
        }

        /**
         * Returns the handler on which a runnable can be posted
         *
         * @return Handler State Machine's handler
         */
        public Handler getHandler() {
            return mHandler;
        }

        /**
         * Start Provisioning with the Osuprovider and invoke callbacks
         *
         * @param provider OsuProvider to provision with
         * @param callback IProvisioningCallback to invoke callbacks on
         * Note: Called on main thread (WifiService thread).
         */
        public void startProvisioning(OsuProvider provider, IProvisioningCallback callback) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "startProvisioning received in state=" + mState);
            }
            if (mState != STATE_INIT) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "State Machine needs to be reset before starting provisioning");
                }
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
            }
            if (!mOsuServerConnection.canValidateServer()) {
                Log.w(TAG, "Provisioning is not possible");
                mProvisioningCallback = callback;
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_PROVISIONING_NOT_AVAILABLE);
                return;
            }
            URL serverUrl;
            try {
                serverUrl = new URL(provider.getServerUri().toString());
            } catch (MalformedURLException e) {
                Log.e(TAG, "Invalid Server URL");
                mProvisioningCallback = callback;
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_SERVER_URL_INVALID);
                return;
            }
            mServerUrl = serverUrl;
            mProvisioningCallback = callback;
            mOsuProvider = provider;

            // Register for network and wifi state events during provisioning flow
            mOsuNetworkConnection.setEventCallback(mOsuNetworkCallbacks);

            // Register for OSU server callbacks
            mOsuServerConnection.setEventCallback(new OsuServerCallbacks(++mCurrentSessionId));

            if (!mOsuNetworkConnection.connect(mOsuProvider.getOsuSsid(),
                    mOsuProvider.getNetworkAccessIdentifier())) {
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_AP_CONNECTING);
            changeState(STATE_WAITING_TO_CONNECT);
        }

        /**
         * Handle Wifi Disable event
         *
         * Note: Called on main thread (WifiService thread).
         */
        public void handleWifiDisabled() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Wifi Disabled in state=" + mState);
            }
            if (mState == STATE_INIT) {
                Log.w(TAG, "Wifi Disable unhandled in state=" + mState);
                return;
            }
            resetStateMachine(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
        }

        /**
         * Handle server validation failure
         *
         * Note: Called on main thread (WifiService thread).
         */
        public void handleServerValidationFailure(int sessionId) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Server Validation failure received in " + mState);
            }
            if (sessionId != mCurrentSessionId) {
                Log.w(TAG, "Expected server validation callback for currentSessionId="
                        + mCurrentSessionId);
                return;
            }
            if (mState != STATE_OSU_SERVER_CONNECTED) {
                Log.wtf(TAG, "Server Validation Failure unhandled in mState=" + mState);
                return;
            }
            resetStateMachine(ProvisioningCallback.OSU_FAILURE_SERVER_VALIDATION);
        }

        /**
         * Handle status of server validation success
         *
         * Note: Called on main thread (WifiService thread).
         */
        public void handleServerValidationSuccess(int sessionId) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Server Validation Success received in " + mState);
            }
            if (sessionId != mCurrentSessionId) {
                Log.w(TAG, "Expected server validation callback for currentSessionId="
                        + mCurrentSessionId);
                return;
            }
            if (mState != STATE_OSU_SERVER_CONNECTED) {
                Log.wtf(TAG, "Server validation success event unhandled in state=" + mState);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_SERVER_VALIDATED);
            validateServiceProvider();
        }

        /**
         * Handles next step once receiving a HTTP redirect response.
         *
         * Note: Called on main thread (WifiService thread).
         */
        public void handleRedirectResponse() {
            if (mState != STATE_WAITING_FOR_REDIRECT_RESPONSE) {
                Log.e(TAG, "Received redirect request in wrong state=" + mState);
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                return;
            }

            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_REDIRECT_RESPONSE_RECEIVED);
            mRedirectListener.stopServer();
            secondSoapExchange();
        }

        /**
         * Handles next step when timeout occurs because {@link RedirectListener} doesn't
         * receive a HTTP redirect response.
         *
         * Note: Called on main thread (WifiService thread).
         */
        public void handleTimeOutForRedirectResponse() {
            Log.e(TAG, "Timed out for HTTP redirect response");

            if (mState != STATE_WAITING_FOR_REDIRECT_RESPONSE) {
                Log.e(TAG, "Received timeout error for HTTP redirect response  in wrong state="
                        + mState);
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                return;
            }
            mRedirectListener.stopServer();
            resetStateMachine(ProvisioningCallback.OSU_FAILURE_TIMED_OUT_REDIRECT_LISTENER);
        }

        /**
         * Connected event received
         *
         * @param network Network object for this connection
         * Note: Called on main thread (WifiService thread).
         */
        public void handleConnectedEvent(Network network) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Connected event received in state=" + mState);
            }
            if (mState != STATE_WAITING_TO_CONNECT) {
                // Not waiting for a connection
                Log.wtf(TAG, "Connection event unhandled in state=" + mState);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_AP_CONNECTED);
            changeState(STATE_OSU_AP_CONNECTED);
            initiateServerConnection(network);
        }

        /**
         * Handles SOAP message response sent by server
         *
         * @param sessionId indicating current session ID
         * @param responseMessage SOAP SPP response, or {@code null} in any failure.
         * Note: Called on main thread (WifiService thread).
         */
        public void handleSoapMessageResponse(int sessionId,
                @Nullable SppResponseMessage responseMessage) {
            if (sessionId != mCurrentSessionId) {
                Log.w(TAG, "Expected soapMessageResponse callback for currentSessionId="
                        + mCurrentSessionId);
                return;
            }

            if (responseMessage == null) {
                Log.e(TAG, "failed to send the sppPostDevData message");
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_SOAP_MESSAGE_EXCHANGE);
                return;
            }

            if (mState == STATE_WAITING_FOR_FIRST_SOAP_RESPONSE) {
                if (responseMessage.getMessageType()
                        != SppResponseMessage.MessageType.POST_DEV_DATA_RESPONSE) {
                    Log.e(TAG, "Expected a PostDevDataResponse, but got "
                            + responseMessage.getMessageType());
                    resetStateMachine(
                            ProvisioningCallback.OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_TYPE);
                    return;
                }

                PostDevDataResponse devDataResponse = (PostDevDataResponse) responseMessage;
                mSessionId = devDataResponse.getSessionID();
                if (devDataResponse.getSppCommand().getExecCommandId()
                        != SppCommand.ExecCommandId.BROWSER) {
                    Log.e(TAG, "Expected a launchBrowser command, but got "
                            + devDataResponse.getSppCommand().getExecCommandId());
                    resetStateMachine(ProvisioningCallback.OSU_FAILURE_UNEXPECTED_COMMAND_TYPE);
                    return;
                }

                Log.d(TAG, "Exec: " + devDataResponse.getSppCommand().getExecCommandId() + ", for '"
                        + devDataResponse.getSppCommand().getCommandData() + "'");

                mWebUrl = ((BrowserUri) devDataResponse.getSppCommand().getCommandData()).getUri();
                if (mWebUrl == null) {
                    Log.e(TAG, "No Web-Url");
                    resetStateMachine(ProvisioningCallback.OSU_FAILURE_INVALID_SERVER_URL);
                    return;
                }

                if (!mWebUrl.toLowerCase(Locale.US).contains(mSessionId.toLowerCase(Locale.US))) {
                    Log.e(TAG, "Bad or Missing session ID in webUrl");
                    resetStateMachine(ProvisioningCallback.OSU_FAILURE_INVALID_SERVER_URL);
                    return;
                }
                launchOsuWebView();
            } else if (mState == STATE_WAITING_FOR_SECOND_SOAP_RESPONSE) {
                if (responseMessage.getMessageType()
                        != SppResponseMessage.MessageType.POST_DEV_DATA_RESPONSE) {
                    Log.e(TAG, "Expected a PostDevDataResponse, but got "
                            + responseMessage.getMessageType());
                    resetStateMachine(
                            ProvisioningCallback.OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_TYPE);
                    return;
                }

                PostDevDataResponse devDataResponse = (PostDevDataResponse) responseMessage;
                if (devDataResponse.getSppCommand() == null
                        || devDataResponse.getSppCommand().getSppCommandId()
                        != SppCommand.CommandId.ADD_MO) {
                    Log.e(TAG, "Expected a ADD_MO command, but got " + (
                            (devDataResponse.getSppCommand() == null) ? "null"
                                    : devDataResponse.getSppCommand().getSppCommandId()));
                    resetStateMachine(
                            ProvisioningCallback.OSU_FAILURE_UNEXPECTED_COMMAND_TYPE);
                    return;
                }

                PasspointConfiguration passpointConfig = buildPasspointConfiguration(
                            (PpsMoData) devDataResponse.getSppCommand().getCommandData());

                // TODO(b/74244324): Implement a routine to transmit third SOAP message.
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Received an unexpected SOAP message in state=" + mState);
                }
            }
        }

        /**
         * Disconnect event received
         *
         * Note: Called on main thread (WifiService thread).
         */
        public void handleDisconnect() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Connection failed in state=" + mState);
            }
            if (mState == STATE_INIT) {
                Log.w(TAG, "Disconnect event unhandled in state=" + mState);
                return;
            }
            mNetwork = null;
            resetStateMachine(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
        }

        private void initiateServerConnection(Network network) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Initiating server connection in state=" + mState);
            }
            if (mState != STATE_OSU_AP_CONNECTED) {
                Log.wtf(TAG , "Initiating server connection aborted in invalid state=" + mState);
                return;
            }
            if (!mOsuServerConnection.connect(mServerUrl, network)) {
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_SERVER_CONNECTION);
                return;
            }
            mNetwork = network;
            changeState(STATE_OSU_SERVER_CONNECTED);
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_SERVER_CONNECTED);
        }

        private void invokeProvisioningCallback(int callbackType, int status) {
            if (mProvisioningCallback == null) {
                Log.e(TAG, "Provisioning callback " + callbackType + " with status " + status
                        + " not invoked");
                return;
            }
            try {
                if (callbackType == PROVISIONING_STATUS) {
                    mProvisioningCallback.onProvisioningStatus(status);
                } else {
                    mProvisioningCallback.onProvisioningFailure(status);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Remote Exception while posting callback type=" + callbackType
                        + " status=" + status);
            }
        }

        /**
         * Validate the OSU Server certificate based on the procedure in 7.3.2.2 of Hotspot2.0
         * rel2 spec.
         */
        private void validateServiceProvider() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Validating the service provider of OSU Server certificate in state="
                        + mState);
            }
            if (!mOsuServerConnection.validateProvider(
                    Locale.getDefault(), mOsuProvider.getFriendlyName())) {
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_SERVICE_PROVIDER_VERIFICATION);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_SERVICE_PROVIDER_VERIFIED);
            mProvisioningStateMachine.getHandler().post(() -> initSoapExchange());
        }

        /**
         * Initiates the SOAP message exchange with sending the sppPostDevData message.
         */
        private void initSoapExchange() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Initiates soap message exchange in state =" + mState);
            }

            if (mState != STATE_OSU_SERVER_CONNECTED) {
                Log.e(TAG, "Initiates soap message exchange in wrong state=" + mState);
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                return;
            }

            // Redirect uri used for signal of completion for registration process.
            final URL redirectUri = mRedirectListener.getServerUrl();

            // Sending the first sppPostDevDataRequest message.
            if (mOsuServerConnection.exchangeSoapMessage(
                    PostDevDataMessage.serializeToSoapEnvelope(mContext, mSystemInfo,
                            redirectUri.toString(),
                            SppConstants.SppReason.SUBSCRIPTION_REGISTRATION, null))) {
                invokeProvisioningCallback(PROVISIONING_STATUS,
                        ProvisioningCallback.OSU_STATUS_INIT_SOAP_EXCHANGE);
                // Move to initiate soap exchange
                changeState(STATE_WAITING_FOR_FIRST_SOAP_RESPONSE);
            } else {
                Log.e(TAG, "HttpsConnection is not established for soap message exchange");
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_SOAP_MESSAGE_EXCHANGE);
                return;
            }
        }

        private void launchOsuWebView() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "launch Osu webview in state =" + mState);
            }

            if (mState != STATE_WAITING_FOR_FIRST_SOAP_RESPONSE) {
                Log.e(TAG, "launch Osu webview in wrong state =" + mState);
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                return;
            }

            // Start the redirect server to listen the HTTP redirect response from server
            // as completion of user input.
            if (!mRedirectListener.startServer(new RedirectListener.RedirectCallback() {
                /** Called on different thread (RedirectListener thread). */
                @Override
                public void onRedirectReceived() {
                    if (mVerboseLoggingEnabled) {
                        Log.v(TAG, "Received HTTP redirect response");
                    }
                    mProvisioningStateMachine.getHandler().post(() -> handleRedirectResponse());
                }

                /** Called on main thread (WifiService thread). */
                @Override
                public void onRedirectTimedOut() {
                    if (mVerboseLoggingEnabled) {
                        Log.v(TAG, "Timed out to receive a HTTP redirect response");
                    }
                    mProvisioningStateMachine.handleTimeOutForRedirectResponse();
                }
            })) {
                Log.e(TAG, "fails to start redirect listener");
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_START_REDIRECT_LISTENER);
                return;
            }

            Intent intent = new Intent(WifiManager.ACTION_PASSPOINT_LAUNCH_OSU_VIEW);
            intent.setPackage(OSU_APP_PACKAGE);
            intent.putExtra(WifiManager.EXTRA_OSU_NETWORK, mNetwork);
            intent.putExtra(WifiManager.EXTRA_URL, mWebUrl);

            intent.setFlags(
                    Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);

            // Verify that the intent will resolve to an activity
            if (intent.resolveActivity(mContext.getPackageManager()) != null) {
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                invokeProvisioningCallback(PROVISIONING_STATUS,
                        ProvisioningCallback.OSU_STATUS_WAITING_FOR_REDIRECT_RESPONSE);
                changeState(STATE_WAITING_FOR_REDIRECT_RESPONSE);
            } else {
                Log.e(TAG, "can't resolve the activity for the intent");
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_NO_OSU_ACTIVITY_FOUND);
                return;
            }
        }

        /**
         * Initiates the second SOAP message exchange with sending the sppPostDevData message.
         */
        private void secondSoapExchange() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Initiates the second soap message exchange in state =" + mState);
            }

            if (mState != STATE_WAITING_FOR_REDIRECT_RESPONSE) {
                Log.e(TAG, "Initiates the second soap message exchange in wrong state=" + mState);
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                return;
            }

            // Sending the second sppPostDevDataRequest message.
            if (mOsuServerConnection.exchangeSoapMessage(
                    PostDevDataMessage.serializeToSoapEnvelope(mContext, mSystemInfo,
                            mRedirectListener.getServerUrl().toString(),
                            SppConstants.SppReason.USER_INPUT_COMPLETED, mSessionId))) {
                invokeProvisioningCallback(PROVISIONING_STATUS,
                        ProvisioningCallback.OSU_STATUS_SECOND_SOAP_EXCHANGE);
                changeState(STATE_WAITING_FOR_SECOND_SOAP_RESPONSE);
            } else {
                Log.e(TAG, "HttpsConnection is not established for soap message exchange");
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_SOAP_MESSAGE_EXCHANGE);
                return;
            }
        }

        private PasspointConfiguration buildPasspointConfiguration(@NonNull PpsMoData moData) {
            String moTree = moData.getPpsMoTree();

            PasspointConfiguration passpointConfiguration = PpsMoParser.parseMoText(moTree);
            if (passpointConfiguration == null) {
                Log.e(TAG, "fails to parse the MoTree");
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "The parsed PasspointConfiguration: " + passpointConfiguration);
                }
            }
            return passpointConfiguration;
        }

        private void changeState(int nextState) {
            if (nextState != mState) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Changing state from " + mState + " -> " + nextState);
                }
                mState = nextState;
            }
        }

        private void resetStateMachine(int failureCode) {
            invokeProvisioningCallback(PROVISIONING_FAILURE, failureCode);
            mRedirectListener.stopServer();
            mOsuNetworkConnection.setEventCallback(null);
            mOsuNetworkConnection.disconnectIfNeeded();
            mOsuServerConnection.setEventCallback(null);
            mOsuServerConnection.cleanup();
            changeState(STATE_INIT);
        }
    }

    /**
     * Callbacks for network and wifi events
     *
     * Note: Called on main thread (WifiService thread).
     */
    class OsuNetworkCallbacks implements OsuNetworkConnection.Callbacks {

        OsuNetworkCallbacks() {}

        @Override
        public void onConnected(Network network) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "onConnected to " + network);
            }
            if (network == null) {
                mProvisioningStateMachine.handleDisconnect();
            } else {
                mProvisioningStateMachine.handleConnectedEvent(network);
            }
        }

        @Override
        public void onDisconnected() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "onDisconnected");
            }
            mProvisioningStateMachine.handleDisconnect();
        }

        @Override
        public void onTimeOut() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Timed out waiting for connection to OSU AP");
            }
            mProvisioningStateMachine.handleDisconnect();
        }

        @Override
        public void onWifiEnabled() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "onWifiEnabled");
            }
        }

        @Override
        public void onWifiDisabled() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "onWifiDisabled");
            }
            mProvisioningStateMachine.handleWifiDisabled();
        }
    }

    /**
     * Defines the callbacks expected from OsuServerConnection
     *
     * Note: Called on main thread (WifiService thread).
     */
    public class OsuServerCallbacks {
        private final int mSessionId;

        OsuServerCallbacks(int sessionId) {
            mSessionId = sessionId;
        }

        /**
         * Returns the session ID corresponding to this callback
         *
         * @return int sessionID
         */
        public int getSessionId() {
            return mSessionId;
        }

        /**
         * Provides a server validation status for the session ID
         *
         * @param sessionId integer indicating current session ID
         * @param succeeded boolean indicating success/failure of server validation
         */
        public void onServerValidationStatus(int sessionId, boolean succeeded) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "OSU Server Validation status=" + succeeded + " sessionId=" + sessionId);
            }
            if (succeeded) {
                mProvisioningStateMachine.getHandler().post(() -> {
                    mProvisioningStateMachine.handleServerValidationSuccess(sessionId);
                });
            } else {
                mProvisioningStateMachine.getHandler().post(() -> {
                    mProvisioningStateMachine.handleServerValidationFailure(sessionId);
                });
            }
        }

        /**
         * Callback when soap message is received from server.
         *
         * @param sessionId indicating current session ID
         * @param responseMessage SOAP SPP response parsed or {@code null} in any failure
         * Note: Called on different thread (OsuServer Thread)!
         */
        public void onReceivedSoapMessage(int sessionId,
                @Nullable SppResponseMessage responseMessage) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "onReceivedSoapMessage with sessionId=" + sessionId);
            }
            mProvisioningStateMachine.getHandler().post(() ->
                    mProvisioningStateMachine.handleSoapMessageResponse(sessionId,
                            responseMessage));
        }
    }
}

