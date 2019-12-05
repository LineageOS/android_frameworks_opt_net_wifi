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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * MBO-OCE related constants
 */
public class MboOceConstants {

    public static final int MBO_OCE_ATTRIBUTE_NOT_PRESENT = -1;

    /** MBO-OCE attribute Ids */
    public static final int MBO_OCE_AID_MBO_AP_CAPABILITY_INDICATION = 0x01;
    public static final int MBO_OCE_AID_NON_PREFERRED_CHANNEL_REPORT = 0x02;
    public static final int MBO_OCE_AID_CELLULAR_DATA_CAPABILITIES = 0x03;
    public static final int MBO_OCE_AID_ASSOCIATION_DISALLOWED = 0x04;
    public static final int MBO_OCE_AID_CELLULAR_DATA_CONNECTION_PREFERENCE = 0x05;
    public static final int MBO_OCE_AID_TRANSITION_REASON_CODE = 0x06;
    public static final int MBO_OCE_AID_TRANSITION_REJECTION_REASON_CODE = 0x07;
    public static final int MBO_OCE_AID_ASSOCIATION_RETRY_DELAY = 0x08;
    public static final int MBO_OCE_AID_OCE_AP_CAPABILITY_INDICATION = 0x65;
    public static final int MBO_OCE_AID_RSSI_BASED_ASSOCIATION_REJECTION = 0x66;
    public static final int MBO_OCE_AID_REDUCED_WAN_METRICS = 0x67;
    public static final int MBO_OCE_AID_RNR_COMPLETENESS = 0x68;
    public static final int MBO_OCE_AID_PROBE_SUPPRESSION_BSSIDS = 0x69;
    public static final int MBO_OCE_AID_PROBE_SUPPRESSION_SSIDS = 0x6A;

    @IntDef(prefix = { "MBO_OCE_AID_" }, value = {
            MBO_OCE_AID_MBO_AP_CAPABILITY_INDICATION,
            MBO_OCE_AID_NON_PREFERRED_CHANNEL_REPORT,
            MBO_OCE_AID_CELLULAR_DATA_CAPABILITIES,
            MBO_OCE_AID_ASSOCIATION_DISALLOWED,
            MBO_OCE_AID_CELLULAR_DATA_CONNECTION_PREFERENCE,
            MBO_OCE_AID_TRANSITION_REASON_CODE,
            MBO_OCE_AID_TRANSITION_REJECTION_REASON_CODE,
            MBO_OCE_AID_ASSOCIATION_RETRY_DELAY,
            MBO_OCE_AID_OCE_AP_CAPABILITY_INDICATION,
            MBO_OCE_AID_RSSI_BASED_ASSOCIATION_REJECTION,
            MBO_OCE_AID_REDUCED_WAN_METRICS,
            MBO_OCE_AID_RNR_COMPLETENESS,
            MBO_OCE_AID_PROBE_SUPPRESSION_BSSIDS,
            MBO_OCE_AID_PROBE_SUPPRESSION_SSIDS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MboOceAid{}

    public static final int MBO_AP_CAP_IND_ATTR_CELL_DATA_AWARE = 0x40;
}
