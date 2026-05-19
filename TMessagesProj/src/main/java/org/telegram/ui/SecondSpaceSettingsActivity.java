package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class SecondSpaceSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_CHECK_CHAT = 1;
    private static final int VIEW_TYPE_SHADOW = 2;
    private static final int VIEW_TYPE_SWITCH = 3;
    private static final int VIEW_TYPE_INFO = 4;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private final ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();

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
            int viewType = adapter.getItemViewType(position);
            if (viewType == VIEW_TYPE_CHECK_CHAT) {
                int dialogIndex = position - 1;
                if (dialogIndex < 0 || dialogIndex >= dialogs.size()) {
                    return;
                }
                TLRPC.Dialog dialog = dialogs.get(dialogIndex);
                SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
                boolean currentlyIn = ssc.isInSecondSpace(dialog.id);
                if (currentlyIn) {
                    ssc.removeFromSecondSpace(dialog.id);
                } else {
                    ssc.addToSecondSpace(dialog.id);
                }
                ((TextCheckCell) view).setChecked(!currentlyIn);
            } else if (viewType == VIEW_TYPE_SWITCH) {
                onEntryButtonSwitchClick((TextCheckCell) view);
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        reloadDialogs();
        return fragmentView;
    }

    private void onEntryButtonSwitchClick(TextCheckCell cell) {
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        boolean currentValue = ssc.isEntryButtonVisible();
        boolean newValue = !currentValue;
        boolean ok = ssc.setEntryButtonVisible(newValue);
        if (ok) {
            cell.setChecked(newValue);
        } else {
            if (getParentActivity() != null) {
                new AlertDialog.Builder(getParentActivity())
                        .setTitle(LocaleController.getString(R.string.PrivateSpaceTitle))
                        .setMessage(LocaleController.getString(R.string.PrivateSpaceShowEntryButtonBlocked))
                        .setPositiveButton(LocaleController.getString(R.string.OK), null)
                        .show();
            }
        }
    }

    private void reloadDialogs() {
        dialogs.clear();
        ArrayList<TLRPC.Dialog> all = MessagesController.getInstance(currentAccount).getAllDialogs();
        if (all != null) {
            for (int i = 0; i < all.size(); i++) {
                TLRPC.Dialog d = all.get(i);
                if (d == null || isSelfOrService(d.id)) {
                    continue;
                }
                dialogs.add(d);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private boolean isSelfOrService(long dialogId) {
        long selfId = getUserConfig().getClientUserId();
        if (dialogId == selfId) {
            return true;
        }
        if (dialogId > 0) {
            TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(dialogId);
            return u != null && (UserObject.isReplyUser(u) || UserObject.isDeleted(u));
        }
        return false;
    }

    private String dialogTitle(TLRPC.Dialog dialog) {
        if (dialog.id > 0) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialog.id);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } else {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialog.id);
            if (chat != null) {
                return chat.title != null ? chat.title : "";
            }
        }
        return "";
    }

    private int rowHeader() { return 0; }
    private int rowFirstChat() { return 1; }
    private int rowLastChat() { return dialogs.size(); }
    private int rowShadow() { return dialogs.size() + 1; }
    private int rowSwitch() { return dialogs.size() + 2; }
    private int rowInfo() { return dialogs.size() + 3; }
    private int rowCount() { return dialogs.size() + 4; }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            this.mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int vt = holder.getItemViewType();
            return vt == VIEW_TYPE_CHECK_CHAT || vt == VIEW_TYPE_SWITCH;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case VIEW_TYPE_SWITCH:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_CHECK_CHAT:
                default:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int viewType = holder.getItemViewType();
            switch (viewType) {
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.PrivateSpaceSelectChats));
                    break;
                }
                case VIEW_TYPE_CHECK_CHAT: {
                    int dialogIndex = position - 1;
                    if (dialogIndex < 0 || dialogIndex >= dialogs.size()) {
                        return;
                    }
                    TLRPC.Dialog dialog = dialogs.get(dialogIndex);
                    boolean checked = SecondSpaceController.getInstance(currentAccount).isInSecondSpace(dialog.id);
                    boolean divider = dialogIndex < dialogs.size() - 1;
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(dialogTitle(dialog), checked, divider);
                    break;
                }
                case VIEW_TYPE_SWITCH: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    boolean checked = SecondSpaceController.getInstance(currentAccount).isEntryButtonVisible();
                    cell.setTextAndCheck(LocaleController.getString(R.string.PrivateSpaceShowEntryButton), checked, false);
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.PrivateSpaceShowEntryButtonInfo));
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == rowHeader()) return VIEW_TYPE_HEADER;
            if (position >= rowFirstChat() && position <= rowLastChat()) return VIEW_TYPE_CHECK_CHAT;
            if (position == rowShadow()) return VIEW_TYPE_SHADOW;
            if (position == rowSwitch()) return VIEW_TYPE_SWITCH;
            if (position == rowInfo()) return VIEW_TYPE_INFO;
            return VIEW_TYPE_SHADOW;
        }

        @Override
        public int getItemCount() {
            return rowCount();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadDialogs();
    }
}
