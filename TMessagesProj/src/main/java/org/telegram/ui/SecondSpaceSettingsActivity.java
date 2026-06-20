package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SecondSpaceController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ManageChatTextCell;
import org.telegram.ui.Cells.ManageChatUserCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Set;

public class SecondSpaceSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int VIEW_USER = 0;
    private static final int VIEW_INFO = 1;
    private static final int VIEW_ADD = 2;
    private static final int VIEW_HEADER = 3;
    private static final int VIEW_SHADOW = 4;
    private static final int VIEW_SWITCH = 5;
    private static final int VIEW_VALUE = 6;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private final ArrayList<Long> hiddenIds = new ArrayList<>();

    private int rowCount;
    private int premiumRow;
    private int premiumShadowRow;
    private int addChatRow;
    private int addChatInfoRow;
    private int chatsHeaderRow;
    private int chatsStartRow;
    private int chatsEndRow;
    private int chatsShadowRow;
    private int switchRow;
    private int switchInfoRow;
    private int screenshotShadowRow;
    private int screenshotRow;
    private int screenshotInfoRow;
    private int passwordShadowRow;
    private int passwordRow;
    private int passwordTimeoutRow;
    private int passwordInfoRow;
    private int sequenceShadowRow;
    private int sequenceRow;
    private int sequenceInfoRow;
    private int pinInSearchRow;
    private int pinInSearchInfoRow;
    private int hiddenAccountsShadowRow;
    private int addHiddenAccountRow;
    private int hiddenAccountsHeaderRow;
    private int hiddenAccountsStartRow;
    private int hiddenAccountsEndRow;
    private int hiddenAccountsInfoRow;
    private int switchPasswordShadowRow;
    private int switchPasswordRow;
    private int switchPasswordInfoRow;
    private int deleteShadowRow;
    private int deleteLeemenRow;
    private int deleteTelegramRow;
    /** Accounts currently in the hide-list (shown as removable rows). */
    private final ArrayList<Integer> hiddenAccountsList = new ArrayList<>();
    /** Whether any OTHER activated account exists at all (gates the whole section). */
    private boolean hasOtherAccounts;

    @Override
    public boolean onFragmentCreate() {
        // Deniability guard: settings accessible only while inside private space.
        if (!SecondSpaceController.getInstance(currentAccount).isActive()) {
            return false;
        }
        getNotificationCenter().addObserver(this, NotificationCenter.secondSpaceModeChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.secondSpaceModeChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.secondSpaceModeChanged) {
            SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
            if (!ssc.isActive()) {
                finishFragment();
                return;
            }
            // Entry-method changes (PIN/sequence/pin-in-search) force the entry-button toggle
            // back to ON via clearShortcutTested; rebind so the toggle row reflects current state.
            updateRows();
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.PrivateSpaceTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position == premiumRow) {
                presentFragment(new LeemenPremiumActivity());
            } else if (position == addChatRow) {
                openChatPicker();
            } else if (position == switchRow) {
                onEntryButtonSwitchClick((TextCheckCell) view);
            } else if (position == screenshotRow) {
                onScreenshotSwitchClick((TextCheckCell) view);
            } else if (position == passwordRow) {
                onPasswordRowClick();
            } else if (position == passwordTimeoutRow) {
                showPinTimeoutPicker();
            } else if (position == sequenceRow) {
                presentFragment(new PrivateSpaceTabSequenceActivity());
            } else if (position == pinInSearchRow) {
                onPinInSearchSwitchClick((TextCheckCell) view);
            } else if (position == addHiddenAccountRow) {
                openAccountPicker();
            } else if (position == switchPasswordRow) {
                onSwitchPasswordRowClick();
            } else if (position == deleteLeemenRow) {
                confirmDeleteLeemenAccount();
            } else if (position == deleteTelegramRow) {
                confirmDeleteTelegramAccount();
            } else if (position >= hiddenAccountsStartRow && position < hiddenAccountsEndRow) {
                confirmRemoveAccount(hiddenAccountsList.get(position - hiddenAccountsStartRow));
            } else if (position >= chatsStartRow && position < chatsEndRow) {
                long dialogId = hiddenIds.get(position - chatsStartRow);
                confirmRemove(dialogId);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= chatsStartRow && position < chatsEndRow) {
                confirmRemove(hiddenIds.get(position - chatsStartRow));
                return true;
            }
            if (position >= hiddenAccountsStartRow && position < hiddenAccountsEndRow) {
                confirmRemoveAccount(hiddenAccountsList.get(position - hiddenAccountsStartRow));
                return true;
            }
            return false;
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        reloadHiddenIds();
        return fragmentView;
    }

    private void openChatPicker() {
        Bundle args = new Bundle();
        args.putBoolean("isAlwaysShare", true);
        args.putInt("chatAddType", 2); // FILTER — accepts users + chats + channels
        GroupCreateActivity fragment = new GroupCreateActivity(args);
        fragment.setDelegate((premium, miniapps, ids) -> {
            SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
            // addToCurrentSpace targets whichever space is currently active (real or fake),
            // so the picker mutates the right membership set without explicit branching.
            // Free tier caps hidden chats at MAX_HIDDEN_CHATS_FREE; add as many as fit, then
            // surface the Leemen Premium upsell if the selection exceeded the limit.
            boolean blockedByLimit = false;
            for (Long id : ids) {
                if (id == null) continue;
                if (!ssc.canAddChats(1)) {
                    blockedByLimit = true;
                    break;
                }
                ssc.addToCurrentSpace(id);
            }
            reloadHiddenIds();
            if (blockedByLimit) {
                LeemenPremiumActivity.showUpgradeDialog(SecondSpaceSettingsActivity.this);
            }
        });
        presentFragment(fragment);
    }

    private void confirmRemove(long dialogId) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.PrivateSpaceTitle));
        builder.setMessage(LocaleController.getString(R.string.PrivateSpaceRemoveChatConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.Remove), (d, w) -> {
            SecondSpaceController.getInstance(currentAccount).removeFromCurrentSpace(dialogId);
            reloadHiddenIds();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alert = builder.create();
        alert.show();
        alert.redPositive();
    }

    private void confirmDeleteLeemenAccount() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.DeleteLeemenAccount));
        b.setMessage(LocaleController.getString(R.string.DeleteLeemenAccountConfirm));
        b.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
            progress.setCanCancel(false);
            progress.show();
            // Variant B: server erasure → log out all Leemen clients → this device to a clean first-run.
            org.telegram.messenger.leemen.LeemenAccount.deleteAndLogoutEverywhere(currentAccount, () -> {
                try { progress.dismiss(); } catch (Exception ignore) {}
            });
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alert = b.create();
        alert.show();
        alert.redPositive();
    }

    private void confirmDeleteTelegramAccount() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.DeleteTelegramAccount));
        b.setMessage(LocaleController.getString(R.string.DeleteTelegramAccountConfirm));
        b.setPositiveButton(LocaleController.getString(R.string.Continue), (d, w) -> {
            try {
                getParentActivity().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://my.telegram.org/auth?to=deactivate")));
            } catch (Throwable ignore) {}
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alert = b.create();
        alert.show();
        alert.redPositive();
    }

    private void onPasswordRowClick() {
        if (getParentActivity() == null) return;
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        if (ssc.hasPassword()) {
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(LocaleController.getString(R.string.PrivateSpacePinTitle));
            b.setMessage(LocaleController.getString(R.string.PrivateSpacePinRemoveConfirm));
            b.setPositiveButton(LocaleController.getString(R.string.Remove), (d, w) -> {
                boolean wasVisible = ssc.isEntryButtonVisible();
                presentFragment(new PrivateSpacePasscodeActivity(PrivateSpacePasscodeActivity.MODE_REMOVE)
                        .setOnSuccess(() -> {
                            // PIN removal disables pin-in-search and rebalances row layout.
                            SecondSpaceController.getInstance(currentAccount).setPinInSearchEnabled(false);
                            updateRows();
                            if (adapter != null) adapter.notifyDataSetChanged();
                            if (!wasVisible) notifyEntryMethodChanged();
                        }));
            });
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            AlertDialog alert = b.create();
            alert.show();
            alert.redPositive();
        } else {
            boolean wasVisible = ssc.isEntryButtonVisible();
            presentFragment(new PrivateSpacePasscodeActivity(PrivateSpacePasscodeActivity.MODE_SET)
                    .setOnSuccess(() -> {
                        updateRows();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        if (!wasVisible) notifyEntryMethodChanged();
                    }));
        }
    }

    /** Pop a non-blocking notice that the user must re-verify their entry method. */
    private void notifyEntryMethodChanged() {
        if (getParentActivity() == null) return;
        org.telegram.ui.Components.BulletinFactory.of(this)
                .createSimpleBulletin(R.raw.info,
                        LocaleController.getString(R.string.PrivateSpaceShortcutRetestRequired))
                .setDuration(6000)
                .show();
    }

    private void showPinTimeoutPicker() {
        if (getParentActivity() == null) return;
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        int[] minuteValues = {0, 1, 5, 15, 60, 240};
        CharSequence[] labels = new CharSequence[minuteValues.length];
        for (int i = 0; i < minuteValues.length; i++) {
            labels[i] = minuteValues[i] == 0
                    ? LocaleController.getString(R.string.PrivateSpacePinTimeoutOff)
                    : LocaleController.formatString(R.string.PrivateSpacePinTimeoutMinutes, minuteValues[i]);
        }
        int current = ssc.getPinTimeoutMinutes();
        int currentIdx = 0;
        for (int i = 0; i < minuteValues.length; i++) {
            if (minuteValues[i] == current) { currentIdx = i; break; }
        }
        // Indicate current selection by prefixing with a checkmark in the label.
        CharSequence[] decorated = new CharSequence[labels.length];
        for (int i = 0; i < labels.length; i++) {
            decorated[i] = (i == currentIdx ? "✓ " : "    ") + labels[i];
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PrivateSpacePinTimeoutTitle));
        b.setItems(decorated, (d, which) -> {
            ssc.setPinTimeoutMinutes(minuteValues[which]);
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        b.show();
    }

    private void onPinInSearchSwitchClick(TextCheckCell cell) {
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        if (!ssc.hasPassword()) {
            return;
        }
        boolean wasVisible = ssc.isEntryButtonVisible();
        boolean newValue = !ssc.isPinInSearchEnabled();
        ssc.setPinInSearchEnabled(newValue);
        cell.setChecked(newValue);
        // Adapter rebind reflects entryButtonVisible getting force-flipped to true by clearShortcutTested.
        if (adapter != null) adapter.notifyDataSetChanged();
        if (!wasVisible) notifyEntryMethodChanged();
    }

    private void onScreenshotSwitchClick(TextCheckCell cell) {
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        boolean newValue = !ssc.isScreenshotsAllowed();
        ssc.setScreenshotsAllowed(newValue);
        cell.setChecked(newValue);
    }

    private void onEntryButtonSwitchClick(TextCheckCell cell) {
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        boolean currentValue = ssc.isEntryButtonVisible();
        boolean newValue = !currentValue;
        boolean ok = ssc.setEntryButtonVisible(newValue);
        if (ok) {
            cell.setChecked(newValue);
        } else if (getParentActivity() != null) {
            int msgRes = !ssc.hasConfiguredShortcut()
                    ? R.string.PrivateSpaceShowEntryButtonBlockedNoShortcut
                    : R.string.PrivateSpaceShowEntryButtonBlockedUntested;
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.PrivateSpaceTitle))
                    .setMessage(LocaleController.getString(msgRes))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .show();
        }
    }

    private void reloadHiddenIds() {
        hiddenIds.clear();
        // Show only the CURRENT space's membership — real-mode shows real chats, fake-mode
        // shows fake chats. The two are isolated by design.
        Set<Long> all = SecondSpaceController.getInstance(currentAccount).getCurrentSpaceDialogIds();
        if (all != null) {
            hiddenIds.addAll(all);
        }
        reloadAccounts();
        updateRows();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void reloadAccounts() {
        hiddenAccountsList.clear();
        hasOtherAccounts = false;
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a == currentAccount) continue;
            if (!UserConfig.getInstance(a).isClientActivated()) continue;
            hasOtherAccounts = true;
            if (ssc.isAccountHidden(a)) {
                hiddenAccountsList.add(a);
            }
        }
    }

    private String accountName(int account) {
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        return user == null
                ? ""
                : org.telegram.messenger.ContactsController.formatName(user.first_name, user.last_name);
    }

    /** Pick an account to add to the hide-list — lists OTHER activated accounts not yet hidden. */
    private void openAccountPicker() {
        if (getParentActivity() == null) return;
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        ArrayList<Integer> addable = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a == currentAccount) continue;
            if (!UserConfig.getInstance(a).isClientActivated()) continue;
            if (ssc.isAccountHidden(a)) continue;
            addable.add(a);
        }
        if (addable.isEmpty()) {
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.HiddenAccountsAddAccount))
                    .setMessage(LocaleController.getString(R.string.HiddenAccountsNoneToAdd))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .show();
            return;
        }
        CharSequence[] names = new CharSequence[addable.size()];
        for (int i = 0; i < addable.size(); i++) {
            names[i] = accountName(addable.get(i));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.HiddenAccountsAddAccount));
        b.setItems(names, (d, which) -> {
            SecondSpaceController.getInstance(currentAccount).setAccountHidden(addable.get(which), true);
            reloadAccounts();
            updateRows();
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        b.show();
    }

    private void confirmRemoveAccount(int account) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.HiddenAccountsListTitle));
        b.setMessage(LocaleController.getString(R.string.HiddenAccountsRemoveConfirm));
        b.setPositiveButton(LocaleController.getString(R.string.Remove), (d, w) -> {
            SecondSpaceController.getInstance(currentAccount).setAccountHidden(account, false);
            reloadAccounts();
            updateRows();
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alert = b.create();
        alert.show();
        alert.redPositive();
    }

    private void onSwitchPasswordRowClick() {
        if (getParentActivity() == null) return;
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        if (ssc.hasSwitchPassword()) {
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(LocaleController.getString(R.string.HiddenAccountsSwitchPasswordTitle));
            b.setMessage(LocaleController.getString(R.string.HiddenAccountsSwitchPasswordRemoveConfirm));
            b.setPositiveButton(LocaleController.getString(R.string.Remove), (d, w) -> {
                PrivateSpacePinDialog.showEnterSwitchPassword(
                        getParentActivity(),
                        currentAccount,
                        () -> {
                            SecondSpaceController.getInstance(currentAccount).setSwitchPassword(null);
                            if (adapter != null) adapter.notifyDataSetChanged();
                        },
                        null
                );
            });
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            AlertDialog alert = b.create();
            alert.show();
            alert.redPositive();
        } else {
            PrivateSpacePinDialog.showSetSwitchPassword(getParentActivity(), currentAccount, () -> {
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        }
    }

    private void updateRows() {
        rowCount = 0;
        premiumRow = rowCount++;
        premiumShadowRow = rowCount++;
        addChatRow = rowCount++;
        addChatInfoRow = rowCount++;
        if (!hiddenIds.isEmpty()) {
            chatsHeaderRow = rowCount++;
            chatsStartRow = rowCount;
            rowCount += hiddenIds.size();
            chatsEndRow = rowCount;
            chatsShadowRow = rowCount++;
        } else {
            chatsHeaderRow = -1;
            chatsStartRow = -1;
            chatsEndRow = -1;
            chatsShadowRow = -1;
        }
        switchRow = rowCount++;
        switchInfoRow = rowCount++;
        screenshotShadowRow = rowCount++;
        screenshotRow = rowCount++;
        screenshotInfoRow = rowCount++;
        passwordShadowRow = rowCount++;
        passwordRow = rowCount++;
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        if (ssc.hasPassword()) {
            passwordTimeoutRow = rowCount++;
        } else {
            passwordTimeoutRow = -1;
        }
        passwordInfoRow = rowCount++;
        sequenceShadowRow = rowCount++;
        sequenceRow = rowCount++;
        sequenceInfoRow = rowCount++;
        if (ssc.hasPassword()) {
            pinInSearchRow = rowCount++;
            pinInSearchInfoRow = rowCount++;
        } else {
            pinInSearchRow = -1;
            pinInSearchInfoRow = -1;
        }
        if (hasOtherAccounts) {
            hiddenAccountsShadowRow = rowCount++;
            addHiddenAccountRow = rowCount++;
            if (!hiddenAccountsList.isEmpty()) {
                hiddenAccountsHeaderRow = rowCount++;
                hiddenAccountsStartRow = rowCount;
                rowCount += hiddenAccountsList.size();
                hiddenAccountsEndRow = rowCount;
            } else {
                hiddenAccountsHeaderRow = -1;
                hiddenAccountsStartRow = -1;
                hiddenAccountsEndRow = -1;
            }
            hiddenAccountsInfoRow = rowCount++;
            switchPasswordShadowRow = rowCount++;
            switchPasswordRow = rowCount++;
            switchPasswordInfoRow = rowCount++;
        } else {
            hiddenAccountsShadowRow = -1;
            addHiddenAccountRow = -1;
            hiddenAccountsHeaderRow = -1;
            hiddenAccountsStartRow = -1;
            hiddenAccountsEndRow = -1;
            hiddenAccountsInfoRow = -1;
            switchPasswordShadowRow = -1;
            switchPasswordRow = -1;
            switchPasswordInfoRow = -1;
        }
        deleteShadowRow = rowCount++;
        deleteLeemenRow = rowCount++;
        deleteTelegramRow = rowCount++;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int vt = holder.getItemViewType();
            return vt == VIEW_USER || vt == VIEW_ADD || vt == VIEW_SWITCH || vt == VIEW_VALUE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_USER: {
                    ManageChatUserCell userCell = new ManageChatUserCell(mContext, 7, 6, false);
                    userCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = userCell;
                    break;
                }
                case VIEW_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_ADD: {
                    ManageChatTextCell actionCell = new ManageChatTextCell(mContext);
                    actionCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = actionCell;
                    break;
                }
                case VIEW_HEADER: {
                    HeaderCell headerCell = new HeaderCell(mContext, Theme.key_windowBackgroundWhiteBlueHeader, 21, 11, false);
                    headerCell.setHeight(43);
                    view = headerCell;
                    break;
                }
                case VIEW_SWITCH: {
                    TextCheckCell cell = new TextCheckCell(mContext);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_VALUE: {
                    org.telegram.ui.Cells.TextSettingsCell cell = new org.telegram.ui.Cells.TextSettingsCell(mContext);
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
                case VIEW_USER: {
                    ManageChatUserCell userCell = (ManageChatUserCell) holder.itemView;
                    if (position >= hiddenAccountsStartRow && position < hiddenAccountsEndRow) {
                        int acc = hiddenAccountsList.get(position - hiddenAccountsStartRow);
                        TLRPC.User accUser = UserConfig.getInstance(acc).getCurrentUser();
                        String subtitle = accUser != null && accUser.phone != null && accUser.phone.length() != 0
                                ? PhoneFormat.getInstance().format("+" + accUser.phone)
                                : LocaleController.getString(R.string.NumberUnknown);
                        userCell.setTag(acc);
                        userCell.setData(accUser, null, subtitle, position != hiddenAccountsEndRow - 1);
                        break;
                    }
                    long did = hiddenIds.get(position - chatsStartRow);
                    userCell.setTag(did);
                    if (did > 0) {
                        TLRPC.User user = getMessagesController().getUser(did);
                        if (user != null) {
                            String subtitle;
                            if (user.bot) {
                                subtitle = LocaleController.getString(R.string.Bot);
                            } else if (user.phone != null && user.phone.length() != 0) {
                                subtitle = PhoneFormat.getInstance().format("+" + user.phone);
                            } else {
                                subtitle = LocaleController.getString(R.string.NumberUnknown);
                            }
                            userCell.setData(user, null, subtitle, position != chatsEndRow - 1);
                        }
                    } else {
                        TLRPC.Chat chat = getMessagesController().getChat(-did);
                        if (chat != null) {
                            String subtitle;
                            if (chat.participants_count != 0) {
                                subtitle = LocaleController.formatPluralString("Members", chat.participants_count);
                            } else if (chat.has_geo) {
                                subtitle = LocaleController.getString(R.string.MegaLocation);
                            } else if (!ChatObject.isPublic(chat)) {
                                subtitle = LocaleController.getString(R.string.MegaPrivate);
                            } else {
                                subtitle = LocaleController.getString(R.string.MegaPublic);
                            }
                            userCell.setData(chat, null, subtitle, position != chatsEndRow - 1);
                        }
                    }
                    break;
                }
                case VIEW_INFO: {
                    TextInfoPrivacyCell privacyCell = (TextInfoPrivacyCell) holder.itemView;
                    privacyCell.setFixedSize(0);
                    if (position == addChatInfoRow) {
                        privacyCell.setText(LocaleController.getString(R.string.PrivateSpaceAddChatsInfo));
                    } else if (position == switchInfoRow) {
                        privacyCell.setText(LocaleController.getString(R.string.PrivateSpaceShowEntryButtonInfo));
                    } else if (position == screenshotInfoRow) {
                        privacyCell.setText(LocaleController.getString(R.string.PrivateSpaceAllowScreenshotsInfo));
                    } else if (position == passwordInfoRow) {
                        privacyCell.setText(LocaleController.getString(R.string.PrivateSpacePinInfo));
                    } else if (position == sequenceInfoRow) {
                        privacyCell.setText(LocaleController.getString(R.string.PrivateSpaceSequenceInfo));
                    } else if (position == pinInSearchInfoRow) {
                        privacyCell.setText(LocaleController.getString(R.string.PrivateSpacePinInSearchInfo));
                    } else if (position == hiddenAccountsInfoRow) {
                        privacyCell.setText(LocaleController.getString(R.string.HiddenAccountsListInfo));
                    } else if (position == switchPasswordInfoRow) {
                        privacyCell.setText(LocaleController.getString(R.string.HiddenAccountsSwitchPasswordInfo));
                    }
                    break;
                }
                case VIEW_ADD: {
                    ManageChatTextCell actionCell = (ManageChatTextCell) holder.itemView;
                    actionCell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
                    if (position == addHiddenAccountRow) {
                        actionCell.setText(LocaleController.getString(R.string.HiddenAccountsAddAccount), null, R.drawable.msg_contact_add, false);
                    } else {
                        actionCell.setText(LocaleController.getString(R.string.PrivateSpaceAddChat), null, R.drawable.msg_contact_add, false);
                    }
                    break;
                }
                case VIEW_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == hiddenAccountsHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.HiddenAccountsListTitle));
                    } else {
                        headerCell.setText(LocaleController.getString(R.string.PrivateSpaceSelectChats));
                    }
                    break;
                }
                case VIEW_SWITCH: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
                    if (position == switchRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.PrivateSpaceShowEntryButton), ssc.isEntryButtonVisible(), false);
                    } else if (position == screenshotRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.PrivateSpaceAllowScreenshots), ssc.isScreenshotsAllowed(), false);
                    } else if (position == pinInSearchRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.PrivateSpacePinInSearch), ssc.isPinInSearchEnabled(), false);
                    }
                    break;
                }
                case VIEW_VALUE: {
                    org.telegram.ui.Cells.TextSettingsCell cell = (org.telegram.ui.Cells.TextSettingsCell) holder.itemView;
                    SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
                    cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)); // reset (recycled cells)
                    if (position == premiumRow) {
                        String value;
                        if (ssc.hasLeemenPremium()) {
                            String date = android.text.format.DateFormat
                                    .getDateFormat(org.telegram.messenger.ApplicationLoader.applicationContext)
                                    .format(new java.util.Date(ssc.getLeemenPremiumUntil()));
                            value = LocaleController.formatString(R.string.LeemenPremiumActiveUntil, date);
                        } else {
                            value = LocaleController.getString(R.string.LeemenPremiumStatusUnlimited);
                        }
                        cell.setTextAndValue(LocaleController.getString(R.string.LeemenPremium), value, true);
                    } else if (position == passwordRow) {
                        boolean on = ssc.hasPassword();
                        cell.setTextAndValue(
                                LocaleController.getString(R.string.PrivateSpacePinTitle),
                                LocaleController.getString(on ? R.string.PrivateSpacePinOn : R.string.PrivateSpacePinOff),
                                ssc.hasPassword());
                    } else if (position == passwordTimeoutRow) {
                        int m = ssc.getPinTimeoutMinutes();
                        String value = m == 0
                                ? LocaleController.getString(R.string.PrivateSpacePinTimeoutOff)
                                : LocaleController.formatString(R.string.PrivateSpacePinTimeoutMinutes, m);
                        cell.setTextAndValue(LocaleController.getString(R.string.PrivateSpacePinTimeoutTitle), value, false);
                    } else if (position == sequenceRow) {
                        int n = ssc.getTabSequence().size();
                        String value = n == 0
                                ? LocaleController.getString(R.string.PrivateSpaceSequenceRowOff)
                                : LocaleController.formatString(R.string.PrivateSpaceSequenceRowOn, n);
                        cell.setTextAndValue(LocaleController.getString(R.string.PrivateSpaceSequenceTitle), value, false);
                    } else if (position == switchPasswordRow) {
                        boolean on = ssc.hasSwitchPassword();
                        cell.setTextAndValue(
                                LocaleController.getString(R.string.HiddenAccountsSwitchPasswordTitle),
                                LocaleController.getString(on ? R.string.PrivateSpacePinOn : R.string.PrivateSpacePinOff),
                                false);
                    } else if (position == deleteLeemenRow) {
                        cell.setText(LocaleController.getString(R.string.DeleteLeemenAccount), true);
                        cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                    } else if (position == deleteTelegramRow) {
                        cell.setText(LocaleController.getString(R.string.DeleteTelegramAccount), false);
                        cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == premiumRow) return VIEW_VALUE;
            if (position == premiumShadowRow) return VIEW_SHADOW;
            if (position == addChatRow) return VIEW_ADD;
            if (position == addChatInfoRow) return VIEW_INFO;
            if (position == chatsHeaderRow) return VIEW_HEADER;
            if (position >= chatsStartRow && position < chatsEndRow) return VIEW_USER;
            if (position == chatsShadowRow) return VIEW_SHADOW;
            if (position == switchRow) return VIEW_SWITCH;
            if (position == switchInfoRow) return VIEW_INFO;
            if (position == screenshotShadowRow) return VIEW_SHADOW;
            if (position == screenshotRow) return VIEW_SWITCH;
            if (position == screenshotInfoRow) return VIEW_INFO;
            if (position == passwordShadowRow) return VIEW_SHADOW;
            if (position == passwordRow) return VIEW_VALUE;
            if (position == passwordTimeoutRow) return VIEW_VALUE;
            if (position == passwordInfoRow) return VIEW_INFO;
            if (position == sequenceShadowRow) return VIEW_SHADOW;
            if (position == sequenceRow) return VIEW_VALUE;
            if (position == sequenceInfoRow) return VIEW_INFO;
            if (position == pinInSearchRow) return VIEW_SWITCH;
            if (position == pinInSearchInfoRow) return VIEW_INFO;
            if (position == hiddenAccountsShadowRow) return VIEW_SHADOW;
            if (position == addHiddenAccountRow) return VIEW_ADD;
            if (position == hiddenAccountsHeaderRow) return VIEW_HEADER;
            if (position >= hiddenAccountsStartRow && position < hiddenAccountsEndRow) return VIEW_USER;
            if (position == hiddenAccountsInfoRow) return VIEW_INFO;
            if (position == switchPasswordShadowRow) return VIEW_SHADOW;
            if (position == switchPasswordRow) return VIEW_VALUE;
            if (position == switchPasswordInfoRow) return VIEW_INFO;
            if (position == deleteShadowRow) return VIEW_SHADOW;
            if (position == deleteLeemenRow) return VIEW_VALUE;
            if (position == deleteTelegramRow) return VIEW_VALUE;
            return VIEW_SHADOW;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // reloadHiddenIds invokes updateRows + notifyDataSetChanged — also refreshes sequence/PIN labels.
        reloadHiddenIds();
    }
}
