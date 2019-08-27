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

package com.android.wifitrackerlib;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Abstract base class for an entry representing a Wi-Fi network in a Wi-Fi picker.
 *
 * Clients implementing a Wi-Fi picker should receive WifiEntry objects from WifiTracker2, and rely
 * on the given API for all user-displayable information and actions on the represented network.
 */
public abstract class WifiEntry implements Comparable<WifiEntry> {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            SECURITY_NONE,
            SECURITY_WEP,
            SECURITY_PSK,
            SECURITY_EAP,
            SECURITY_OWE,
            SECURITY_SAE,
            SECURITY_EAP_SUITE_B,
            SECURITY_PSK_SAE_TRANSITION,
            SECURITY_OWE_TRANSITION,
            SECURITY_MAX_VAL
    })

    public @interface Security {}

    public static final int SECURITY_NONE = 0;
    public static final int SECURITY_WEP = 1;
    public static final int SECURITY_PSK = 2;
    public static final int SECURITY_EAP = 3;
    public static final int SECURITY_OWE = 4;
    public static final int SECURITY_SAE = 5;
    public static final int SECURITY_EAP_SUITE_B = 6;
    public static final int SECURITY_PSK_SAE_TRANSITION = 7;
    public static final int SECURITY_OWE_TRANSITION = 8;
    public static final int SECURITY_MAX_VAL = 9; // Has to be the last

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            CONNECTED_STATE_DISCONNECTED,
            CONNECTED_STATE_CONNECTED,
            CONNECTED_STATE_CONNECTING
    })

    public @interface ConnectedState {}

    public static final int CONNECTED_STATE_DISCONNECTED = 0;
    public static final int CONNECTED_STATE_CONNECTING = 1;
    public static final int CONNECTED_STATE_CONNECTED = 2;

    // Wi-Fi signal levels for displaying signal strength.
    public static final int WIFI_LEVEL_MIN = 0;
    public static final int WIFI_LEVEL_MAX = 4;
    public static final int WIFI_LEVEL_UNREACHABLE = -1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            METERED_CHOICE_AUTO,
            METERED_CHOICE_METERED,
            METERED_CHOICE_UNMETERED,
            METERED_CHOICE_UNKNOWN
    })

    public @interface MeteredChoice {}

    // User's choice whether to treat a network as metered.
    public static final int METERED_CHOICE_AUTO = 0;
    public static final int METERED_CHOICE_METERED = 1;
    public static final int METERED_CHOICE_UNMETERED = 2;
    public static final int METERED_CHOICE_UNKNOWN = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            PRIVACY_DEVICE_MAC,
            PRIVACY_RANDOMIZED_MAC,
            PRIVACY_UNKNOWN
    })

    public @interface Privacy {}

    public static final int PRIVACY_DEVICE_MAC = 0;
    public static final int PRIVACY_RANDOMIZED_MAC = 1;
    public static final int PRIVACY_UNKNOWN = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            FREQUENCY_2_4_GHZ,
            FREQUENCY_5_GHZ,
            FREQUENCY_6_GHZ,
            FREQUENCY_UNKNOWN
    })

    public @interface Frequency {}

    public static final int FREQUENCY_2_4_GHZ = 2_400;
    public static final int FREQUENCY_5_GHZ = 5_000;
    public static final int FREQUENCY_6_GHZ = 6_000;
    public static final int FREQUENCY_UNKNOWN = -1;

    // Callback associated with this WifiEntry. Subclasses should call its methods appropriately.
    protected WifiEntryCallback mListener;

    // Info available for all WifiEntries //

    /** The unique key defining a WifiEntry */
    public abstract String getKey();

    /** Returns connection state of the network defined by the CONNECTED_STATE constants */
    @ConnectedState
    public abstract int getConnectedState();

    /** Returns the display title. This is most commonly the SSID of a network. */
    public abstract String getTitle();

    /** Returns the display summary */
    public abstract String getSummary();

    /**
     * Returns the signal strength level within [WIFI_LEVEL_MIN, WIFI_LEVEL_MAX].
     * A value of WIFI_LEVEL_UNREACHABLE indicates an out of range network.
     */
    public abstract int getLevel();

    /** Returns the security type defined by the SECURITY constants */
    @Security
    public abstract int getSecurity();

    /**
     * Indicates when a network is metered or the user marked the network as metered.
     */
    public abstract boolean isMetered();

    /**
     * Returns the ConnectedInfo object pertaining to an active connection.
     *
     * Returns null if getConnectedState() != CONNECTED_STATE_CONNECTED.
     */
    public abstract ConnectedInfo getConnectedInfo();

    /**
     * Info associated with the active connection.
     */
    public static class ConnectedInfo {
        @Frequency
        public int frequencyMhz;
        public List<String> dnsServers;
        public int linkSpeedMbps;
        public String ipAddress;
        public List<String> ipv6Addresses;
        public String gateway;
        public String subnetMask;
    }

    // User actions on a network

    /** Returns whether the entry should show a connect option */
    public abstract boolean canConnect();
    /** Connects to the network */
    public abstract void connect();

    /** Returns whether the entry should show a disconnect option */
    public abstract boolean canDisconnect();
    /** Disconnects from the network */
    public abstract void disconnect();

    /** Returns whether the entry should show a forget option */
    public abstract boolean canForget();
    /** Forgets the network */
    public abstract void forget();

    // Modifiable settings

    /** Returns whether the entry should show a password input */
    public abstract boolean canSetPassword();
    /** Sets the user's password to a network */
    public abstract void setPassword(@NonNull String password);

    /**
     *  Returns the user's choice whether to treat a network as metered,
     *  defined by the METERED_CHOICE constants
     */
    @MeteredChoice
    public abstract int getMeteredChoice();
    /** Returns whether the entry should let the user choose the metered treatment of a network */
    public abstract boolean canSetMeteredChoice();
    /**
     * Sets the user's choice for treating a network as metered,
     * defined by the METERED_CHOICE constants
     */
    public abstract void setMeteredChoice(@MeteredChoice int meteredChoice);

    /** Returns whether the entry should let the user choose the MAC randomization setting */
    public abstract boolean canSetPrivacy();
    /** Returns the MAC randomization setting defined by the PRIVACY constants */
    @Privacy
    public abstract int getPrivacy();
    /** Sets the user's choice for MAC randomization defined by the PRIVACY constants */
    public abstract void setPrivacy(@Privacy int privacy);

    /** Returns whether the network has auto-join enabled */
    public abstract boolean isAutoJoinEnabled();
    /** Returns whether the user can enable/disable auto-join */
    public abstract boolean canSetAutoJoinEnabled();
    /** Sets whether a network will be auto-joined or not */
    public abstract void setAutoJoinEnabled(boolean enabled);

    /** Returns the ProxySettings for the network */
    public abstract ProxySettings getProxySettings();
    /** Returns whether the user can modify the ProxySettings for the network */
    public abstract boolean canSetProxySettings();
    /** Sets the ProxySettinsg for the network */
    public abstract void setProxySettings(@NonNull ProxySettings proxySettings);

    /** Returns the IpSettings for the network */
    public abstract IpSettings getIpSettings();
    /** Returns whether the user can set the IpSettings for the network */
    public abstract boolean canSetIpSettings();
    /** Sets the IpSettings for the network */
    public abstract void setIpSettings(@NonNull IpSettings ipSettings);

    /**
     * Data class used for proxy settings
     */
    public static class ProxySettings {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                TYPE_NONE,
                TYPE_MANUAL,
                TYPE_PROXY_AUTOCONFIG
        })

        public @interface Type {}

        public static final int TYPE_NONE = 0;
        public static final int TYPE_MANUAL = 1;
        public static final int TYPE_PROXY_AUTOCONFIG = 2;

        @Type
        public int type;
        public String hostname;
        public String port;
        public String bypassAddresses;
        public String pacUrl;
    }

    /**
     * Data class used for IP settings
     */
    public static class IpSettings {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                TYPE_DCHP,
                TYPE_STATIC
        })

        public @interface Type {}

        public static final int TYPE_DCHP = 0;
        public static final int TYPE_STATIC = 1;

        @Type
        public int type;
        public String ipAddress;
        public String gateway;
        public int prefixLength;
        public String dns1;
        public String dns2;
    }

    /**
     * Sets the callback listener for WifiEntryCallback methods.
     * Subsequent calls will overwrite the previous listener.
     */
    public void setListener(WifiEntryCallback listener) {
        mListener = listener;
    }

    /**
     * Listener for changes to the state of the WifiEntry or the result of actions on the WifiEntry.
     */
    public interface WifiEntryCallback {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                CONNECT_STATUS_SUCCESS,
                CONNECT_STATUS_FAILURE_NO_PASSWORD,
                CONNECT_STATUS_FAILURE_UNKNOWN
        })

        public @interface ConnectStatus {}

        int CONNECT_STATUS_SUCCESS = 0;
        int CONNECT_STATUS_FAILURE_NO_PASSWORD = 1;
        int CONNECT_STATUS_FAILURE_UNKNOWN = 2;

        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                CONNECT_STATUS_SUCCESS,
                CONNECT_STATUS_FAILURE_UNKNOWN
        })

        public @interface DisconnectStatus {}

        int DISCONNECT_STATUS_SUCCESS = 0;
        int DISCONNECT_STATUS_FAILURE_UNKNOWN = 1;

        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                FORGET_STATUS_SUCCESS,
                FORGET_STATUS_FAILURE_UNKNOWN
        })

        public @interface ForgetStatus {}

        int FORGET_STATUS_SUCCESS = 0;
        int FORGET_STATUS_FAILURE_UNKNOWN = 1;

        /**
         * Indicates the state of the WifiEntry has changed and clients may retrieve updates through
         * the WifiEntry getter methods.
         */
        void onUpdated();

        /**
         * Result of the connect request indicated by the CONNECT_STATUS constants.
         */
        void onConnectResult(@ConnectStatus int status);

        /**
         * Result of the disconnect request indicated by the DISCONNECT_STATUS constants.
         */
        void onDisconnectResult(@DisconnectStatus int status);

        /**
         * Result of the forget request indicated by the FORGET_STATUS constants.
         */
        void onForgetResult(@ForgetStatus int status);
    }

    // TODO (b/70983952) Come up with a sorting scheme that does the right thing.
    @Override
    public int compareTo(@NonNull WifiEntry other) {
        if (getLevel() > other.getLevel()) return -1;
        if (getLevel() < other.getLevel()) return 1;

        return getTitle().compareTo(other.getTitle());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WifiEntry)) return false;
        return getKey().equals(((WifiEntry) other).getKey());
    }
}
