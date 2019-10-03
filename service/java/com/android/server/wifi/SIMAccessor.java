package com.android.server.wifi;

import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.List;

public class SIMAccessor {
    private final TelephonyManager mTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;

    public SIMAccessor(Context context) {
        // TODO(b/132188983): Inject this using WifiInjector
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        // TODO(b/132188983): Inject this using WifiInjector
        mSubscriptionManager = context.getSystemService(SubscriptionManager.class);
    }

    public List<String> getMatchingImsis(IMSIParameter mccMnc) {
        if (mccMnc == null) {
            return null;
        }
        List<String> imsis = new ArrayList<>();
        for (SubscriptionInfo sub : mSubscriptionManager.getActiveSubscriptionInfoList()) {
            String imsi =
                    mTelephonyManager.createForSubscriptionId(sub.getSubscriptionId())
                            .getSubscriberId();
            if (imsi != null && mccMnc.matchesImsi(imsi)) {
                imsis.add(imsi);
            }
        }
        return imsis.isEmpty() ? null : imsis;
    }
}
