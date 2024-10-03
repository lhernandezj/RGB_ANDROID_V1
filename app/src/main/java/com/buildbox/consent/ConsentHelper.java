package com.buildbox.consent;

import java.util.ArrayList;
import java.util.List;

public class ConsentHelper {

    public static List<SdkConsentInfo> getSdkConsentInfos() {
        ArrayList<SdkConsentInfo> sdks = new ArrayList<>();
        /* adbox-vungle */
        sdks.add( new SdkConsentInfo("adbox-vungle", "Vungle", "https://vungle.com/privacy/"));
        /* adbox-vungle */
        return sdks;
    }

    public static String getConsentKey(String sdkId) {
        return sdkId + "_CONSENT_KEY";
    }
}
