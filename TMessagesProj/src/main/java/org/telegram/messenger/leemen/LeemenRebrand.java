package org.telegram.messenger.leemen;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the Telegram brand token to "Leemen" in REMOTE/cloud langpack values, so cloud translations
 * (ANY language Telegram ships, not just our bundled locales) keep our branding without us mirroring every
 * locale. Applied ONLY to the rebranded brand keys ({@link LeemenBrandStrings#KEYS}) and ONLY on the
 * display-bound {@code localeValues} map — never on the on-disk canonical text (langpack from_version /
 * delta bookkeeping must stay byte-identical to what the server sent).
 *
 * Scoping is automatic: we iterate only {@link LeemenBrandStrings#KEYS}, so KEEP-class strings
 * (login/account, external pointers, and product nouns in their own keys) are never visited. Within a
 * rebranded key the base rule additionally keeps product nouns that co-occur with a generic mention
 * (e.g. "Telegram Premium", "Telegram Ad Platform"). Idempotent: the output contains no "Telegram" token,
 * so re-running on incremental deltas never double-rebrands.
 *
 * Honest limits: a plain token swap gives no real per-language grammar (Cyrillic case endings, etc.) —
 * but our bundled ru/uk keep the brand Latin-and-indeclinable like upstream, so the swap is safe there;
 * a missed transliteration just leaves the native-script brand inside an otherwise translated string,
 * strictly better than the previous English-only fallback. Transliteration coverage can grow over time.
 */
public final class LeemenRebrand {
    private LeemenRebrand() {}

    // Product nouns kept as "Telegram X" even inside a rebranded value (KEEP-class brands). \b-anchored so
    // "Ad"/"Ads" doesn't swallow "Advanced", etc. External pointers ("for Android", Desktop, Web) are NOT
    // here on purpose — within the 206 keys we DO rebrand them (e.g. TelegramVersion -> "Leemen for Android").
    private static final String KEEP_NOUNS =
            "Premium|Stars?|Business|Passport|Ads?|Gifts?|Anti-?Spam|Wallet|Fragment|TON";

    private static final Pattern[] FIND = {
            Pattern.compile("\\bTELEGRAM\\b"),                                       // all-caps (e.g. INVITE TO TELEGRAM)
            Pattern.compile("\\bTelegram\\b(?!\\s+(?:" + KEEP_NOUNS + ")\\b)"),       // base; also covers "Telegram's" -> "Leemen's"
            Pattern.compile("텔레그램"),                              // ko
            Pattern.compile("تيليجرام|تليجرام"), // ar: تيليجرام / تليجرام
    };
    private static final String[] REPL = { "LEEMEN", "Leemen", "Leemen", "Leemen" };

    /** Rebrand one cloud string. Case-sensitive and \b-anchored so lowercase telegram.org / t.me URLs and
     *  UseProxyTelegram* are untouched. Returns the input unchanged if no token matched. */
    public static String apply(String s) {
        if (s == null || s.length() < 3) {
            return s;
        }
        String out = s;
        for (int i = 0; i < FIND.length; i++) {
            Matcher m = FIND[i].matcher(out);
            if (m.find()) {
                out = m.replaceAll(REPL[i]);
            }
        }
        return out;
    }

    /** In-place rebrand of the brand keys in a display-bound langpack map (cloud values). Iterates the
     *  ~200 brand keys, not the whole ~10k map. Safe to call repeatedly (idempotent). */
    public static void rebrandBrandStrings(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        for (String key : LeemenBrandStrings.KEYS) {
            String v = map.get(key);
            if (v == null) {
                continue;
            }
            String r = apply(v);
            if (!r.equals(v)) {
                map.put(key, r);
            }
        }
    }
}
