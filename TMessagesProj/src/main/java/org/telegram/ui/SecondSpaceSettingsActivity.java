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
    private int passwordShadowRow;
    private int passwordRow;
    private int passwordInfoRow;

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
            if (!SecondSpaceController.getInstance(currentAccount).isActive()) {
                finishFragment();
            }
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
            } else if (position == passwordRow) {
                onPasswordRowClick();
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
            for (Long id : ids) {
                if (id == null) continue;
                ssc.addToSecondSpace(id);
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
            SecondSpaceController.getInstance(currentAccount).removeFromSecondSpace(dialogId);
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
                PrivateSpacePinDialog.showRemove(getParentActivity(), currentAccount, () -> {
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
            });
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            AlertDialog alert = b.create();
            alert.show();
            alert.redPositive();
        } else {
            PrivateSpacePinDialog.showSet(getParentActivity(), currentAccount, () -> {
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        }
    }

    private void onEntryButtonSwitchClick(TextCheckCell cell) {
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        boolean currentValue = ssc.isEntryButtonVisible();
        boolean newValue = !currentValue;
        boolean ok = ssc.setEntryButtonVisible(newValue);
        if (ok) {
            cell.setChecked(newValue);
        } else if (getParentActivity() != null) {
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.PrivateSpaceTitle))
                    .setMessage(LocaleController.getString(R.string.PrivateSpaceShowEntryButtonBlocked))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .show();
        }
    }

    private void reloadHiddenIds() {
        hiddenIds.clear();
        Set<Long> all = SecondSpaceController.getInstance(currentAccount).getDialogIds();
        if (all != null) {
            hiddenIds.addAll(all);
        }
        updateRows();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
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
        passwordShadowRow = rowCount++;
        passwordRow = rowCount++;
        passwordInfoRow = rowCount++;
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
                    } else if (position == passwordInfoRow) {
                        privacyCell.setText(LocaleController.getString(R.string.PrivateSpacePinInfo));
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
                    headerCell.setText(LocaleController.getString(R.string.PrivateSpaceSelectChats));
                    break;
                }
                case VIEW_SWITCH: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    boolean checked = SecondSpaceController.getInstance(currentAccount).isEntryButtonVisible();
                    cell.setTextAndCheck(LocaleController.getString(R.string.PrivateSpaceShowEntryButton), checked, false);
                    break;
                }
                case VIEW_VALUE: {
                    org.telegram.ui.Cells.TextSettingsCell cell = (org.telegram.ui.Cells.TextSettingsCell) holder.itemView;
                    boolean on = SecondSpaceController.getInstance(currentAccount).hasPassword();
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.PrivateSpacePinTitle),
                            LocaleController.getString(on ? R.string.PrivateSpacePinOn : R.string.PrivateSpacePinOff),
                            false);
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
            if (position == passwordShadowRow) return VIEW_SHADOW;
            if (position == passwordRow) return VIEW_VALUE;
            if (position == passwordInfoRow) return VIEW_INFO;
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
        reloadHiddenIds();
    }
}
