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

package com.android.server.wifi.rtt;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.hardware.wifi.V1_0.IWifiRttController;
import android.hardware.wifi.V1_0.IWifiRttControllerEventCallback;
import android.hardware.wifi.V1_0.RttBw;
import android.hardware.wifi.V1_0.RttCapabilities;
import android.hardware.wifi.V1_0.RttConfig;
import android.hardware.wifi.V1_0.RttPeerType;
import android.hardware.wifi.V1_0.RttPreamble;
import android.hardware.wifi.V1_0.RttResult;
import android.hardware.wifi.V1_0.RttStatus;
import android.hardware.wifi.V1_0.RttType;
import android.hardware.wifi.V1_0.WifiChannelWidthInMhz;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.net.MacAddress;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.ResponderConfig;
import android.net.wifi.rtt.ResponderLocation;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.wifi.HalDeviceManager;
import com.android.server.wifi.util.NativeUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;

/**
 * TBD
 */
public class RttNative {
    private static final String TAG = "RttNative";
    private static final boolean VDBG = false; // STOPSHIP if true
    /* package */ boolean mDbg = false;

    /** Unknown status */
    public static final int FRAMEWORK_RTT_STATUS_UNKNOWN = -1;
    /** Success */
    public static final int FRAMEWORK_RTT_STATUS_SUCCESS = 0;
    /** General failure status */
    public static final int FRAMEWORK_RTT_STATUS_FAILURE = 1;
    /** Target STA does not respond to request */
    public static final int FRAMEWORK_RTT_STATUS_FAIL_NO_RSP = 2;
    /** Request rejected. Applies to 2-sided RTT only */
    public static final int FRAMEWORK_RTT_STATUS_FAIL_REJECTED = 3;
    public static final int FRAMEWORK_RTT_STATUS_FAIL_NOT_SCHEDULED_YET = 4;
    /** Timing measurement times out */
    public static final int FRAMEWORK_RTT_STATUS_FAIL_TM_TIMEOUT = 5;
    /** Target on different channel, cannot range */
    public static final int FRAMEWORK_RTT_STATUS_FAIL_AP_ON_DIFF_CHANNEL = 6;
    /** Ranging not supported */
    public static final int FRAMEWORK_RTT_STATUS_FAIL_NO_CAPABILITY = 7;
    /** Request aborted for unknown reason */
    public static final int FRAMEWORK_RTT_STATUS_ABORTED = 8;
    /** Invalid T1-T4 timestamp */
    public static final int FRAMEWORK_RTT_STATUS_FAIL_INVALID_TS = 9;
    /** 11mc protocol failed */
    public static final int FRAMEWORK_RTT_STATUS_FAIL_PROTOCOL = 10;
    /** Request could not be scheduled */
    public static final int FRAMEWORK_RTT_STATUS_FAIL_SCHEDULE = 11;
    /** Responder cannot collaborate at time of request */
    public static final int FRAMEWORK_RTT_STATUS_FAIL_BUSY_TRY_LATER = 12;
    /** Bad request args */
    public static final int FRAMEWORK_RTT_STATUS_INVALID_REQ = 13;
    /** WiFi not enabled. */
    public static final int FRAMEWORK_RTT_STATUS_NO_WIFI = 14;
    /** Responder overrides param info, cannot range with new params */
    public static final int FRAMEWORK_RTT_STATUS_FAIL_FTM_PARAM_OVERRIDE = 15;

    /** @hide */
    @IntDef(prefix = "FRAMEWORK_RTT_STATUS_", value = {FRAMEWORK_RTT_STATUS_UNKNOWN,
            FRAMEWORK_RTT_STATUS_SUCCESS, FRAMEWORK_RTT_STATUS_FAILURE,
            FRAMEWORK_RTT_STATUS_FAIL_NO_RSP, FRAMEWORK_RTT_STATUS_FAIL_REJECTED,
            FRAMEWORK_RTT_STATUS_FAIL_NOT_SCHEDULED_YET, FRAMEWORK_RTT_STATUS_FAIL_TM_TIMEOUT,
            FRAMEWORK_RTT_STATUS_FAIL_AP_ON_DIFF_CHANNEL, FRAMEWORK_RTT_STATUS_FAIL_NO_CAPABILITY,
            FRAMEWORK_RTT_STATUS_ABORTED, FRAMEWORK_RTT_STATUS_FAIL_INVALID_TS,
            FRAMEWORK_RTT_STATUS_FAIL_PROTOCOL, FRAMEWORK_RTT_STATUS_FAIL_SCHEDULE,
            FRAMEWORK_RTT_STATUS_FAIL_BUSY_TRY_LATER, FRAMEWORK_RTT_STATUS_INVALID_REQ,
            FRAMEWORK_RTT_STATUS_NO_WIFI, FRAMEWORK_RTT_STATUS_FAIL_FTM_PARAM_OVERRIDE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrameworkRttStatus {}


    private final RttServiceImpl mRttService;
    private final HalDeviceManager mHalDeviceManager;

    private Object mLock = new Object();

    private volatile IWifiRttController mIWifiRttController;
    private volatile Capabilities mRttCapabilities;
    private final WifiRttControllerEventCallback mWifiRttControllerEventCallback;
    private static final int CONVERSION_US_TO_MS = 1_000;

    private final HalDeviceManager.InterfaceRttControllerLifecycleCallback mRttLifecycleCb =
            new HalDeviceManager.InterfaceRttControllerLifecycleCallback() {
                @Override
                public void onNewRttController(IWifiRttController controller) {
                    if (mDbg) Log.d(TAG, "onNewRttController: controller=" + controller);
                    synchronized (mLock) {
                        try {
                            controller.registerEventCallback(mWifiRttControllerEventCallback);
                        } catch (RemoteException e) {
                            Log.e(TAG, "onNewRttController: exception registering callback: " + e);
                            if (mIWifiRttController != null) {
                                mIWifiRttController = null;
                                mRttService.disable();
                            }
                            return;
                        }
                        mIWifiRttController = controller;
                        mRttService.enableIfPossible();
                        updateRttCapabilities();
                    }
                }

                @Override
                public void onRttControllerDestroyed() {
                    if (mDbg) Log.d(TAG, "onRttControllerDestroyed");
                    synchronized (mLock) {
                        mIWifiRttController = null;
                        mRttCapabilities = null;
                        mRttService.disable();
                    }
                }
            };

    public RttNative(RttServiceImpl rttService, HalDeviceManager halDeviceManager) {
        mRttService = rttService;
        mHalDeviceManager = halDeviceManager;
        mWifiRttControllerEventCallback = new WifiRttControllerEventCallback();
    }

    /**
     * Initialize the object - registering with the HAL device manager.
     */
    public void start(Handler handler) {
        synchronized (mLock) {
            mHalDeviceManager.initialize();
            mHalDeviceManager.registerStatusListener(() -> {
                if (VDBG) Log.d(TAG, "hdm.onStatusChanged");
                if (mHalDeviceManager.isStarted()) {
                    mHalDeviceManager.registerRttControllerLifecycleCallback(mRttLifecycleCb,
                            handler);
                }
            }, handler);
            if (mHalDeviceManager.isStarted()) {
                mHalDeviceManager.registerRttControllerLifecycleCallback(mRttLifecycleCb, handler);
            }
        }
    }

    /**
     * Returns true if Wi-Fi is ready for RTT requests, false otherwise.
     */
    public boolean isReady() {
        return mIWifiRttController != null;
    }

    /**
     * Returns the RTT capabilities. Will only be null when disabled (e.g. no STA interface
     * available - not necessarily up).
     */
    public @Nullable Capabilities getRttCapabilities() {
        return mRttCapabilities;
    }

    /**
     * Updates the RTT capabilities.
     */
    void updateRttCapabilities() {
        if (mIWifiRttController == null) {
            Log.e(TAG, "updateRttCapabilities: but a RTT controll is NULL!?");
            return;
        }
        if (mRttCapabilities != null) {
            return;
        }
        if (mDbg) Log.v(TAG, "updateRttCapabilities");

        synchronized (mLock) {
            try {
                mIWifiRttController.getCapabilities(
                        (status, capabilities) -> {
                            if (status.code != WifiStatusCode.SUCCESS) {
                                Log.e(TAG, "updateRttCapabilities: error requesting capabilities "
                                        + "-- code=" + status.code);
                                return;
                            }
                            if (mDbg) {
                                Log.v(TAG, "updateRttCapabilities: RTT capabilities="
                                        + capabilities);
                            }
                            mRttCapabilities = new Capabilities(capabilities);
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "updateRttCapabilities: exception requesting capabilities: " + e);
            }

            if (mRttCapabilities != null && !mRttCapabilities.rttFtmSupported) {
                Log.wtf(TAG, "Firmware indicates RTT is not supported - but device supports RTT - "
                        + "ignored!?");
            }
        }
    }

    /**
     * Issue a range request to the HAL.
     *
     * @param cmdId Command ID for the request. Will be used in the corresponding
     * {@link WifiRttControllerEventCallback#onResults(int, ArrayList)}.
     * @param request Range request.
     * @param isCalledFromPrivilegedContext Indicates whether privileged APIs are permitted,
     *                                      initially: support for one-sided RTT.
     *
     * @return Success status: true for success, false for failure.
     */
    public boolean rangeRequest(int cmdId, RangingRequest request,
            boolean isCalledFromPrivilegedContext) {
        if (mDbg) {
            Log.v(TAG,
                    "rangeRequest: cmdId=" + cmdId + ", # of requests=" + request.mRttPeers.size());
        }
        if (VDBG) Log.v(TAG, "rangeRequest: request=" + request);
        synchronized (mLock) {
            if (!isReady()) {
                Log.e(TAG, "rangeRequest: RttController is null");
                return false;
            }
            updateRttCapabilities();

            ArrayList<RttConfig> rttConfig = convertRangingRequestToRttConfigs(request,
                    isCalledFromPrivilegedContext, mRttCapabilities);
            if (rttConfig == null) {
                Log.e(TAG, "rangeRequest: invalid request parameters");
                return false;
            }
            if (rttConfig.size() == 0) {
                Log.e(TAG, "rangeRequest: all requests invalidated");
                mRttService.onRangingResults(cmdId, new ArrayList<>());
                return true;
            }

            try {
                WifiStatus status = mIWifiRttController.rangeRequest(cmdId, rttConfig);
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "rangeRequest: cannot issue range request -- code=" + status.code);
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "rangeRequest: exception issuing range request: " + e);
                return false;
            }

            return true;
        }
    }

    /**
     * Cancel an outstanding ranging request: no guarantees of execution - we will ignore any
     * results which are returned for the canceled request.
     *
     * @param cmdId The cmdId issued with the original rangeRequest command.
     * @param macAddresses A list of MAC addresses for which to cancel the operation.
     * @return Success status: true for success, false for failure.
     */
    public boolean rangeCancel(int cmdId, ArrayList<byte[]> macAddresses) {
        if (mDbg) Log.v(TAG, "rangeCancel: cmdId=" + cmdId);
        synchronized (mLock) {
            if (!isReady()) {
                Log.e(TAG, "rangeCancel: RttController is null");
                return false;
            }

            try {
                WifiStatus status = mIWifiRttController.rangeCancel(cmdId, macAddresses);
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "rangeCancel: cannot issue range cancel -- code=" + status.code);
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "rangeCancel: exception issuing range cancel: " + e);
                return false;
            }

            return true;
        }
    }

    private static ArrayList<RttConfig> convertRangingRequestToRttConfigs(RangingRequest request,
            boolean isCalledFromPrivilegedContext, Capabilities cap) {
        ArrayList<RttConfig> rttConfigs = new ArrayList<>(request.mRttPeers.size());

        // Skipping any configurations which have an error (printing out a message).
        // The caller will only get results for valid configurations.
        for (ResponderConfig responder: request.mRttPeers) {
            if (!isCalledFromPrivilegedContext) {
                if (!responder.supports80211mc) {
                    Log.e(TAG, "Invalid responder: does not support 802.11mc");
                    continue;
                }
            }

            RttConfig config = new RttConfig();

            System.arraycopy(responder.macAddress.toByteArray(), 0, config.addr, 0,
                    config.addr.length);

            try {
                config.type = responder.supports80211mc ? RttType.TWO_SIDED : RttType.ONE_SIDED;
                if (config.type == RttType.ONE_SIDED && cap != null && !cap.oneSidedRttSupported) {
                    Log.w(TAG, "Device does not support one-sided RTT");
                    continue;
                }

                config.peer = halRttPeerTypeFromResponderType(responder.responderType);
                config.channel.width = halChannelWidthFromResponderChannelWidth(
                        responder.channelWidth);
                config.channel.centerFreq = responder.frequency;
                config.channel.centerFreq0 = responder.centerFreq0;
                config.channel.centerFreq1 = responder.centerFreq1;
                config.bw = halRttChannelBandwidthFromResponderChannelWidth(responder.channelWidth);
                config.preamble = halRttPreambleFromResponderPreamble(responder.preamble);

                if (config.peer == RttPeerType.NAN) {
                    config.mustRequestLci = false;
                    config.mustRequestLcr = false;
                    config.burstPeriod = 0;
                    config.numBurst = 0;
                    config.numFramesPerBurst = 5;
                    config.numRetriesPerRttFrame = 0; // irrelevant for 2-sided RTT
                    config.numRetriesPerFtmr = 3;
                    config.burstDuration = 9;
                } else { // AP + all non-NAN requests
                    config.mustRequestLci = true;
                    config.mustRequestLcr = true;
                    config.burstPeriod = 0;
                    config.numBurst = 0;
                    config.numFramesPerBurst = 8;
                    config.numRetriesPerRttFrame = (config.type == RttType.TWO_SIDED ? 0 : 3);
                    config.numRetriesPerFtmr = 3;
                    config.burstDuration = 9;

                    if (cap != null) { // constrain parameters per device capabilities
                        config.mustRequestLci = config.mustRequestLci && cap.lciSupported;
                        config.mustRequestLcr = config.mustRequestLcr && cap.lcrSupported;
                        config.bw = halRttChannelBandwidthCapabilityLimiter(config.bw, cap);
                        config.preamble = halRttPreambleCapabilityLimiter(config.preamble, cap);
                    }
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid configuration: " + e.getMessage());
                continue;
            }

            rttConfigs.add(config);
        }

        return rttConfigs;
    }

    private static int halRttPeerTypeFromResponderType(int responderType) {
        switch (responderType) {
            case ResponderConfig.RESPONDER_AP:
                return RttPeerType.AP;
            case ResponderConfig.RESPONDER_STA:
                return RttPeerType.STA;
            case ResponderConfig.RESPONDER_P2P_GO:
                return RttPeerType.P2P_GO;
            case ResponderConfig.RESPONDER_P2P_CLIENT:
                return RttPeerType.P2P_CLIENT;
            case ResponderConfig.RESPONDER_AWARE:
                return RttPeerType.NAN;
            default:
                throw new IllegalArgumentException(
                        "halRttPeerTypeFromResponderType: bad " + responderType);
        }
    }

    private static int halChannelWidthFromResponderChannelWidth(int responderChannelWidth) {
        switch (responderChannelWidth) {
            case ResponderConfig.CHANNEL_WIDTH_20MHZ:
                return WifiChannelWidthInMhz.WIDTH_20;
            case ResponderConfig.CHANNEL_WIDTH_40MHZ:
                return WifiChannelWidthInMhz.WIDTH_40;
            case ResponderConfig.CHANNEL_WIDTH_80MHZ:
                return WifiChannelWidthInMhz.WIDTH_80;
            case ResponderConfig.CHANNEL_WIDTH_160MHZ:
                return WifiChannelWidthInMhz.WIDTH_160;
            case ResponderConfig.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return WifiChannelWidthInMhz.WIDTH_80P80;
            default:
                throw new IllegalArgumentException(
                        "halChannelWidthFromResponderChannelWidth: bad " + responderChannelWidth);
        }
    }

    private static int halRttChannelBandwidthFromResponderChannelWidth(int responderChannelWidth) {
        switch (responderChannelWidth) {
            case ResponderConfig.CHANNEL_WIDTH_20MHZ:
                return RttBw.BW_20MHZ;
            case ResponderConfig.CHANNEL_WIDTH_40MHZ:
                return RttBw.BW_40MHZ;
            case ResponderConfig.CHANNEL_WIDTH_80MHZ:
                return RttBw.BW_80MHZ;
            case ResponderConfig.CHANNEL_WIDTH_160MHZ:
            case ResponderConfig.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return RttBw.BW_160MHZ;
            default:
                throw new IllegalArgumentException(
                        "halRttChannelBandwidthFromHalBandwidth: bad " + responderChannelWidth);
        }
    }

    private static int halRttPreambleFromResponderPreamble(int responderPreamble) {
        switch (responderPreamble) {
            case ResponderConfig.PREAMBLE_LEGACY:
                return RttPreamble.LEGACY;
            case ResponderConfig.PREAMBLE_HT:
                return RttPreamble.HT;
            case ResponderConfig.PREAMBLE_VHT:
                return RttPreamble.VHT;
            default:
                throw new IllegalArgumentException(
                        "halRttPreambleFromResponderPreamble: bad " + responderPreamble);
        }
    }

    /**
     * Check to see whether the selected RTT channel bandwidth is supported by the device.
     * If not supported: return the next lower bandwidth which is supported
     * If none: throw an IllegalArgumentException.
     *
     * Note: the halRttChannelBandwidth is a single bit flag of the ones used in cap.bwSupport (HAL
     * specifications).
     */
    private static int halRttChannelBandwidthCapabilityLimiter(int halRttChannelBandwidth,
            Capabilities cap) {
        while ((halRttChannelBandwidth != 0) && ((halRttChannelBandwidth & cap.bwSupported) == 0)) {
            halRttChannelBandwidth >>= 1;
        }

        if (halRttChannelBandwidth != 0) {
            return halRttChannelBandwidth;
        }

        throw new IllegalArgumentException(
                "RTT BW=" + halRttChannelBandwidth + ", not supported by device capabilities=" + cap
                        + " - and no supported alternative");
    }

    /**
     * Check to see whether the selected RTT preamble is supported by the device.
     * If not supported: return the next "lower" preamble which is supported
     * If none: throw an IllegalArgumentException.
     *
     * Note: the halRttPreamble is a single bit flag of the ones used in cap.preambleSupport (HAL
     * specifications).
     */
    private static int halRttPreambleCapabilityLimiter(int halRttPreamble, Capabilities cap) {
        while ((halRttPreamble != 0) && ((halRttPreamble & cap.preambleSupported) == 0)) {
            halRttPreamble >>= 1;
        }

        if (halRttPreamble != 0) {
            return halRttPreamble;
        }

        throw new IllegalArgumentException(
                "RTT Preamble=" + halRttPreamble + ", not supported by device capabilities=" + cap
                        + " - and no supported alternative");
    }


    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("RttNative:");
        pw.println("  mHalDeviceManager: " + mHalDeviceManager);
        pw.println("  mIWifiRttController: " + mIWifiRttController);
        pw.println("  mRttCapabilities: " + mRttCapabilities);
    }

    /**
     *  Callback for events on 1.0 WifiRttController
     */
    private class WifiRttControllerEventCallback extends IWifiRttControllerEventCallback.Stub {
        /**
         * Callback from HAL with range results.
         *
         * @param cmdId Command ID specified in the original request
         * {@link #rangeRequest(int, RangingRequest, boolean)}.
         * @param halResults A list of range results.
         */
        @Override
        public void onResults(int cmdId, ArrayList<RttResult> halResults) {
            // sanitize HAL results
            if (halResults == null) {
                halResults = new ArrayList<>();
            }
            halResults.removeIf(Objects::isNull);
            if (mDbg) {
                Log.v(TAG, "onResults: cmdId=" + cmdId + ", # of results=" + halResults.size());
            }
            ArrayList<RangingResult> rangingResults = convertHalResultsRangingResults(halResults);
            mRttService.onRangingResults(cmdId, rangingResults);
        }

        private ArrayList<RangingResult> convertHalResultsRangingResults(
                ArrayList<RttResult> halResults) {
            ArrayList<RangingResult> rangingResults = new ArrayList<>();
            for (RttResult rttResult : halResults) {
                byte[] lci = NativeUtil.byteArrayFromArrayList(rttResult.lci.data);
                byte[] lcr = NativeUtil.byteArrayFromArrayList(rttResult.lcr.data);
                ResponderLocation responderLocation;
                try {
                    responderLocation = new ResponderLocation(lci, lcr);
                    if (!responderLocation.isValid()) {
                        responderLocation = null;
                    }
                } catch (Exception e) {
                    responderLocation = null;
                    Log.e(TAG,
                            "ResponderLocation: lci/lcr parser failed exception -- " + e);
                }
                if (rttResult.successNumber <= 1
                        && rttResult.distanceSdInMm != 0) {
                    if (mDbg) {
                        Log.w(TAG, "postProcessResults: non-zero distance stdev with 0||1 num "
                                + "samples!? result=" + rttResult);
                    }
                    rttResult.distanceSdInMm = 0;
                }
                rangingResults.add(new RangingResult(
                        convertHalStatusToFrameworkStatus(rttResult.status),
                        MacAddress.fromBytes(rttResult.addr),
                        rttResult.distanceInMm, rttResult.distanceSdInMm,
                        rttResult.rssi / -2, rttResult.numberPerBurstPeer,
                        rttResult.successNumber, lci, lcr, responderLocation,
                        rttResult.timeStampInUs / CONVERSION_US_TO_MS));
            }
            return rangingResults;
        }

        private @FrameworkRttStatus int convertHalStatusToFrameworkStatus(int halStatus) {
            switch (halStatus) {
                case RttStatus.SUCCESS:
                    return FRAMEWORK_RTT_STATUS_SUCCESS;
                case RttStatus.FAILURE:
                    return FRAMEWORK_RTT_STATUS_FAILURE;
                case RttStatus.FAIL_NO_RSP:
                    return FRAMEWORK_RTT_STATUS_FAIL_NO_RSP;
                case RttStatus.FAIL_REJECTED:
                    return FRAMEWORK_RTT_STATUS_FAIL_REJECTED;
                case RttStatus.FAIL_NOT_SCHEDULED_YET:
                    return FRAMEWORK_RTT_STATUS_FAIL_NOT_SCHEDULED_YET;
                case RttStatus.FAIL_TM_TIMEOUT:
                    return FRAMEWORK_RTT_STATUS_FAIL_TM_TIMEOUT;
                case RttStatus.FAIL_AP_ON_DIFF_CHANNEL:
                    return FRAMEWORK_RTT_STATUS_FAIL_AP_ON_DIFF_CHANNEL;
                case RttStatus.FAIL_NO_CAPABILITY:
                    return FRAMEWORK_RTT_STATUS_FAIL_NO_CAPABILITY;
                case RttStatus.ABORTED:
                    return FRAMEWORK_RTT_STATUS_ABORTED;
                case RttStatus.FAIL_INVALID_TS:
                    return FRAMEWORK_RTT_STATUS_FAIL_INVALID_TS;
                case RttStatus.FAIL_PROTOCOL:
                    return FRAMEWORK_RTT_STATUS_FAIL_PROTOCOL;
                case RttStatus.FAIL_SCHEDULE:
                    return FRAMEWORK_RTT_STATUS_FAIL_SCHEDULE;
                case RttStatus.FAIL_BUSY_TRY_LATER:
                    return FRAMEWORK_RTT_STATUS_FAIL_BUSY_TRY_LATER;
                case RttStatus.INVALID_REQ:
                    return FRAMEWORK_RTT_STATUS_INVALID_REQ;
                case RttStatus.NO_WIFI:
                    return FRAMEWORK_RTT_STATUS_NO_WIFI;
                case RttStatus.FAIL_FTM_PARAM_OVERRIDE:
                    return FRAMEWORK_RTT_STATUS_FAIL_FTM_PARAM_OVERRIDE;
                default:
                    Log.e(TAG, "Unrecognized RttStatus: " + halStatus);
                    return FRAMEWORK_RTT_STATUS_UNKNOWN;
            }
        }
    }

    /**
     * Rtt capabilities inside framework
     */
    public class Capabilities {
        //1-sided rtt measurement is supported
        public boolean oneSidedRttSupported;
        //location configuration information supported
        public boolean lciSupported;
        //location civic records supported
        public boolean lcrSupported;
        //preamble supported, see bit mask definition above
        public int preambleSupported;
        //RTT bandwidth supported
        public int bwSupported;
        // Whether STA responder role is supported.
        public boolean responderSupported;
        //Draft 11mc version supported, including major and minor version. e.g, draft 4.3 is 43.
        public byte mcVersion;
        //if ftm rtt data collection is supported.
        public boolean rttFtmSupported;

        public Capabilities(RttCapabilities rttHalCapabilities) {
            oneSidedRttSupported = rttHalCapabilities.rttOneSidedSupported;
            lciSupported = rttHalCapabilities.lciSupported;
            lcrSupported = rttHalCapabilities.lcrSupported;
            responderSupported = rttHalCapabilities.responderSupported;
            preambleSupported = rttHalCapabilities.preambleSupport;
            mcVersion = rttHalCapabilities.mcVersion;
            bwSupported = rttHalCapabilities.bwSupport;
            rttFtmSupported = rttHalCapabilities.rttFtmSupported;
        }
    }
}
