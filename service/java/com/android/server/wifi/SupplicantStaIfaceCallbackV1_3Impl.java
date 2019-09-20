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

import android.annotation.NonNull;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;

import java.util.ArrayList;

abstract class SupplicantStaIfaceCallbackV1_3Impl extends
        android.hardware.wifi.supplicant.V1_3.ISupplicantStaIfaceCallback.Stub {
    private static final String TAG = SupplicantStaIfaceCallbackV1_3Impl.class.getSimpleName();
    private final SupplicantStaIfaceHal mStaIfaceHal;
    private final String mIfaceName;
    private final SupplicantStaIfaceHal.SupplicantStaIfaceHalCallbackV1_2 mCallbackV12;

    SupplicantStaIfaceCallbackV1_3Impl(@NonNull SupplicantStaIfaceHal staIfaceHal,
            @NonNull String ifaceName) {
        mStaIfaceHal = staIfaceHal;
        mIfaceName = ifaceName;
        // Create an older callback for function delegation,
        // and it would cascadingly create older one.
        mCallbackV12 = mStaIfaceHal.new SupplicantStaIfaceHalCallbackV1_2(mIfaceName);
    }

    @Override
    public void onNetworkAdded(int id) {
        mCallbackV12.onNetworkAdded(id);
    }

    @Override
    public void onNetworkRemoved(int id) {
        mCallbackV12.onNetworkRemoved(id);
    }

    @Override
    public void onStateChanged(int newState, byte[/* 6 */] bssid, int id,
            ArrayList<Byte> ssid) {
        mCallbackV12.onStateChanged(newState, bssid, id, ssid);
    }

    @Override
    public void onAnqpQueryDone(byte[/* 6 */] bssid,
            ISupplicantStaIfaceCallback.AnqpData data,
            ISupplicantStaIfaceCallback.Hs20AnqpData hs20Data) {
        mCallbackV12.onAnqpQueryDone(bssid, data, hs20Data);
    }

    @Override
    public void onHs20IconQueryDone(byte[/* 6 */] bssid, String fileName,
            ArrayList<Byte> data) {
        mCallbackV12.onHs20IconQueryDone(bssid, fileName, data);
    }

    @Override
    public void onHs20SubscriptionRemediation(byte[/* 6 */] bssid,
            byte osuMethod, String url) {
        mCallbackV12.onHs20SubscriptionRemediation(bssid, osuMethod, url);
    }

    @Override
    public void onHs20DeauthImminentNotice(byte[/* 6 */] bssid, int reasonCode,
            int reAuthDelayInSec, String url) {
        mCallbackV12.onHs20DeauthImminentNotice(bssid, reasonCode, reAuthDelayInSec, url);
    }

    @Override
    public void onDisconnected(byte[/* 6 */] bssid, boolean locallyGenerated,
            int reasonCode) {
        mCallbackV12.onDisconnected(bssid, locallyGenerated, reasonCode);
    }

    @Override
    public void onAssociationRejected(byte[/* 6 */] bssid, int statusCode,
            boolean timedOut) {
        mCallbackV12.onAssociationRejected(bssid, statusCode, timedOut);
    }

    @Override
    public void onAuthenticationTimeout(byte[/* 6 */] bssid) {
        mCallbackV12.onAuthenticationTimeout(bssid);
    }

    @Override
    public void onBssidChanged(byte reason, byte[/* 6 */] bssid) {
        mCallbackV12.onBssidChanged(reason, bssid);
    }

    @Override
    public void onEapFailure() {
        mCallbackV12.onEapFailure();
    }

    @Override
    public void onEapFailure_1_1(int code) {
        mCallbackV12.onEapFailure_1_1(code);
    }

    @Override
    public void onWpsEventSuccess() {
        mCallbackV12.onWpsEventSuccess();
    }

    @Override
    public void onWpsEventFail(byte[/* 6 */] bssid, short configError, short errorInd) {
        mCallbackV12.onWpsEventFail(bssid, configError, errorInd);
    }

    @Override
    public void onWpsEventPbcOverlap() {
        mCallbackV12.onWpsEventPbcOverlap();
    }

    @Override
    public void onExtRadioWorkStart(int id) {
        mCallbackV12.onExtRadioWorkStart(id);
    }

    @Override
    public void onExtRadioWorkTimeout(int id) {
        mCallbackV12.onExtRadioWorkTimeout(id);
    }

    @Override
    public void onDppSuccessConfigReceived(ArrayList<Byte> ssid, String password,
            byte[] psk, int securityAkm) {
        mCallbackV12.onDppSuccessConfigReceived(
                ssid, password, psk, securityAkm);
    }

    @Override
    public void onDppSuccessConfigSent() {
        mCallbackV12.onDppSuccessConfigSent();
    }

    @Override
    public void onDppProgress(int code) {
        mCallbackV12.onDppProgress(code);
    }

    @Override
    public void onDppFailure(int code) {
        mCallbackV12.onDppFailure(code);
    }

    @Override
    public void onPmkCacheAdded(long expirationTimeInSec, ArrayList<Byte> serializedEntry) {
        int curNetworkId = mStaIfaceHal.getCurrentNetworkId(mIfaceName);
        mStaIfaceHal.addPmkCacheEntry(curNetworkId, expirationTimeInSec, serializedEntry);
        mStaIfaceHal.logCallback(
                "onPmkCacheAdded: update pmk cache for config id " + curNetworkId);
    }
}
