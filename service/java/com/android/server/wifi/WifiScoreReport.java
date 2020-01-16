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
import android.net.Network;
import android.net.NetworkAgent;
import android.net.wifi.IScoreChangeCallback;
import android.net.wifi.IWifiConnectedNetworkScorer;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.wifi.util.ExternalCallbackTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * Class used to calculate scores for connected wifi networks and report it to the associated
 * network agent.
*/
public class WifiScoreReport {
    private static final String TAG = "WifiScoreReport";

    private static final int DUMPSYS_ENTRY_COUNT_LIMIT = 3600; // 3 hours on 3 second poll

    private boolean mVerboseLoggingEnabled = false;
    private static final long FIRST_REASONABLE_WALL_CLOCK = 1490000000000L; // mid-December 2016

    private static final long MIN_TIME_TO_KEEP_BELOW_TRANSITION_SCORE_MILLIS = 9000;
    private long mLastDownwardBreachTimeMillis = 0;

    private static final int WIFI_CONNECTED_NETWORK_SCORER_IDENTIFIER = 0;
    private static final int INVALID_SESSION_ID = -1;

    // Cache of the last score
    private int mScore = ConnectedScore.WIFI_MAX_SCORE;
    private int mExternalConnectedScore = ConnectedScore.WIFI_MAX_SCORE;
    private int mSessionId = INVALID_SESSION_ID;

    private final ScoringParams mScoringParams;
    private final Clock mClock;
    private int mSessionNumber = 0;

    ConnectedScore mAggressiveConnectedScore;
    VelocityBasedConnectedScore mVelocityBasedConnectedScore;

    /**
     * Callback proxy. See {@link WifiManager#ScoreChangeCallback}.
     */
    private class ScoreChangeCallbackProxy extends IScoreChangeCallback.Stub {
        @Override
        public void onStatusChange(int sessionId, boolean isUsable) {
            if (sessionId == INVALID_SESSION_ID || sessionId != mSessionId) {
                return;
            }
            mExternalConnectedScore = isUsable ? ConnectedScore.WIFI_TRANSITION_SCORE + 1 :
                    ConnectedScore.WIFI_TRANSITION_SCORE - 1;
            // TODO: Refactor this class to bypass and override score provided by scorer
            //  in framework
            // if (mWifiConnectedNetworkScorers.getNumCallbacks() == 0) {
            //     // donot override
            // } else {
            //     // bypass scorer in framework and use score from external scorer
            // }
        }
        @Override
        public void onTriggerUpdateOfWifiUsabilityStats(@NonNull int sessionId) {
            // TODO: Fetch WifiInfo and WifiLinkLayerStats, and trigger an update of
            // WifiUsabilityStatsEntry in WifiMetrics.
            // mWifiMetrics.updateWifiUsabilityStatsEntries(WifiInfo, WifiLinkLayerStats);
        }
    }

    private final ScoreChangeCallbackProxy mScoreChangeCallback = new ScoreChangeCallbackProxy();

    private final ExternalCallbackTracker<IWifiConnectedNetworkScorer>
            mWifiConnectedNetworkScorers;

    WifiScoreReport(ScoringParams scoringParams, Clock clock, Handler handler) {
        mScoringParams = scoringParams;
        mClock = clock;
        mAggressiveConnectedScore = new AggressiveConnectedScore(scoringParams, clock);
        mVelocityBasedConnectedScore = new VelocityBasedConnectedScore(scoringParams, clock);

        mWifiConnectedNetworkScorers =
                new ExternalCallbackTracker<IWifiConnectedNetworkScorer>(handler);
    }

    /**
     * Reset the last calculated score.
     */
    public void reset() {
        mSessionNumber++;
        mScore = ConnectedScore.WIFI_MAX_SCORE;
        mLastKnownNudCheckScore = ConnectedScore.WIFI_TRANSITION_SCORE;
        mAggressiveConnectedScore.reset();
        mVelocityBasedConnectedScore.reset();
        mLastDownwardBreachTimeMillis = 0;
        if (mVerboseLoggingEnabled) Log.d(TAG, "reset");
    }

    /**
     * Enable/Disable verbose logging in score report generation.
     */
    public void enableVerboseLogging(boolean enable) {
        mVerboseLoggingEnabled = enable;
    }

    /**
     * Calculate wifi network score based on updated link layer stats and send the score to
     * the provided network agent.
     *
     * If the score has changed from the previous value, update the WifiNetworkAgent.
     *
     * Called periodically (POLL_RSSI_INTERVAL_MSECS) about every 3 seconds.
     *
     * @param wifiInfo WifiInfo instance pointing to the currently connected network.
     * @param networkAgent NetworkAgent to be notified of new score.
     * @param wifiMetrics for reporting our scores.
     */
    public void calculateAndReportScore(WifiInfo wifiInfo, NetworkAgent networkAgent,
                                        WifiMetrics wifiMetrics) {
        if (wifiInfo.getRssi() == WifiInfo.INVALID_RSSI) {
            Log.d(TAG, "Not reporting score because RSSI is invalid");
            return;
        }
        int score;

        long millis = mClock.getWallClockMillis();
        int netId = 0;

        if (networkAgent != null) {
            final Network network = networkAgent.getNetwork();
            if (network != null) {
                netId = network.netId;
            }
        }

        mAggressiveConnectedScore.updateUsingWifiInfo(wifiInfo, millis);
        mVelocityBasedConnectedScore.updateUsingWifiInfo(wifiInfo, millis);

        int s1 = mAggressiveConnectedScore.generateScore();
        int s2 = mVelocityBasedConnectedScore.generateScore();

        score = s2;

        if (wifiInfo.getScore() > ConnectedScore.WIFI_TRANSITION_SCORE
                 && score <= ConnectedScore.WIFI_TRANSITION_SCORE
                 && wifiInfo.getTxSuccessRate() >= mScoringParams.getYippeeSkippyPacketsPerSecond()
                 && wifiInfo.getRxSuccessRate() >= mScoringParams.getYippeeSkippyPacketsPerSecond()
        ) {
            score = ConnectedScore.WIFI_TRANSITION_SCORE + 1;
        }

        if (wifiInfo.getScore() > ConnectedScore.WIFI_TRANSITION_SCORE
                 && score <= ConnectedScore.WIFI_TRANSITION_SCORE) {
            // We don't want to trigger a downward breach unless the rssi is
            // below the entry threshold.  There is noise in the measured rssi, and
            // the kalman-filtered rssi is affected by the trend, so check them both.
            // TODO(b/74613347) skip this if there are other indications to support the low score
            int entry = mScoringParams.getEntryRssi(wifiInfo.getFrequency());
            if (mVelocityBasedConnectedScore.getFilteredRssi() >= entry
                    || wifiInfo.getRssi() >= entry) {
                // Stay a notch above the transition score to reduce ambiguity.
                score = ConnectedScore.WIFI_TRANSITION_SCORE + 1;
            }
        }

        if (wifiInfo.getScore() >= ConnectedScore.WIFI_TRANSITION_SCORE
                 && score < ConnectedScore.WIFI_TRANSITION_SCORE) {
            mLastDownwardBreachTimeMillis = millis;
        } else if (wifiInfo.getScore() < ConnectedScore.WIFI_TRANSITION_SCORE
                 && score >= ConnectedScore.WIFI_TRANSITION_SCORE) {
            // Staying at below transition score for a certain period of time
            // to prevent going back to wifi network again in a short time.
            long elapsedMillis = millis - mLastDownwardBreachTimeMillis;
            if (elapsedMillis < MIN_TIME_TO_KEEP_BELOW_TRANSITION_SCORE_MILLIS) {
                score = wifiInfo.getScore();
            }
        }

        //sanitize boundaries
        if (score > ConnectedScore.WIFI_MAX_SCORE) {
            score = ConnectedScore.WIFI_MAX_SCORE;
        }
        if (score < 0) {
            score = 0;
        }

        logLinkMetrics(wifiInfo, millis, netId, s1, s2, score);

        //report score
        if (score != wifiInfo.getScore()) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "report new wifi score " + score);
            }
            wifiInfo.setScore(score);
            if (networkAgent != null) {
                networkAgent.sendNetworkScore(score);
            }
        }

        wifiMetrics.incrementWifiScoreCount(score);
        mScore = score;
    }

    private static final double TIME_CONSTANT_MILLIS = 30.0e+3;
    private static final long NUD_THROTTLE_MILLIS = 5000;
    private long mLastKnownNudCheckTimeMillis = 0;
    private int mLastKnownNudCheckScore = ConnectedScore.WIFI_TRANSITION_SCORE;
    private int mNudYes = 0;    // Counts when we voted for a NUD
    private int mNudCount = 0;  // Counts when we were told a NUD was sent

    /**
     * Recommends that a layer 3 check be done
     *
     * The caller can use this to (help) decide that an IP reachability check
     * is desirable. The check is not done here; that is the caller's responsibility.
     *
     * @return true to indicate that an IP reachability check is recommended
     */
    public boolean shouldCheckIpLayer() {
        int nud = mScoringParams.getNudKnob();
        if (nud == 0) {
            return false;
        }
        long millis = mClock.getWallClockMillis();
        long deltaMillis = millis - mLastKnownNudCheckTimeMillis;
        // Don't ever ask back-to-back - allow at least 5 seconds
        // for the previous one to finish.
        if (deltaMillis < NUD_THROTTLE_MILLIS) {
            return false;
        }
        // nud is between 1 and 10 at this point
        double deltaLevel = 11 - nud;
        // nextNudBreach is the bar the score needs to cross before we ask for NUD
        double nextNudBreach = ConnectedScore.WIFI_TRANSITION_SCORE;
        // If we were below threshold the last time we checked, then compute a new bar
        // that starts down from there and decays exponentially back up to the steady-state
        // bar. If 5 time constants have passed, we are 99% of the way there, so skip the math.
        if (mLastKnownNudCheckScore < ConnectedScore.WIFI_TRANSITION_SCORE
                && deltaMillis < 5.0 * TIME_CONSTANT_MILLIS) {
            double a = Math.exp(-deltaMillis / TIME_CONSTANT_MILLIS);
            nextNudBreach = a * (mLastKnownNudCheckScore - deltaLevel) + (1.0 - a) * nextNudBreach;
        }
        if (mScore >= nextNudBreach) {
            return false;
        }
        mNudYes++;
        return true;
    }

    /**
     * Should be called when a reachability check has been issued
     *
     * When the caller has requested an IP reachability check, calling this will
     * help to rate-limit requests via shouldCheckIpLayer()
     */
    public void noteIpCheck() {
        long millis = mClock.getWallClockMillis();
        mLastKnownNudCheckTimeMillis = millis;
        mLastKnownNudCheckScore = mScore;
        mNudCount++;
    }

    /**
     * Data for dumpsys
     *
     * These are stored as csv formatted lines
     */
    private LinkedList<String> mLinkMetricsHistory = new LinkedList<String>();

    /**
     * Data logging for dumpsys
     */
    private void logLinkMetrics(WifiInfo wifiInfo, long now, int netId,
                                int s1, int s2, int score) {
        if (now < FIRST_REASONABLE_WALL_CLOCK) return;
        double rssi = wifiInfo.getRssi();
        double filteredRssi = mVelocityBasedConnectedScore.getFilteredRssi();
        double rssiThreshold = mVelocityBasedConnectedScore.getAdjustedRssiThreshold();
        int freq = wifiInfo.getFrequency();
        int txLinkSpeed = wifiInfo.getLinkSpeed();
        int rxLinkSpeed = wifiInfo.getRxLinkSpeedMbps();
        double txSuccessRate = wifiInfo.getTxSuccessRate();
        double txRetriesRate = wifiInfo.getTxRetriesRate();
        double txBadRate = wifiInfo.getTxBadRate();
        double rxSuccessRate = wifiInfo.getRxSuccessRate();
        String s;
        try {
            String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(new Date(now));
            s = String.format(Locale.US, // Use US to avoid comma/decimal confusion
                    "%s,%d,%d,%.1f,%.1f,%.1f,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d",
                    timestamp, mSessionNumber, netId,
                    rssi, filteredRssi, rssiThreshold, freq, txLinkSpeed, rxLinkSpeed,
                    txSuccessRate, txRetriesRate, txBadRate, rxSuccessRate,
                    mNudYes, mNudCount,
                    s1, s2, score);
        } catch (Exception e) {
            Log.e(TAG, "format problem", e);
            return;
        }
        synchronized (mLinkMetricsHistory) {
            mLinkMetricsHistory.add(s);
            while (mLinkMetricsHistory.size() > DUMPSYS_ENTRY_COUNT_LIMIT) {
                mLinkMetricsHistory.removeFirst();
            }
        }
    }

    /**
     * Tag to be used in dumpsys request
     */
    public static final String DUMP_ARG = "WifiScoreReport";

    /**
     * Dump logged signal strength and traffic measurements.
     * @param fd unused
     * @param pw PrintWriter for writing dump to
     * @param args unused
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        LinkedList<String> history;
        synchronized (mLinkMetricsHistory) {
            history = new LinkedList<>(mLinkMetricsHistory);
        }
        pw.println("time,session,netid,rssi,filtered_rssi,rssi_threshold,freq,txLinkSpeed,"
                + "rxLinkSpeed,tx_good,tx_retry,tx_bad,rx_pps,nudrq,nuds,s1,s2,score");
        for (String line : history) {
            pw.println(line);
        }
        history.clear();
    }

    /**
     * Set a scorer for Wi-Fi connected network score handling.
     */
    public boolean setWifiConnectedNetworkScorer(IBinder binder,
            IWifiConnectedNetworkScorer scorer) {
        // Enforce that only a single scorer can be set successfully.
        if (mWifiConnectedNetworkScorers.getNumCallbacks() > 0) {
            Log.e(TAG, "Failed to set current scorer because one scorer is already set");
            return false;
        }
        if (!mWifiConnectedNetworkScorers.add(
                binder, scorer, WIFI_CONNECTED_NETWORK_SCORER_IDENTIFIER)) {
            Log.e(TAG, "Failed to add scorer");
            return false;
        }
        try {
            scorer.setScoreChangeCallback(mScoreChangeCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to set score change callback " + scorer, e);
        }
        return true;
    }

    /**
     * Clear an existing scorer for Wi-Fi connected network score handling.
     */
    public void clearWifiConnectedNetworkScorer() {
        mWifiConnectedNetworkScorers.clear();
    }

    /**
     * Start the registered Wi-Fi connected network scorer.
     */
    public void startConnectedNetworkScorer() {
        for (IWifiConnectedNetworkScorer scorer : mWifiConnectedNetworkScorers.getCallbacks()) {
            try {
                scorer.start(mSessionId);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to start Wifi connected network scorer " + scorer, e);
            }
        }
    }

    /**
     * Stop the registered Wi-Fi connected network scorer.
     */
    public void stopConnectedNetworkScorer() {
        for (IWifiConnectedNetworkScorer scorer : mWifiConnectedNetworkScorers.getCallbacks()) {
            try {
                scorer.stop(mSessionId);
                mSessionId = INVALID_SESSION_ID;
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to stop Wifi connected network scorer " + scorer, e);
            }
        }
    }

    /**
     * Get external Wifi connected network score.
     */
    public int getExternalConnectedScore() {
        return mExternalConnectedScore;
    }

    /**
     * Set session ID.
     * @param sessionId
     */
    public void setSessionId(int sessionId) {
        mSessionId = sessionId;
    }
}
