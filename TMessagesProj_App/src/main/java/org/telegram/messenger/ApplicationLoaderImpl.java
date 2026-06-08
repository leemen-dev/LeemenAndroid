package org.telegram.messenger;

import android.app.Activity;
import android.os.Build;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    // --- Crash reporting (Firebase Crashlytics) ---
    //
    // Enabled for ALL build types, INCLUDING the production release/standalone variants where
    // BuildVars.DEBUG_VERSION is false. We deliberately do NOT gate on DEBUG_VERSION (that is
    // what AppHockeyApp does) because the goal here is to receive fatal crashes from real
    // users. Fatal (uncaught) crashes are captured automatically by the Crashlytics handler
    // installed at Firebase init; this hook only enables collection and attaches a few
    // non-identifying device/version keys for triage.
    //
    // Privacy: we intentionally set NO userId / username / account info. Firebase is already
    // present in the app (FCM, remote config), so this introduces no new vendor — only crash
    // stack traces plus the device/version keys below, none of which contain message content
    // or account PII.
    @Override
    protected void startAppCenterInternal(Activity context) {
        try {
            final FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.setCrashlyticsCollectionEnabled(true);
            crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME);
            crashlytics.setCustomKey("build_type", BuildConfig.BUILD_TYPE);
            crashlytics.setCustomKey("model", Build.MODEL);
            crashlytics.setCustomKey("manufacturer", Build.MANUFACTURER);
            crashlytics.setCustomKey("device", Build.DEVICE);
            crashlytics.setCustomKey("android_sdk", Build.VERSION.SDK_INT);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // Non-fatal forwarding. Reached from FileLog.e(...) -> AndroidUtilities.appCenterLog(...).
    // In production (DEBUG_VERSION == false) FileLog does not call this path, so this mainly
    // records caught exceptions in debug/beta builds; fatal crashes always report via the
    // auto-installed handler regardless.
    @Override
    protected void appCenterLogInternal(Throwable e) {
        try {
            FirebaseCrashlytics.getInstance().recordException(e);
        } catch (Throwable ignore) {
        }
    }
}
