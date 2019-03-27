/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.view.View;

import androidx.collection.ArraySet;

import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A manager that ensures that notifications are visually stable. It will suppress reorderings
 * and reorder at the right time when they are out of view.
 */
@Singleton
public class VisualStabilityManager implements OnHeadsUpChangedListener {

    private final ArrayList<Callback> mCallbacks =  new ArrayList<>();

    private NotificationPresenter mPresenter;
    private boolean mPanelExpanded;
    private boolean mScreenOn;
    private boolean mReorderingAllowed;
    private VisibilityLocationProvider mVisibilityLocationProvider;
    private ArraySet<View> mAllowedReorderViews = new ArraySet<>();
    private ArraySet<NotificationEntry> mLowPriorityReorderingViews = new ArraySet<>();
    private ArraySet<View> mAddedChildren = new ArraySet<>();
    private boolean mPulsing;

    @Inject
    public VisualStabilityManager(NotificationEntryManager notificationEntryManager) {
        notificationEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
            @Override
            public void onPreEntryUpdated(NotificationEntry entry) {
                final boolean mAmbientStateHasChanged =
                        entry.ambient != entry.getRow().isLowPriority();
                if (mAmbientStateHasChanged) {
                    mLowPriorityReorderingViews.add(entry);
                }
            }

            @Override
            public void onPostEntryUpdated(NotificationEntry entry) {
                // This line is technically not required as we'll get called as the hierarchy
                // manager will call onReorderingFinished() immediately before this.
                // TODO: Find a way to make this relationship more explicit
                mLowPriorityReorderingViews.remove(entry);
            }
        });
    }

    public void setUpWithPresenter(NotificationPresenter presenter) {
        mPresenter = presenter;
    }

    /**
     * Add a callback to invoke when reordering is allowed again.
     * @param callback
     */
    public void addReorderingAllowedCallback(Callback callback) {
        if (mCallbacks.contains(callback)) {
            return;
        }
        mCallbacks.add(callback);
    }

    /**
     * Set the panel to be expanded.
     */
    public void setPanelExpanded(boolean expanded) {
        mPanelExpanded = expanded;
        updateReorderingAllowed();
    }

    /**
     * @param screenOn whether the screen is on
     */
    public void setScreenOn(boolean screenOn) {
        mScreenOn = screenOn;
        updateReorderingAllowed();
    }

    /**
     * @param pulsing whether we are currently pulsing for ambient display.
     */
    public void setPulsing(boolean pulsing) {
        if (mPulsing == pulsing) {
            return;
        }
        mPulsing = pulsing;
        updateReorderingAllowed();
    }

    private void updateReorderingAllowed() {
        boolean reorderingAllowed = (!mScreenOn || !mPanelExpanded) && !mPulsing;
        boolean changed = reorderingAllowed && !mReorderingAllowed;
        mReorderingAllowed = reorderingAllowed;
        if (changed) {
            notifyCallbacks();
        }
    }

    private void notifyCallbacks() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            Callback callback = mCallbacks.get(i);
            callback.onReorderingAllowed();
        }
        mCallbacks.clear();
    }

    /**
     * @return whether reordering is currently allowed in general.
     */
    public boolean isReorderingAllowed() {
        return mReorderingAllowed;
    }

    /**
     * @return whether a specific notification is allowed to reorder. Certain notifications are
     * allowed to reorder even if {@link #isReorderingAllowed()} returns false, like newly added
     * notifications or heads-up notifications that are out of view.
     */
    public boolean canReorderNotification(ExpandableNotificationRow row) {
        if (mReorderingAllowed) {
            return true;
        }
        if (mAddedChildren.contains(row)) {
            return true;
        }
        if (mLowPriorityReorderingViews.contains(row.getEntry())) {
            return true;
        }
        if (mAllowedReorderViews.contains(row)
                && !mVisibilityLocationProvider.isInVisibleLocation(row.getEntry())) {
            return true;
        }
        return false;
    }

    public void setVisibilityLocationProvider(
            VisibilityLocationProvider visibilityLocationProvider) {
        mVisibilityLocationProvider = visibilityLocationProvider;
    }

    public void onReorderingFinished() {
        mAllowedReorderViews.clear();
        mAddedChildren.clear();
        mLowPriorityReorderingViews.clear();
    }

    @Override
    public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
        if (isHeadsUp) {
            // Heads up notifications should in general be allowed to reorder if they are out of
            // view and stay at the current location if they aren't.
            mAllowedReorderViews.add(entry.getRow());
        }
    }

    /**
     * Notify the visual stability manager that a new view was added and should be allowed to
     * reorder next time.
     */
    public void notifyViewAddition(View view) {
        mAddedChildren.add(view);
    }

    public interface Callback {
        /**
         * Called when reordering is allowed again.
         */
        void onReorderingAllowed();
    }

}
