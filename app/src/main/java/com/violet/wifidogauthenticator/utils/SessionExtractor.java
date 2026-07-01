package com.violet.wifidogauthenticator.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts sessionId from a redirect URL using the same regex as the Python reference:
 * [?&]sessionId=([a-zA-Z0-9]+)
 */
public class SessionExtractor {

    private static final Pattern SESSION_PATTERN =
            Pattern.compile("[?&]sessionId=([a-zA-Z0-9]+)");

    /**
     * @param url the final (possibly redirected) URL string
     * @return the extracted sessionId, "sessionid_limited" if error occurs, or null if not found
     */
    public static String extract(String url) {
        if (url == null || url.isEmpty()) return null;

        // ပြင်ဆင်ထည့်သွင်းထားသောအပိုင်း - URL တွင် error.html? ပါဝင်ခြင်း ရှိ/မရှိ စစ်ဆေးခြင်း
        if (url.contains("error.html?")) {
            return "sessionid_limited";
        }

        Matcher m = SESSION_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
