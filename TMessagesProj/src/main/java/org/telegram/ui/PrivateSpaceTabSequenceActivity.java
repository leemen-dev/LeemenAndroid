package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SecondSpaceController;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ManageChatTextCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/** Builder UI for the tab-tap sequence that opens Private Space. */
public class PrivateSpaceTabSequenceActivity extends BaseFragment {

    private static final int VIEW_HEADER = 0;
    private static final int VIEW_STEP = 1;
    private static final int VIEW_ADD = 2;
    private static final int VIEW_INFO = 3;
    private static final int VIEW_SHADOW = 4;
    private static final int VIEW_CLEAR = 5;

    // Tab indexes mirror MainTabsActivity ordering — keep in sync.
    private static final int INDEX_CHATS = 0;
    private static final int INDEX_CONTACTS = 1;
    private static final int INDEX_SETTINGS = 2;
    private static final int INDEX_CALLS = 3;
    private static final int INDEX_PROFILE = 4;

    public static final int TARGET_REAL = 0;

    private static final int DONE_BUTTON = 1;

    private final int target;
    private RecyclerListView listView;
    private ListAdapter adapter;
    private final ArrayList<SecondSpaceController.TabStep> steps = new ArrayList<>();
    private final ArrayList<SecondSpaceController.TabStep> originalSteps = new ArrayList<>();

    public PrivateSpaceTabSequenceActivity() {
        this(TARGET_REAL);
    }

    public PrivateSpaceTabSequenceActivity(int target) {
        this.target = target;
    }

    private int rowHeader;
    private int rowStepStart;
    private int rowStepEnd;
    private int rowAdd;
    private int rowInfo;
    private int rowShadow;
    private int rowClear;
    private int rowCount;

    @Override
    public boolean onFragmentCreate() {
        if (!SecondSpaceController.getInstance(currentAccount).isActive()) {
            return false;
        }
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        List<SecondSpaceController.TabStep> saved = ssc.getTabSequence();
        steps.addAll(saved);
        originalSteps.addAll(saved);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.PrivateSpaceSequenceTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    handleBackOrCancel();
                } else if (id == DONE_BUTTON) {
                    confirmAndApply();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem doneItem = menu.addItemWithWidth(DONE_BUTTON, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneItem.setContentDescription(LocaleController.getString(R.string.Done));

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frame;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position == rowAdd) {
                showAddStepDialog();
            } else if (position == rowClear) {
                steps.clear();
                updateRows();
                adapter.notifyDataSetChanged();
            } else if (position >= rowStepStart && position < rowStepEnd) {
                showRemoveStepDialog(position - rowStepStart);
            }
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        updateRows();
        return fragmentView;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        // Both toolbar arrow and system back route here. If user edited the sequence but didn't
        // confirm via the explicit Done button, ask before discarding.
        if (invoked) {
            handleBackOrCancel();
            return false; // don't auto-close — handleBackOrCancel decides
        }
        return super.onBackPressed(invoked);
    }

    /** Toolbar arrow / system back. Confirms before discarding unsaved changes. */
    private void handleBackOrCancel() {
        if (sameSequence(steps, originalSteps)) {
            finishFragment();
            return;
        }
        if (getParentActivity() == null) {
            finishFragment();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PrivateSpaceSequenceDiscardTitle));
        b.setMessage(LocaleController.getString(R.string.PrivateSpaceSequenceDiscardMessage));
        b.setPositiveButton(LocaleController.getString(R.string.Discard), (d, w) -> finishFragment());
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alert = b.create();
        alert.show();
        alert.redPositive();
    }

    /** Done button. Validates, saves, then shows the "test your shortcut" reminder. */
    private void confirmAndApply() {
        if (!steps.isEmpty() && !isValidSequence(steps) && getParentActivity() != null) {
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(LocaleController.getString(R.string.PrivateSpaceSequenceTitle));
            b.setMessage(LocaleController.getString(R.string.PrivateSpaceSequenceTooShort));
            b.setPositiveButton(LocaleController.getString(R.string.OK), null);
            b.show();
            return;
        }
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        if (!steps.isEmpty() && getParentActivity() != null) {
        }
        boolean hadShortcutBefore = ssc.hasConfiguredShortcut();
        ssc.setTabSequence(steps);
        boolean tested = ssc.isShortcutTested();
        if (!steps.isEmpty() && !tested && getParentActivity() != null) {
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(LocaleController.getString(R.string.PrivateSpaceSequenceConfirmTitle));
            b.setMessage(LocaleController.getString(hadShortcutBefore
                    ? R.string.PrivateSpaceSequenceConfirmMessageAgain
                    : R.string.PrivateSpaceSequenceConfirmMessage));
            b.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> finishFragment());
            b.setOnDismissListener(d -> {
                if (getParentLayout() != null && getParentLayout().getFragmentStack().contains(PrivateSpaceTabSequenceActivity.this)) {
                    finishFragment();
                }
            });
            b.show();
            return;
        }
        finishFragment();
    }

    private static boolean sameSequence(List<SecondSpaceController.TabStep> a, List<SecondSpaceController.TabStep> b) {
        return SecondSpaceController.sameSequence(a, b);
    }

    /** A sequence is valid if it contains ≥1 long-press OR ≥3 short taps. */
    private static boolean isValidSequence(List<SecondSpaceController.TabStep> seq) {
        int taps = 0;
        for (SecondSpaceController.TabStep step : seq) {
            if (step.longPress) return true;
            taps++;
        }
        return taps >= 3;
    }

    private void updateRows() {
        rowCount = 0;
        rowHeader = rowCount++;
        if (!steps.isEmpty()) {
            rowStepStart = rowCount;
            rowCount += steps.size();
            rowStepEnd = rowCount;
        } else {
            rowStepStart = -1;
            rowStepEnd = -1;
        }
        rowAdd = rowCount++;
        rowInfo = rowCount++;
        if (!steps.isEmpty()) {
            rowShadow = rowCount++;
            rowClear = rowCount++;
        } else {
            rowShadow = -1;
            rowClear = -1;
        }
    }

    private static class TabOption {
        final int tabIndex;
        final int iconRes;
        final int labelRes;
        TabOption(int tabIndex, int iconRes, int labelRes) {
            this.tabIndex = tabIndex;
            this.iconRes = iconRes;
            this.labelRes = labelRes;
        }
    }

    /** Returns only tabs that are actually visible in MainTabsActivity for this user. Exit excluded. */
    private List<TabOption> visibleTabOptions() {
        ArrayList<TabOption> list = new ArrayList<>(4);
        list.add(new TabOption(INDEX_CHATS, R.drawable.tabs_chats_24, R.string.MainTabsChats));
        list.add(new TabOption(INDEX_CONTACTS, R.drawable.tabs_contacts_24, R.string.MainTabsContacts));
        if (getUserConfig().showCallsTab) {
            list.add(new TabOption(INDEX_CALLS, R.drawable.tabs_calls_24, R.string.MainTabsCalls));
        } else {
            list.add(new TabOption(INDEX_SETTINGS, R.drawable.outline_profile_settings, R.string.Settings));
        }
        list.add(new TabOption(INDEX_PROFILE, R.drawable.outline_profile_member_24, R.string.MainTabsProfile));
        return list;
    }

    private void showAddStepDialog() {
        if (getParentActivity() == null) return;
        List<TabOption> options = visibleTabOptions();
        Context context = getParentActivity();

        android.widget.LinearLayout container = new android.widget.LinearLayout(context);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padTop = org.telegram.messenger.AndroidUtilities.dp(4);
        int padBot = org.telegram.messenger.AndroidUtilities.dp(8);
        container.setPadding(0, padTop, 0, padBot);

        AlertDialog.Builder tabPicker = new AlertDialog.Builder(context);
        tabPicker.setTitle(LocaleController.getString(R.string.PrivateSpaceSequencePickTab));
        AlertDialog[] alertRef = new AlertDialog[1];

        for (TabOption opt : options) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(context);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackground(Theme.getSelectorDrawable(false));
            int sidePad = org.telegram.messenger.AndroidUtilities.dp(20);
            int vPad = org.telegram.messenger.AndroidUtilities.dp(12);
            row.setPadding(sidePad, vPad, sidePad, vPad);

            android.widget.ImageView icon = new android.widget.ImageView(context);
            icon.setImageResource(opt.iconRes);
            icon.setColorFilter(new android.graphics.PorterDuffColorFilter(
                    Theme.getColor(Theme.key_dialogTextBlack),
                    android.graphics.PorterDuff.Mode.SRC_IN));
            int iconSize = org.telegram.messenger.AndroidUtilities.dp(24);
            android.widget.LinearLayout.LayoutParams iconLp =
                    new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
            iconLp.rightMargin = org.telegram.messenger.AndroidUtilities.dp(16);
            row.addView(icon, iconLp);

            android.widget.TextView label = new android.widget.TextView(context);
            label.setText(LocaleController.getString(opt.labelRes));
            label.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            label.setTextSize(16);
            row.addView(label, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            final int chosenTab = opt.tabIndex;
            row.setOnClickListener(v -> {
                if (alertRef[0] != null) alertRef[0].dismiss();
                showActionPicker(chosenTab);
            });
            container.addView(row, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        tabPicker.setView(container);
        tabPicker.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        alertRef[0] = tabPicker.create();
        alertRef[0].show();
    }

    private void showActionPicker(int chosenTab) {
        if (getParentActivity() == null) return;
        CharSequence[] actions = {
                LocaleController.getString(R.string.PrivateSpaceSequenceTap),
                LocaleController.getString(R.string.PrivateSpaceSequenceLongPress),
        };
        AlertDialog.Builder actionPicker = new AlertDialog.Builder(getParentActivity());
        actionPicker.setTitle(LocaleController.getString(R.string.PrivateSpaceSequencePickAction));
        actionPicker.setItems(actions, (d2, which2) -> {
            steps.add(new SecondSpaceController.TabStep(chosenTab, which2 == 1));
            updateRows();
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        actionPicker.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        actionPicker.show();
    }

    private void showRemoveStepDialog(int stepIndex) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PrivateSpaceSequenceTitle));
        b.setMessage(LocaleController.getString(R.string.PrivateSpaceSequenceRemoveStep));
        b.setPositiveButton(LocaleController.getString(R.string.Remove), (d, w) -> {
            if (stepIndex >= 0 && stepIndex < steps.size()) {
                steps.remove(stepIndex);
                updateRows();
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alert = b.create();
        alert.show();
        alert.redPositive();
    }

    private String stepLabel(SecondSpaceController.TabStep step) {
        String tabName;
        switch (step.tabIndex) {
            case INDEX_CHATS: tabName = LocaleController.getString(R.string.MainTabsChats); break;
            case INDEX_CONTACTS: tabName = LocaleController.getString(R.string.MainTabsContacts); break;
            case INDEX_SETTINGS: tabName = LocaleController.getString(R.string.Settings); break;
            case INDEX_CALLS: tabName = LocaleController.getString(R.string.MainTabsCalls); break;
            case INDEX_PROFILE: tabName = LocaleController.getString(R.string.MainTabsProfile); break;
            default: tabName = "?";
        }
        String action = step.longPress
                ? LocaleController.getString(R.string.PrivateSpaceSequenceLongPress)
                : LocaleController.getString(R.string.PrivateSpaceSequenceTap);
        return tabName + " — " + action;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int vt = holder.getItemViewType();
            return vt == VIEW_STEP || vt == VIEW_ADD || vt == VIEW_CLEAR;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_HEADER: {
                    HeaderCell cell = new HeaderCell(mContext, Theme.key_windowBackgroundWhiteBlueHeader, 21, 11, false);
                    cell.setHeight(43);
                    view = cell;
                    break;
                }
                case VIEW_STEP: {
                    TextSettingsCell cell = new TextSettingsCell(mContext);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_ADD: {
                    ManageChatTextCell cell = new ManageChatTextCell(mContext);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_INFO: {
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                }
                case VIEW_CLEAR: {
                    TextSettingsCell cell = new TextSettingsCell(mContext);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_SHADOW:
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int viewType = holder.getItemViewType();
            switch (viewType) {
                case VIEW_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.PrivateSpaceSequenceTitle));
                    break;
                }
                case VIEW_STEP: {
                    int idx = position - rowStepStart;
                    SecondSpaceController.TabStep step = steps.get(idx);
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    cell.setTextAndValue((idx + 1) + ". " + stepLabel(step), "", position != rowStepEnd - 1);
                    break;
                }
                case VIEW_ADD: {
                    ManageChatTextCell cell = (ManageChatTextCell) holder.itemView;
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
                    cell.setText(LocaleController.getString(R.string.PrivateSpaceSequenceAddStep), null, R.drawable.msg_contact_add, false);
                    break;
                }
                case VIEW_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setFixedSize(0);
                    cell.setText(LocaleController.getString(R.string.PrivateSpaceSequenceInfo));
                    break;
                }
                case VIEW_CLEAR: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                    cell.setText(LocaleController.getString(R.string.PrivateSpaceSequenceClear), false);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == rowHeader) return VIEW_HEADER;
            if (position >= rowStepStart && position < rowStepEnd) return VIEW_STEP;
            if (position == rowAdd) return VIEW_ADD;
            if (position == rowInfo) return VIEW_INFO;
            if (position == rowShadow) return VIEW_SHADOW;
            if (position == rowClear) return VIEW_CLEAR;
            return VIEW_SHADOW;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }
    }
}
