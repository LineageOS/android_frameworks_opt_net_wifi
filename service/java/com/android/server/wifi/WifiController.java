/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.util.WifiPermissionsUtil;

/**
 * WifiController is the class used to manage wifi state for various operating
 * modes (normal, airplane, wifi hotspot, etc.).
 */
public class WifiController extends StateMachine {
    private static final String TAG = "WifiController";
    private final Context mContext;

    // Maximum limit to use for timeout delay if the value from overlay setting is too large.
    private static final int MAX_RECOVERY_TIMEOUT_DELAY_MS = 4000;

    /* References to values tracked in WifiService */
    private final ClientModeImpl mClientModeImpl;
    private final Looper mClientModeImplLooper;
    private final ActiveModeWarden mActiveModeWarden;
    private final WifiSettingsStore mSettingsStore;
    private final FrameworkFacade mFacade;
    private final WifiPermissionsUtil mWifiPermissionsUtil;

    private final int mRecoveryDelayMillis;

    private static final int BASE = Protocol.BASE_WIFI_CONTROLLER;

    static final int CMD_EMERGENCY_MODE_CHANGED                 = BASE + 1;
    static final int CMD_SCAN_ALWAYS_MODE_CHANGED               = BASE + 7;
    static final int CMD_WIFI_TOGGLED                           = BASE + 8;
    static final int CMD_AIRPLANE_TOGGLED                       = BASE + 9;
    static final int CMD_SET_AP                                 = BASE + 10;
    static final int CMD_EMERGENCY_CALL_STATE_CHANGED           = BASE + 14;
    static final int CMD_AP_STOPPED                             = BASE + 15;
    static final int CMD_STA_START_FAILURE                      = BASE + 16;
    // Command used to trigger a wifi stack restart when in active mode
    static final int CMD_RECOVERY_RESTART_WIFI                  = BASE + 17;
    // Internal command used to complete wifi stack restart
    private static final int CMD_RECOVERY_RESTART_WIFI_CONTINUE = BASE + 18;
    // Command to disable wifi when SelfRecovery is throttled or otherwise not doing full recovery
    static final int CMD_RECOVERY_DISABLE_WIFI                  = BASE + 19;
    static final int CMD_STA_STOPPED                            = BASE + 20;
    static final int CMD_SCANNING_STOPPED                       = BASE + 21;
    static final int CMD_DEFERRED_RECOVERY_RESTART_WIFI         = BASE + 22;

    private final DefaultState mDefaultState = new DefaultState();
    private final StaEnabledState mStaEnabledState = new StaEnabledState();
    private final StaDisabledState mStaDisabledState = new StaDisabledState();
    private final StaDisabledWithScanState mStaDisabledWithScanState =
            new StaDisabledWithScanState();
    private final EcmState mEcmState = new EcmState();

    WifiController(Context context, ClientModeImpl clientModeImpl, Looper clientModeImplLooper,
                   WifiSettingsStore wss, Looper wifiServiceLooper, FrameworkFacade f,
                   ActiveModeWarden amw, WifiPermissionsUtil wifiPermissionsUtil) {
        super(TAG, wifiServiceLooper);
        mFacade = f;
        mContext = context;
        mClientModeImpl = clientModeImpl;
        mClientModeImplLooper = clientModeImplLooper;
        mActiveModeWarden = amw;
        mSettingsStore = wss;
        mWifiPermissionsUtil = wifiPermissionsUtil;

        addState(mDefaultState); {
            addState(mStaDisabledState, mDefaultState);
            addState(mStaEnabledState, mDefaultState);
            addState(mStaDisabledWithScanState, mDefaultState);
            addState(mEcmState, mDefaultState);
        }

        setLogRecSize(100);
        setLogOnlyTransitions(false);

        // register for state updates via callbacks (vs the intents registered below)
        mActiveModeWarden.registerScanOnlyCallback(state -> {
            if (state == WifiManager.WIFI_STATE_UNKNOWN) {
                Log.d(TAG, "ScanOnlyMode unexpected failure: state unknown");
            } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                Log.d(TAG, "ScanOnlyMode stopped");
                sendMessage(CMD_SCANNING_STOPPED);
            } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                // scan mode is ready to go
                Log.d(TAG, "scan mode active");
            } else {
                Log.d(TAG, "unexpected state update: " + state);
            }
        });
        mActiveModeWarden.registerClientModeCallback(state -> {
            if (state == WifiManager.WIFI_STATE_UNKNOWN) {
                logd("ClientMode unexpected failure: state unknown");
                sendMessage(CMD_STA_START_FAILURE);
            } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                logd("ClientMode stopped");
                sendMessage(CMD_STA_STOPPED);
            } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                // scan mode is ready to go
                logd("client mode active");
            } else {
                logd("unexpected state update: " + state);
            }
        });

        mRecoveryDelayMillis = readWifiRecoveryDelay();
    }

    @Override
    public void start() {
        boolean isAirplaneModeOn = mSettingsStore.isAirplaneModeOn();
        boolean isWifiEnabled = mSettingsStore.isWifiToggleEnabled();
        boolean isScanningAlwaysAvailable = mSettingsStore.isScanAlwaysAvailable();
        boolean isLocationModeActive = mWifiPermissionsUtil.isLocationModeEnabled();

        log("isAirplaneModeOn = " + isAirplaneModeOn
                + ", isWifiEnabled = " + isWifiEnabled
                + ", isScanningAvailable = " + isScanningAlwaysAvailable
                + ", isLocationModeActive = " + isLocationModeActive);

        if (checkScanOnlyModeAvailable()) {
            setInitialState(mStaDisabledWithScanState);
        } else {
            setInitialState(mStaDisabledState);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (action.equals(LocationManager.MODE_CHANGED_ACTION)) {
                            // Location mode has been toggled...  trigger with the scan change
                            // update to make sure we are in the correct mode
                            sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
                        }
                    }
                },
                new IntentFilter(filter));
        super.start();
    }

    private boolean checkScanOnlyModeAvailable() {
        return mWifiPermissionsUtil.isLocationModeEnabled()
                && mSettingsStore.isScanAlwaysAvailable();
    }

    private int readWifiRecoveryDelay() {
        int recoveryDelayMillis = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_recovery_timeout_delay);
        if (recoveryDelayMillis > MAX_RECOVERY_TIMEOUT_DELAY_MS) {
            recoveryDelayMillis = MAX_RECOVERY_TIMEOUT_DELAY_MS;
            Log.w(TAG, "Overriding timeout delay with maximum limit value");
        }
        return recoveryDelayMillis;
    }

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                case CMD_WIFI_TOGGLED:
                case CMD_SCANNING_STOPPED:
                case CMD_STA_STOPPED:
                case CMD_STA_START_FAILURE:
                case CMD_RECOVERY_RESTART_WIFI_CONTINUE:
                case CMD_DEFERRED_RECOVERY_RESTART_WIFI:
                    break;
                case CMD_RECOVERY_DISABLE_WIFI:
                    log("Recovery has been throttled, disable wifi");
                    mActiveModeWarden.shutdownWifi();
                    transitionTo(mStaDisabledState);
                    break;
                case CMD_RECOVERY_RESTART_WIFI:
                    deferMessage(obtainMessage(CMD_DEFERRED_RECOVERY_RESTART_WIFI));
                    mActiveModeWarden.shutdownWifi();
                    transitionTo(mStaDisabledState);
                    break;
                case CMD_SET_AP:
                    // note: CMD_SET_AP is handled/dropped in ECM mode - will not start here
                    if (msg.arg1 == 1) {
                        mActiveModeWarden.enterSoftAPMode((SoftApModeConfiguration) msg.obj);
                    } else {
                        mActiveModeWarden.stopSoftAPMode(msg.arg2);
                    }
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    if (mSettingsStore.isAirplaneModeOn()) {
                        log("Airplane mode toggled, shutdown all modes");
                        mActiveModeWarden.shutdownWifi();
                        transitionTo(mStaDisabledState);
                    } else {
                        log("Airplane mode disabled, determine next state");
                        if (mSettingsStore.isWifiToggleEnabled()) {
                            transitionTo(mStaEnabledState);
                        } else if (checkScanOnlyModeAvailable()) {
                            transitionTo(mStaDisabledWithScanState);
                        }
                        // wifi should remain disabled, do not need to transition
                    }
                    break;
                case CMD_EMERGENCY_CALL_STATE_CHANGED:
                case CMD_EMERGENCY_MODE_CHANGED:
                    if (msg.arg1 == 1) {
                        transitionTo(mEcmState);
                    }
                    break;
                case CMD_AP_STOPPED:
                    log("SoftAp mode disabled, determine next state");
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        transitionTo(mStaEnabledState);
                    } else if (checkScanOnlyModeAvailable()) {
                        transitionTo(mStaDisabledWithScanState);
                    }
                    // wifi should remain disabled, do not need to transition
                    break;
                default:
                    throw new RuntimeException("WifiController.handleMessage " + msg.what);
            }
            return HANDLED;
        }
    }

    class StaDisabledState extends State {
        @Override
        public void enter() {
            mActiveModeWarden.disableWifi();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        transitionTo(mStaEnabledState);
                    } else if (checkScanOnlyModeAvailable()) {
                        // only go to scan mode if we aren't in airplane mode
                        if (mSettingsStore.isAirplaneModeOn()) {
                            transitionTo(mStaDisabledWithScanState);
                        }
                    }
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    if (checkScanOnlyModeAvailable()) {
                        transitionTo(mStaDisabledWithScanState);
                    }
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 1) {
                        // remember that we were disabled, but pass the command up to start softap
                        mSettingsStore.setWifiSavedState(WifiSettingsStore.WIFI_DISABLED);
                    }
                    return NOT_HANDLED;
                case CMD_DEFERRED_RECOVERY_RESTART_WIFI:
                    // wait mRecoveryDelayMillis for letting driver clean reset.
                    sendMessageDelayed(CMD_RECOVERY_RESTART_WIFI_CONTINUE, mRecoveryDelayMillis);
                    break;
                case CMD_RECOVERY_RESTART_WIFI_CONTINUE:
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        // wifi is currently disabled but the toggle is on, must have had an
                        // interface down before the recovery triggered
                        transitionTo(mStaEnabledState);
                        break;
                    } else if (checkScanOnlyModeAvailable()) {
                        transitionTo(mStaDisabledWithScanState);
                        break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class StaEnabledState extends State {
        @Override
        public void enter() {
            log("StaEnabledState.enter()");
            mActiveModeWarden.enterClientMode();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                    if (! mSettingsStore.isWifiToggleEnabled()) {
                        if (checkScanOnlyModeAvailable()) {
                            transitionTo(mStaDisabledWithScanState);
                        } else {
                            transitionTo(mStaDisabledState);
                        }
                    }
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    // airplane mode toggled on is handled in the default state
                    if (mSettingsStore.isAirplaneModeOn()) {
                        return NOT_HANDLED;
                    } else {
                        // when airplane mode is toggled off, but wifi is on, we can keep it on
                        log("airplane mode toggled - and airplane mode is off. return handled");
                        return HANDLED;
                    }
                case CMD_STA_START_FAILURE:
                    if (!checkScanOnlyModeAvailable()) {
                        transitionTo(mStaDisabledState);
                    } else {
                        transitionTo(mStaDisabledWithScanState);
                    }
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 1) {
                        // remember that we were enabled, but pass the command up to start softap
                        mSettingsStore.setWifiSavedState(WifiSettingsStore.WIFI_ENABLED);
                    }
                    return NOT_HANDLED;
                case CMD_AP_STOPPED:
                    // already in a wifi mode, no need to check where we should go with softap
                    // stopped
                    break;
                case CMD_STA_STOPPED:
                    // Client mode stopped. head to Disabled to wait for next command
                    transitionTo(mStaDisabledState);
                    break;
                case CMD_RECOVERY_RESTART_WIFI:
                    final String bugTitle;
                    final String bugDetail;
                    if (msg.arg1 < SelfRecovery.REASON_STRINGS.length && msg.arg1 >= 0) {
                        bugDetail = SelfRecovery.REASON_STRINGS[msg.arg1];
                        bugTitle = "Wi-Fi BugReport: " + bugDetail;
                    } else {
                        bugDetail = "";
                        bugTitle = "Wi-Fi BugReport";
                    }
                    if (msg.arg1 != SelfRecovery.REASON_LAST_RESORT_WATCHDOG) {
                        new Handler(mClientModeImplLooper)
                                .post(() -> mClientModeImpl.takeBugReport(bugTitle, bugDetail));
                    }
                    // after the bug report trigger, more handling needs to be done
                    return NOT_HANDLED;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class StaDisabledWithScanState extends State {
        @Override
        public void enter() {
            // now trigger the actual mode switch in ActiveModeWarden
            mActiveModeWarden.enterScanOnlyMode();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        transitionTo(mStaEnabledState);
                    }
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    if (!checkScanOnlyModeAvailable()) {
                        log("StaDisabledWithScanState: scan no longer available");
                        transitionTo(mStaDisabledState);
                    }
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 1) {
                        // remember that we were disabled, but pass the command up to start softap
                        mSettingsStore.setWifiSavedState(WifiSettingsStore.WIFI_DISABLED);
                    }
                    return NOT_HANDLED;
                case CMD_AP_STOPPED:
                    // already in a wifi mode, no need to check where we should go with softap
                    // stopped
                    break;
                case CMD_SCANNING_STOPPED:
                    // stopped due to interface destruction - return to disabled and wait
                    log("WifiController: SCANNING_STOPPED when in scan mode -> StaDisabled");
                    transitionTo(mStaDisabledState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class EcmState extends State {
        /**
         * we can enter EcmState either because an emergency call started or because
         * emergency callback mode started. This count keeps track of how many such
         * events happened; so we can exit after all are undone
         */
        private int mEcmEntryCount;

        @Override
        public void enter() {
            mActiveModeWarden.stopSoftAPMode(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
            boolean configWiFiDisableInECBM =
                    mFacade.getConfigWiFiDisableInECBM(mContext);
            log("WifiController msg getConfigWiFiDisableInECBM "
                    + configWiFiDisableInECBM);
            if (configWiFiDisableInECBM) {
                mActiveModeWarden.shutdownWifi();
            }
            mEcmEntryCount = 1;
        }

        /**
         * Handles messages received while in EcmMode.
         */
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_EMERGENCY_CALL_STATE_CHANGED:
                case CMD_EMERGENCY_MODE_CHANGED:
                    if (msg.arg1 == 1) {
                        mEcmEntryCount++;
                    } else {
                        mEcmEntryCount--;
                    }
                    if (mEcmEntryCount <= 0) {
                        if (mSettingsStore.isWifiToggleEnabled()) {
                            transitionTo(mStaEnabledState);
                        } else if (checkScanOnlyModeAvailable()) {
                            transitionTo(mStaDisabledWithScanState);
                        } else {
                            transitionTo(mStaDisabledState);
                        }
                    }
                    return HANDLED;
                case CMD_RECOVERY_RESTART_WIFI:
                case CMD_RECOVERY_DISABLE_WIFI:
                    // do not want to restart wifi if we are in emergency mode
                    return HANDLED;
                case CMD_AP_STOPPED:
                case CMD_SCANNING_STOPPED:
                case CMD_STA_STOPPED:
                    // do not want to trigger a mode switch if we are in emergency mode
                    return HANDLED;
                case CMD_SET_AP:
                    // do not want to start softap if we are in emergency mode
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }
}
