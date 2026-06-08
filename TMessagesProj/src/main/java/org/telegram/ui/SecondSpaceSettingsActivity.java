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
    private int entryPasswordShadowRow;
    private int entryPasswordRow;
    private int entryPasswordInfoRow;
    private int hideAccountsShadowRow;
    private int hideAccountsHeaderRow;
    private int hideAccountsStartRow;
    private int hideAccountsEndRow;
    private int hideAccountsInfoRow;
    private final ArrayList<Integer> otherAccounts = new ArrayList<>();

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
            if (position == addChatRow) {
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
            } else if (position == entryPasswordRow) {
                onEntryPasswordRowClick();
            } else if (position >= hideAccountsStartRow && position < hideAccountsEndRow) {
                onHideAccountToggle((TextCheckCell) view, otherAccounts.get(position - hideAccountsStartRow));
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
            for (Long id : ids) {
                if (id == null) continue;
                ssc.addToCurrentSpace(id);
            }
            reloadHiddenIds();
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
        reloadOtherAccounts();
        updateRows();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void reloadOtherAccounts() {
        otherAccounts.clear();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a == currentAccount) continue;
            if (UserConfig.getInstance(a).isClientActivated()) {
                otherAccounts.add(a);
            }
        }
    }

    private void onEntryPasswordRowClick() {
        if (getParentActivity() == null) return;
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        if (ssc.hasEntryPassword()) {
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(LocaleController.getString(R.string.HiddenAccountEntryPasswordTitle));
            b.setMessage(LocaleController.getString(R.string.HiddenAccountEntryPasswordRemoveConfirm));
            b.setPositiveButton(LocaleController.getString(R.string.Remove), (d, w) -> {
                PrivateSpacePinDialog.showEnterAccountPassword(
                        getParentActivity(),
                        currentAccount,
                        () -> {
                            SecondSpaceController.getInstance(currentAccount).setEntryPassword(null);
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
            PrivateSpacePinDialog.showSetAccountPassword(getParentActivity(), currentAccount, () -> {
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        }
    }

    private void onHideAccountToggle(TextCheckCell cell, int otherAccountNum) {
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        boolean newValue = !ssc.isAccountHidden(otherAccountNum);
        ssc.setAccountHidden(otherAccountNum, newValue);
        cell.setChecked(newValue);
    }

    private void updateRows() {
        rowCount = 0;
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
        entryPasswordShadowRow = rowCount++;
        entryPasswordRow = rowCount++;
        entryPasswordInfoRow = rowCount++;
        if (!otherAccounts.isEmpty()) {
            hideAccountsShadowRow = rowCount++;
            hideAccountsHeaderRow = rowCount++;
            hideAccountsStartRow = rowCount;
            rowCount += otherAccounts.size();
            hideAccountsEndRow = rowCount;
            hideAccountsInfoRow = rowCount++;
        } else {
            hideAccountsShadowRow = -1;
            hideAccountsHeaderRow = -1;
            hideAccountsStartRow = -1;
            hideAccountsEndRow = -1;
            hideAccountsInfoRow = -1;
        }
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
                    } else if (position == entryPasswordInfoRow) {
                        privacyCell.setText(LocaleController.getString(R.string.HiddenAccountEntryPasswordInfo));
                    } else if (position == hideAccountsInfoRow) {
                        privacyCell.setText(LocaleController.getString(R.string.HiddenAccountsListInfo));
                    }
                    break;
                }
                case VIEW_ADD: {
                    ManageChatTextCell actionCell = (ManageChatTextCell) holder.itemView;
                    actionCell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
                    actionCell.setText(LocaleController.getString(R.string.PrivateSpaceAddChat), null, R.drawable.msg_contact_add, false);
                    break;
                }
                case VIEW_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == hideAccountsHeaderRow) {
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
                    } else if (position >= hideAccountsStartRow && position < hideAccountsEndRow) {
                        int acc = otherAccounts.get(position - hideAccountsStartRow);
                        TLRPC.User user = UserConfig.getInstance(acc).getCurrentUser();
                        String name = user == null
                                ? ""
                                : org.telegram.messenger.ContactsController.formatName(user.first_name, user.last_name);
                        cell.setTextAndCheck(name, ssc.isAccountHidden(acc), position != hideAccountsEndRow - 1);
                    }
                    break;
                }
                case VIEW_VALUE: {
                    org.telegram.ui.Cells.TextSettingsCell cell = (org.telegram.ui.Cells.TextSettingsCell) holder.itemView;
                    SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
                    if (position == passwordRow) {
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
                    } else if (position == entryPasswordRow) {
                        boolean on = ssc.hasEntryPassword();
                        cell.setTextAndValue(
                                LocaleController.getString(R.string.HiddenAccountEntryPasswordTitle),
                                LocaleController.getString(on ? R.string.PrivateSpacePinOn : R.string.PrivateSpacePinOff),
                                false);
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
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
            if (position == entryPasswordShadowRow) return VIEW_SHADOW;
            if (position == entryPasswordRow) return VIEW_VALUE;
            if (position == entryPasswordInfoRow) return VIEW_INFO;
            if (position == hideAccountsShadowRow) return VIEW_SHADOW;
            if (position == hideAccountsHeaderRow) return VIEW_HEADER;
            if (position >= hideAccountsStartRow && position < hideAccountsEndRow) return VIEW_SWITCH;
            if (position == hideAccountsInfoRow) return VIEW_INFO;
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
