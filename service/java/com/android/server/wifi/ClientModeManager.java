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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.WifiNative.InterfaceCallback;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Manager WiFi in Client Mode where we connect to configured networks.
 */
public class ClientModeManager implements ActiveModeManager {
    private static final String TAG = "WifiClientModeManager";

    private final ClientModeStateMachine mStateMachine;

    private final Context mContext;
    private final WifiNative mWifiNative;
    private final WifiMetrics mWifiMetrics;
    private final SarManager mSarManager;
    private final WakeupController mWakeupController;
    private final Listener mModeListener;
    private final ClientModeImpl mClientModeImpl;

    private String mClientInterfaceName;
    private boolean mIfaceIsUp = false;

    ClientModeManager(Context context, @NonNull Looper looper, WifiNative wifiNative,
            Listener listener, WifiMetrics wifiMetrics, SarManager sarManager,
            WakeupController wakeupController, ClientModeImpl clientModeImpl) {
        mContext = context;
        mWifiNative = wifiNative;
        mModeListener = listener;
        mWifiMetrics = wifiMetrics;
        mSarManager = sarManager;
        mWakeupController = wakeupController;
        mClientModeImpl = clientModeImpl;
        mStateMachine = new ClientModeStateMachine(looper);
    }

    /**
     * Start client mode.
     */
    public void start() {
        mStateMachine.sendMessage(ClientModeStateMachine.CMD_START);
    }

    /**
     * Disconnect from any currently connected networks and stop client mode.
     */
    public void stop() {
        Log.d(TAG, " currentstate: " + getCurrentStateName());
        if (isInConnectMode()) {
            if (mIfaceIsUp) {
                updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLED);
            } else {
                updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLING);
            }
        }
        mStateMachine.quitNow();
    }

    public @ScanMode int getScanMode() {
        if (isInConnectMode()) {
            return SCAN_WITH_HIDDEN_NETWORKS;
        } else if (isInScanOnlyMode()) {
            return SCAN_WITHOUT_HIDDEN_NETWORKS;
        } else {
            return SCAN_NONE;
        }
    }

    /**
     * Switch client mode manager to scan only mode.
     */
    public void switchToScanOnlyMode() {
        mStateMachine.sendMessage(ClientModeStateMachine.CMD_SWITCH_TO_SCAN_ONLY_MODE);
    }

    /**
     * Switch client mode manager to connect mode.
     */
    public void switchToConnectMode() {
        mStateMachine.sendMessage(ClientModeStateMachine.CMD_SWITCH_TO_CONNECT_MODE);
    }

    /**
     * Dump info about this ClientMode manager.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("--Dump of ClientModeManager--");

        pw.println("current StateMachine mode: " + getCurrentStateName());
        pw.println("mClientInterfaceName: " + mClientInterfaceName);
        pw.println("mIfaceIsUp: " + mIfaceIsUp);
        mStateMachine.dump(fd, pw, args);
    }

    private String getCurrentStateName() {
        IState currentState = mStateMachine.getCurrentState();

        if (currentState != null) {
            return currentState.getName();
        }

        return "StateMachine not active";
    }

    /**
     * Update Wifi state and send the broadcast.
     * @param newState new Wifi state
     * @param currentState current wifi state
     */
    private void updateConnectModeState(int newState, int currentState) {
        if (newState == WifiManager.WIFI_STATE_UNKNOWN) {
            // do not need to broadcast failure to system
            return;
        }

        mClientModeImpl.setWifiStateForApiCalls(newState);

        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, newState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, currentState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    public boolean isInConnectMode() {
        return mStateMachine.getCurrentState() == mStateMachine.mConnectModeState;
    }

    public boolean isInScanOnlyMode() {
        return mStateMachine.getCurrentState() == mStateMachine.mScanOnlyModeState;
    }

    private class ClientModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_SWITCH_TO_SCAN_ONLY_MODE = 1;
        public static final int CMD_SWITCH_TO_CONNECT_MODE = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_INTERFACE_DESTROYED = 4;
        public static final int CMD_INTERFACE_DOWN = 5;
        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();
        private final State mScanOnlyModeState = new ScanOnlyModeState();
        private final State mConnectModeState = new ConnectModeState();

        private final InterfaceCallback mWifiNativeInterfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    Log.d(TAG, "STA iface " + ifaceName + " was destroyed, stopping client mode");

                    // we must immediately clean up state in ClientModeImpl to unregister
                    // all client mode related objects
                    // Note: onDestroyed is only called from the main Wifi thread
                    mClientModeImpl.handleIfaceDestroyed();

                    sendMessage(CMD_INTERFACE_DESTROYED);
                }
            }

            @Override
            public void onUp(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 1);
                }
            }

            @Override
            public void onDown(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 0);
                }
            }
        };

        ClientModeStateMachine(Looper looper) {
            super(TAG, looper);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mIdleState);
            addState(mStartedState);
                addState(mScanOnlyModeState, mStartedState);
                addState(mConnectModeState, mStartedState);
            // CHECKSTYLE:ON IndentationCheck

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            @Override
            public void enter() {
                Log.d(TAG, "entering IdleState");
                mClientInterfaceName = null;
                mIfaceIsUp = false;
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        // Always start in scan mode first.
                        mClientInterfaceName =
                                mWifiNative.setupInterfaceForClientInScanMode(
                                mWifiNativeInterfaceCallback);
                        if (TextUtils.isEmpty(mClientInterfaceName)) {
                            Log.e(TAG, "Failed to create ClientInterface. Sit in Idle");
                            mModeListener.onStartFailure();
                            break;
                        }
                        transitionTo(mScanOnlyModeState);
                        break;
                    default:
                        Log.d(TAG, "received an invalid message: " + message);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        private class StartedState extends State {

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }
                mIfaceIsUp = isUp;
                if (!isUp) {
                    // if the interface goes down we should exit and go back to idle state.
                    Log.d(TAG, "interface down!");
                    mStateMachine.sendMessage(CMD_INTERFACE_DOWN);
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "entering StartedState");
                mIfaceIsUp = false;
                onUpChanged(mWifiNative.isInterfaceUp(mClientInterfaceName));
            }

            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_SWITCH_TO_CONNECT_MODE:
                        updateConnectModeState(WifiManager.WIFI_STATE_ENABLING,
                                WifiManager.WIFI_STATE_DISABLED);
                        if (!mWifiNative.switchClientInterfaceToConnectivityMode(
                                mClientInterfaceName)) {
                            updateConnectModeState(WifiManager.WIFI_STATE_UNKNOWN,
                                    WifiManager.WIFI_STATE_ENABLING);
                            updateConnectModeState(WifiManager.WIFI_STATE_DISABLED,
                                    WifiManager.WIFI_STATE_UNKNOWN);
                            mModeListener.onStartFailure();
                            break;
                        }
                        transitionTo(mConnectModeState);
                        break;
                    case CMD_SWITCH_TO_SCAN_ONLY_MODE:
                        if (!mWifiNative.switchClientInterfaceToScanMode(mClientInterfaceName)) {
                            mModeListener.onStartFailure();
                            break;
                        }
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLED);
                        transitionTo(mScanOnlyModeState);
                        break;
                    case CMD_INTERFACE_DOWN:
                        Log.e(TAG, "Detected an interface down, reporting failure to "
                                + "SelfRecovery");
                        mClientModeImpl.failureDetected(SelfRecovery.REASON_STA_IFACE_DOWN);
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_INTERFACE_DESTROYED:
                        Log.d(TAG, "interface destroyed - client mode stopping");
                        mClientInterfaceName = null;
                        transitionTo(mIdleState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            /**
             * Clean up state, unregister listeners and update wifi state.
             */
            @Override
            public void exit() {
                mClientModeImpl.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);

                if (mClientInterfaceName != null) {
                    mWifiNative.teardownInterface(mClientInterfaceName);
                    mClientInterfaceName = null;
                    mIfaceIsUp = false;
                }

                // once we leave started, nothing else to do...  stop the state machine
                mStateMachine.quitNow();
                mModeListener.onStopped();
            }
        }

        private class ScanOnlyModeState extends State {
            @Override
            public void enter() {
                Log.d(TAG, "entering ScanOnlyModeState");
                mClientModeImpl.setOperationalMode(ClientModeImpl.SCAN_ONLY_MODE,
                        mClientInterfaceName);
                mModeListener.onStarted();

                // Inform sar manager that scan only is being enabled
                mSarManager.setScanOnlyWifiState(WifiManager.WIFI_STATE_ENABLED);
                mWakeupController.start();
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_SWITCH_TO_SCAN_ONLY_MODE:
                        // Already in scan only mode, ignore this command.
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                // Inform sar manager that scan only is being disabled
                mSarManager.setScanOnlyWifiState(WifiManager.WIFI_STATE_DISABLED);
                mWakeupController.stop();
            }
        }

        private class ConnectModeState extends State {
            @Override
            public void enter() {
                Log.d(TAG, "entering ConnectModeState");
                mClientModeImpl.setOperationalMode(ClientModeImpl.CONNECT_MODE,
                        mClientInterfaceName);
                mModeListener.onStarted();
                updateConnectModeState(WifiManager.WIFI_STATE_ENABLED,
                        WifiManager.WIFI_STATE_ENABLING);

                // Inform sar manager that wifi is Enabled
                mSarManager.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_SWITCH_TO_CONNECT_MODE:
                        // Already in connect mode, ignore this command.
                        break;
                    case CMD_SWITCH_TO_SCAN_ONLY_MODE:
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLED);
                        return NOT_HANDLED; // Handled in StartedState.
                    case CMD_INTERFACE_DOWN:
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_UNKNOWN);
                        return NOT_HANDLED; // Handled in StartedState.
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        if (isUp == mIfaceIsUp) {
                            break;  // no change
                        }
                        if (!isUp) {
                            if (!mClientModeImpl.isConnectedMacRandomizationEnabled()) {
                                // Handle the error case where our underlying interface went down if
                                // we do not have mac randomization enabled (b/72459123).
                                // if the interface goes down we should exit and go back to idle
                                // state.
                                updateConnectModeState(WifiManager.WIFI_STATE_UNKNOWN,
                                        WifiManager.WIFI_STATE_ENABLED);
                            } else {
                                return HANDLED; // For MAC randomization, ignore...
                            }
                        }
                        return NOT_HANDLED; // Handled in StartedState.
                    case CMD_INTERFACE_DESTROYED:
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLED);
                        return NOT_HANDLED; // Handled in StartedState.
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                updateConnectModeState(WifiManager.WIFI_STATE_DISABLED,
                        WifiManager.WIFI_STATE_DISABLING);

                // Inform sar manager that wifi is being disabled
                mSarManager.setClientWifiState(WifiManager.WIFI_STATE_DISABLED);
            }
        }
    }
}
