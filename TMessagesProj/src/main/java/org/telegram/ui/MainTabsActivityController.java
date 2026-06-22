package org.telegram.ui;

public interface MainTabsActivityController {
    void setTabsVisible(boolean visible);

    /** Onboarding: show a hint pointing at the Settings bottom tab. Returns false if it can't (tab not on screen). */
    boolean showPrivateSpaceSettingsHint();

    /** Onboarding: dismiss the Settings-tab hint if showing. */
    void hidePrivateSpaceSettingsHint();
}
