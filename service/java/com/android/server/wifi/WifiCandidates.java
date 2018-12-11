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

package com.android.server.wifi;

import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.util.ArrayMap;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Candidates for network selection
 */
public class WifiCandidates {
    private static final String TAG = "WifiCandidates";

    /**
     * Represents a connectible candidate
     */
    public class Candidate {
        public final ScanResultMatchInfo matchInfo;
        public final MacAddress bssid;
        public final ScanDetail scanDetail;
        public final WifiConfiguration config;
        public final int evaluatorIndex;        // First evaluator to nominate this config
        public final int evaluatorScore;        // Score provided by first nominating evaluator
        public Candidate(ScanResultMatchInfo matchInfo,
                         MacAddress bssid,
                         ScanDetail scanDetail,
                         WifiConfiguration config,
                         int evaluatorIndex,
                         int evaluatorScore) {
            this.matchInfo = matchInfo;
            this.bssid = bssid;
            this.scanDetail = scanDetail;
            this.config = config;
            this.evaluatorIndex = evaluatorIndex;
            this.evaluatorScore = evaluatorScore;
        }
    }

    private final Map<Pair<ScanResultMatchInfo, MacAddress>, Candidate> mCandidates =
            new ArrayMap<>();

    /**
     * Adds a new candidate
     *
     * @returns true if added or replaced, false otherwise
     */
    public boolean add(ScanDetail scanDetail,
                    WifiConfiguration config,
                    int evaluatorIndex,
                    int evaluatorScore) {
        if (config == null) return failure();
        if (scanDetail == null) return failure();
        ScanResult scanResult = scanDetail.getScanResult();
        if (scanResult == null) return failure();
        MacAddress bssid;
        try {
            bssid = MacAddress.fromString(scanResult.BSSID);
        } catch (RuntimeException e) {
            return failWithException(e);
        }
        ScanResultMatchInfo key1 = ScanResultMatchInfo.fromWifiConfiguration(config);
        ScanResultMatchInfo key2 = ScanResultMatchInfo.fromScanResult(scanResult);
        if (!key1.equals(key2)) return failure(key1, key2);
        Pair key = Pair.create(key1, bssid);
        Candidate candidate = mCandidates.get(key);
        if (candidate != null) { // check if we want to replace this old candidate
            if (evaluatorIndex < candidate.evaluatorIndex) return failure();
            if (evaluatorIndex > candidate.evaluatorIndex) return false;
            if (evaluatorScore <= candidate.evaluatorScore) return false;
            remove(candidate);
        }
        candidate = new Candidate(key1, bssid, scanDetail, config, evaluatorIndex, evaluatorScore);
        mCandidates.put(key, candidate);
        return true;
    }

    /**
     * Removes a candidate
     */
    public boolean remove(Candidate candidate) {
        if (candidate == null) return failure();
        return mCandidates.remove(Pair.create(candidate.matchInfo, candidate.bssid), candidate);
    }

    /**
     * Returns the number of candidates (at the BSSID level)
     */
    public int size() {
        return mCandidates.size();
    }

    /**
     * Returns the candidates, grouped by network.
     */
    public Collection<Collection<Candidate>> getGroupedCandidates() {
        Map<Integer, Collection<Candidate>> candidatesForNetworkId = new ArrayMap<>();
        for (Candidate candidate : mCandidates.values()) {
            Collection<Candidate> cc = candidatesForNetworkId.get(candidate.config.networkId);
            if (cc == null) {
                cc = new ArrayList<>(2); // Guess 2 bssids per network
                candidatesForNetworkId.put(candidate.config.networkId, cc);
            }
            cc.add(candidate);
        }
        return candidatesForNetworkId.values();
    }

    /**
     * After a failure indication is returned, this may be used to get details.
     */
    public RuntimeException getLastFault() {
        return mLastFault;
    }

    /**
     * Returns the number of faults we have seen
     */
    public int getFaultCount() {
        return mFaultCount;
    }

    /**
     * Clears any recorded faults
     */
    public void clearFaults() {
        mLastFault = null;
        mFaultCount = 0;
    }

    /**
     * Controls whether to immediately raise an exception on a failure
     */
    public WifiCandidates setPicky(boolean picky) {
        mPicky = picky;
        return this;
    }

    /**
     * Records details about a failure
     *
     * This captures a stack trace, so don't bother to construct a string message, just
     * supply any culprits (convertible to strings) that might aid diagnosis.
     *
     * @returns false
     * @throws RuntimeException (if in picky mode)
     */
    private boolean failure(Object... culprits) {
        StringJoiner joiner = new StringJoiner(",");
        for (Object c : culprits) {
            joiner.add("" + c);
        }
        return failWithException(new IllegalArgumentException(joiner.toString()));
    }

    /**
     * As above, if we already have an exception.
     */
    private boolean failWithException(RuntimeException e) {
        mLastFault = e;
        mFaultCount++;
        if (mPicky) {
            throw e;
        }
        return false;
    }

    private boolean mPicky = false;
    private RuntimeException mLastFault = null;
    private int mFaultCount = 0;

}
