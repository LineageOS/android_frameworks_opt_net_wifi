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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.IState;
import com.android.internal.util.Preconditions;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.util.WifiPermissionsUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * This class provides the implementation for different WiFi operating modes.
 */
public class ActiveModeWarden {
    private static final String TAG = "WifiActiveModeWarden";

    // Holder for active mode managers
    private final ArraySet<ActiveModeManager> mActiveModeManagers;
    // DefaultModeManager used to service API calls when there are not active mode managers.
    private final DefaultModeManager mDefaultModeManager;

    private final WifiInjector mWifiInjector;
    private final Looper mLooper;
    private final Handler mHandler;
    private final Context mContext;
    private final ClientModeImpl mClientModeImpl;
    private final WifiSettingsStore mSettingsStore;
    private final FrameworkFacade mFacade;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final IBatteryStats mBatteryStats;
    private final ScanRequestProxy mScanRequestProxy;
    private final WifiController mWifiController;

    private WifiManager.SoftApCallback mSoftApCallback;
    private WifiManager.SoftApCallback mLohsCallback;

    /**
     * Called from WifiServiceImpl to register a callback for notifications from SoftApManager
     */
    public void registerSoftApCallback(@NonNull WifiManager.SoftApCallback callback) {
        mSoftApCallback = callback;
    }

    /**
     * Called from WifiServiceImpl to register a callback for notifications from SoftApManager
     * for local-only hotspot.
     */
    public void registerLohsCallback(@NonNull WifiManager.SoftApCallback callback) {
        mLohsCallback = callback;
    }

    ActiveModeWarden(WifiInjector wifiInjector,
                     Looper looper,
                     WifiNative wifiNative,
                     DefaultModeManager defaultModeManager,
                     IBatteryStats batteryStats,
                     BaseWifiDiagnostics wifiDiagnostics,
                     Context context,
                     ClientModeImpl clientModeImpl,
                     WifiSettingsStore settingsStore,
                     FrameworkFacade facade,
                     WifiPermissionsUtil wifiPermissionsUtil) {
        mWifiInjector = wifiInjector;
        mLooper = looper;
        mHandler = new Handler(looper);
        mContext = context;
        mClientModeImpl = clientModeImpl;
        mSettingsStore = settingsStore;
        mFacade = facade;
        mWifiPermissionsUtil = wifiPermissionsUtil;
        mActiveModeManagers = new ArraySet<>();
        mDefaultModeManager = defaultModeManager;
        mBatteryStats = batteryStats;
        mScanRequestProxy = wifiInjector.getScanRequestProxy();
        mWifiController = new WifiController();

        wifiNative.registerStatusListener(isReady -> {
            if (!isReady) {
                mHandler.post(() -> {
                    Log.e(TAG, "One of the native daemons died. Triggering recovery");
                    wifiDiagnostics.captureBugReportData(
                            WifiDiagnostics.REPORT_REASON_WIFINATIVE_FAILURE);

                    // immediately trigger SelfRecovery if we receive a notice about an
                    // underlying daemon failure
                    // Note: SelfRecovery has a circular dependency with ActiveModeWarden and is
                    // instantiated after ActiveModeWarden, so use WifiInjector to get the instance
                    // instead of directly passing in SelfRecovery in the constructor.
                    mWifiInjector.getSelfRecovery().trigger(SelfRecovery.REASON_WIFINATIVE_FAILURE);
                });
            }
        });
    }

    /**
     * Method to enable soft ap for wifi hotspot.
     *
     * The supplied SoftApModeConfiguration includes the target softap WifiConfiguration (or null if
     * the persisted config is to be used) and the target operating mode (ex,
     * {@link WifiManager#IFACE_IP_MODE_TETHERED} {@link WifiManager#IFACE_IP_MODE_LOCAL_ONLY}).
     *
     * @param softApConfig SoftApModeConfiguration for the hostapd softap
     */
    public void enterSoftAPMode(@NonNull SoftApModeConfiguration softApConfig) {
        mHandler.post(() -> {
            Log.d(TAG, "Starting SoftApModeManager config = "
                    + softApConfig.getWifiConfiguration());

            SoftApCallbackImpl callback = new SoftApCallbackImpl(softApConfig.getTargetMode());
            ActiveModeManager manager = mWifiInjector.makeSoftApManager(callback, softApConfig);
            callback.setActiveModeManager(manager);
            manager.start();
            mActiveModeManagers.add(manager);
            updateBatteryStatsWifiState(true);
        });
    }

    /**
     * Method to stop soft ap for wifi hotspot.
     *
     * This method will stop any active softAp mode managers.
     *
     * @param mode the operating mode of APs to bring down (ex,
     *             {@link WifiManager#IFACE_IP_MODE_TETHERED} or
     *             {@link WifiManager#IFACE_IP_MODE_LOCAL_ONLY}).
     *             Use {@link WifiManager#IFACE_IP_MODE_UNSPECIFIED} to stop all APs.
     */
    public void stopSoftAPMode(int mode) {
        mHandler.post(() -> {
            for (ActiveModeManager manager : mActiveModeManagers) {
                if (!(manager instanceof SoftApManager)) continue;
                SoftApManager softApManager = (SoftApManager) manager;

                if (mode != WifiManager.IFACE_IP_MODE_UNSPECIFIED
                        && mode != softApManager.getIpMode()) {
                    continue;
                }
                softApManager.stop();
            }
            updateBatteryStatsWifiState(false);
        });
    }

    /**
     * Method to stop all active modes, for example, when toggling airplane mode.
     */
    public void shutdownWifi() {
        mHandler.post(() -> {
            for (ActiveModeManager manager : mActiveModeManagers) {
                manager.stop();
            }
            updateBatteryStatsWifiState(false);
        });
    }

    /**
     * Dump current state for active mode managers.
     *
     * Must be called from the main Wifi thread.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of " + TAG);
        pw.println("Current wifi mode: " + mWifiController.getCurrentMode());
        pw.println("NumActiveModeManagers: " + mActiveModeManagers.size());
        for (ActiveModeManager manager : mActiveModeManagers) {
            manager.dump(fd, pw, args);
        }
    }

    /**
     *  Helper class to wrap the ActiveModeManager callback objects.
     */
    private static class ModeCallback {
        private ActiveModeManager mActiveManager;

        void setActiveModeManager(ActiveModeManager manager) {
            mActiveManager = manager;
        }

        ActiveModeManager getActiveModeManager() {
            return mActiveManager;
        }
    }

    private class SoftApCallbackImpl extends ModeCallback implements WifiManager.SoftApCallback {
        private final int mMode;

        SoftApCallbackImpl(int mode) {
            Preconditions.checkArgument(mode == WifiManager.IFACE_IP_MODE_TETHERED
                    || mode == WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
            mMode = mode;
        }

        @Override
        public void onStateChanged(int state, int reason) {
            if (state == WifiManager.WIFI_AP_STATE_DISABLED) {
                mActiveModeManagers.remove(getActiveModeManager());
                updateBatteryStatsWifiState(false);
            } else if (state == WifiManager.WIFI_AP_STATE_FAILED) {
                mActiveModeManagers.remove(getActiveModeManager());
                updateBatteryStatsWifiState(false);
            }
            switch (mMode) {
                case WifiManager.IFACE_IP_MODE_TETHERED:
                    if (mSoftApCallback != null) mSoftApCallback.onStateChanged(state, reason);
                    break;
                case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                    if (mLohsCallback != null) mLohsCallback.onStateChanged(state, reason);
                    break;
            }
        }

        @Override
        public void onNumClientsChanged(int numClients) {
            switch (mMode) {
                case WifiManager.IFACE_IP_MODE_TETHERED:
                    if (mSoftApCallback != null) mSoftApCallback.onNumClientsChanged(numClients);
                    break;
                case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                    if (mLohsCallback != null) mLohsCallback.onNumClientsChanged(numClients);
                    break;
            }
        }
    }

    /**
     *  Helper method to report wifi state as on/off (doesn't matter which mode).
     *
     *  @param enabled boolean indicating that some mode has been turned on or off
     */
    private void updateBatteryStatsWifiState(boolean enabled) {
        try {
            if (enabled) {
                if (mActiveModeManagers.size() == 1) {
                    // only report wifi on if we haven't already
                    mBatteryStats.noteWifiOn();
                }
            } else {
                if (mActiveModeManagers.size() == 0) {
                    // only report if we don't have any active modes
                    mBatteryStats.noteWifiOff();
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to note battery stats in wifi");
        }
    }

    private void updateBatteryStatsScanModeActive() {
        try {
            mBatteryStats.noteWifiState(BatteryStats.WIFI_STATE_OFF_SCANNING, null);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to note battery stats in wifi");
        }
    }

    /**
     * WifiController is the class used to manage wifi state for various operating
     * modes (normal, airplane, wifi hotspot, etc.).
     */
    private class WifiController extends StateMachine {
        private static final String TAG = "WifiController";
        private static final String STATE_MACHINE_EXITED_STATE_NAME = "STATE_MACHINE_EXITED";

        // Maximum limit to use for timeout delay if the value from overlay setting is too large.
        private static final int MAX_RECOVERY_TIMEOUT_DELAY_MS = 4000;

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
        static final int CMD_SCANNING_START_FAILURE                 = BASE + 23;

        private final DefaultState mDefaultState = new DefaultState();
        private final StaEnabledState mStaEnabledState = new StaEnabledState();
        private final StaDisabledState mStaDisabledState = new StaDisabledState();
        private final StaDisabledWithScanState mStaDisabledWithScanState =
                new StaDisabledWithScanState();
        private final EcmState mEcmState = new EcmState();

        WifiController() {
            super(TAG, mLooper);

            addState(mDefaultState); {
                addState(mStaDisabledState, mDefaultState);
                addState(mStaEnabledState, mDefaultState);
                addState(mStaDisabledWithScanState, mDefaultState);
                addState(mEcmState, mDefaultState);
            }

            setLogRecSize(100);
            setLogOnlyTransitions(false);

            mRecoveryDelayMillis = readWifiRecoveryDelay();
        }

        private String getCurrentMode() {
            IState state = getCurrentState();
            return state == null ? STATE_MACHINE_EXITED_STATE_NAME : state.getName();
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
                        shutdownWifi();
                        transitionTo(mStaDisabledState);
                        break;
                    case CMD_RECOVERY_RESTART_WIFI:
                        deferMessage(obtainMessage(CMD_DEFERRED_RECOVERY_RESTART_WIFI));
                        shutdownWifi();
                        transitionTo(mStaDisabledState);
                        break;
                    case CMD_SET_AP:
                        // note: CMD_SET_AP is handled/dropped in ECM mode - will not start here
                        if (msg.arg1 == 1) {
                            enterSoftAPMode((SoftApModeConfiguration) msg.obj);
                        } else {
                            stopSoftAPMode(msg.arg2);
                        }
                        break;
                    case CMD_AIRPLANE_TOGGLED:
                        if (mSettingsStore.isAirplaneModeOn()) {
                            log("Airplane mode toggled, shutdown all modes");
                            shutdownWifi();
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

        class ModeActiveState extends State {
            protected ActiveModeManager mManager;

            @Override
            public void exit() {
                // Active states must have a mode manager, so this should not be null, but it isn't
                // obvious from the structure - add a null check here, just in case this is missed
                // in the future
                if (mManager != null) {
                    mManager.stop();
                    mActiveModeManagers.remove(mManager);
                    updateScanMode();
                }
                updateBatteryStatsWifiState(false);
            }

            // Hook to be used by sub-classes of ModeActiveState to indicate the completion of
            // bringup of the corresponding mode.
            protected void onModeActivationComplete() {
                updateScanMode();
            }

            // Update the scan state based on all active mode managers.
            // Note: This is an overkill currently because there is only 1 of scan-only or client
            // mode present today. TODO(STA+STA): multiple modes
            private void updateScanMode() {
                boolean scanEnabled = false;
                boolean scanningForHiddenNetworksEnabled = false;
                for (ActiveModeManager modeManager : mActiveModeManagers) {
                    @ActiveModeManager.ScanMode int scanState = modeManager.getScanMode();
                    switch (scanState) {
                        case ActiveModeManager.SCAN_NONE:
                            break;
                        case ActiveModeManager.SCAN_WITHOUT_HIDDEN_NETWORKS:
                            scanEnabled = true;
                            break;
                        case ActiveModeManager.SCAN_WITH_HIDDEN_NETWORKS:
                            scanEnabled = true;
                            scanningForHiddenNetworksEnabled = true;
                            break;
                    }
                }
                mScanRequestProxy.enableScanning(scanEnabled, scanningForHiddenNetworksEnabled);
            }
        }

        class StaDisabledState extends State {
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

        class StaEnabledState extends ModeActiveState {
            private class ClientListener implements ClientModeManager.Listener {
                @Override
                public void onStateChanged(int state) {
                    // make sure this listener is still active
                    if (this != mListener) {
                        log("Client mode state change from previous manager");
                        return;
                    }

                    log("State changed from client mode. state = " + state);

                    if (state == WifiManager.WIFI_STATE_UNKNOWN) {
                        // error while setting up client mode or an unexpected failure.
                        sendMessage(CMD_STA_START_FAILURE, this);
                    } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                        // client mode stopped
                        sendMessage(CMD_STA_STOPPED, this);
                    } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                        // client mode is ready to go
                        log("client mode active");
                        onModeActivationComplete();
                    } else {
                        // only care if client mode stopped or started, dropping
                    }
                }
            }
            private ClientListener mListener;


            @Override
            public void enter() {
                log("StaEnabledState.enter()");

                mListener = new ClientListener();
                mManager = mWifiInjector.makeClientModeManager(mListener);
                mManager.start();
                mActiveModeManagers.add(mManager);

                updateBatteryStatsWifiState(true);
            }

            @Override
            public void exit() {
                super.exit();
                mListener = null;
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
                        if (mListener != msg.obj) {
                            log("StaEnabledState change from previous manager");
                            break;
                        }
                        log("StaEnabledState failed, return to StaDisabled(WithScan)State.");
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
                        if (mListener != msg.obj) {
                            log("StaEnabledState change from previous manager");
                            break;
                        }
                        log("StaEnabledState stopped, return to StaDisabledState.");
                        // Client mode stopped. Head to Disabled to wait for next command.
                        // We don't check whether we should go to StaDisabledWithScanState because
                        // the STA was stopped so that (for example) SoftAP can be turned on and the
                        // device doesn't support STA+AP. If we instead entered
                        // StaDisabledWithScanState that might kill the SoftAP that we are trying to
                        // start.
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
                            mHandler.post(() -> mClientModeImpl.takeBugReport(bugTitle, bugDetail));
                        }
                        // after the bug report trigger, more handling needs to be done
                        return NOT_HANDLED;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class StaDisabledWithScanState extends ModeActiveState {
            private class ScanOnlyListener implements ScanOnlyModeManager.Listener {
                @Override
                public void onStateChanged(int state) {
                    if (this != mListener) {
                        log("ScanOnly mode state change from previous manager");
                        return;
                    }

                    if (state == WifiManager.WIFI_STATE_UNKNOWN) {
                        log("StaDisabledWithScanState mode failed");
                        // error while setting up scan mode or an unexpected failure.
                        sendMessage(CMD_SCANNING_START_FAILURE, this);
                    } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                        log("StaDisabledWithScanState stopped");
                        //scan only mode stopped
                        sendMessage(CMD_SCANNING_STOPPED, this);
                    } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                        // scan mode is ready to go
                        log("scan mode active");
                        onModeActivationComplete();
                    } else {
                        log("unexpected state update: " + state);
                    }
                }
            }
            private ScanOnlyListener mListener;

            @Override
            public void enter() {
                log("StaDisabledWithScanState.enter()");

                mListener = new ScanOnlyListener();
                mManager = mWifiInjector.makeScanOnlyModeManager(mListener);
                mManager.start();
                mActiveModeManagers.add(mManager);

                updateBatteryStatsWifiState(true);
                updateBatteryStatsScanModeActive();
            }

            @Override
            public void exit() {
                super.exit();
                mListener = null;
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
                    case CMD_SCANNING_START_FAILURE:
                    case CMD_SCANNING_STOPPED:
                        if (mListener != msg.obj) {
                            Log.d(TAG, "ScanOnly mode state change from previous manager");
                            break;
                        }
                        log("StaDisabledWithScanState "
                                + (msg.what == CMD_SCANNING_STOPPED ? "stopped" : "failed")
                                + ", return to StaDisabledState.");
                        // stopped due to interface destruction - return to disabled and wait
                        transitionTo(mStaDisabledState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class EcmState extends ModeActiveState {
            /**
             * we can enter EcmState either because an emergency call started or because
             * emergency callback mode started. This count keeps track of how many such
             * events happened; so we can exit after all are undone
             */
            private int mEcmEntryCount;

            @Override
            public void enter() {
                stopSoftAPMode(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
                boolean configWiFiDisableInECBM =
                        mFacade.getConfigWiFiDisableInECBM(mContext);
                log("WifiController msg getConfigWiFiDisableInECBM "
                        + configWiFiDisableInECBM);
                if (configWiFiDisableInECBM) {
                    shutdownWifi();
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
}
