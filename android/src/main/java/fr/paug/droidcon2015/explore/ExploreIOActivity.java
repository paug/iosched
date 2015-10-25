/*
 * Copyright 2014 Google Inc. All rights reserved.
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
 * limitations under the License.
 */

package fr.paug.droidcon2015.explore;

import fr.paug.droidcon2015.R;
import fr.paug.droidcon2015.explore.ExploreModel.ExploreQueryEnum;
import fr.paug.droidcon2015.explore.ExploreModel.ExploreUserActionEnum;
import fr.paug.droidcon2015.explore.data.ItemGroup;
import fr.paug.droidcon2015.explore.data.LiveStreamData;
import fr.paug.droidcon2015.explore.data.SessionData;
import fr.paug.droidcon2015.framework.QueryEnum;
import fr.paug.droidcon2015.provider.ScheduleContract;
import fr.paug.droidcon2015.session.SessionDetailActivity;
import fr.paug.droidcon2015.ui.BaseActivity;
import fr.paug.droidcon2015.ui.SearchActivity;
import fr.paug.droidcon2015.ui.widget.CollectionView;
import fr.paug.droidcon2015.ui.widget.DrawShadowFrameLayout;
import fr.paug.droidcon2015.util.AnalyticsHelper;
import fr.paug.droidcon2015.util.UIUtils;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import fr.paug.droidcon2015.util.LogUtils;

import static fr.paug.droidcon2015.util.LogUtils.LOGD;
import static fr.paug.droidcon2015.util.LogUtils.LOGE;
import static fr.paug.droidcon2015.util.LogUtils.makeLogTag;

/**
 * Display a summary of what is happening at Google I/O this year. Theme and topic cards are
 * displayed based on the session data. Conference messages are also displayed as cards..
 */
public class ExploreIOActivity extends BaseActivity implements Toolbar.OnMenuItemClickListener {

    private static final String TAG = LogUtils.makeLogTag(ExploreIOActivity.class);

    private static final String SCREEN_LABEL = "Explore I/O";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.explore_io_act);
        addPresenterFragment(
                R.id.explore_library_frag,
                new ExploreModel(
                        getApplicationContext()),
                new QueryEnum[]{
                        ExploreQueryEnum.SESSIONS,
                        ExploreQueryEnum.TAGS},
                new ExploreUserActionEnum[]{
                        ExploreUserActionEnum.RELOAD});

        // ANALYTICS SCREEN: View the Explore I/O screen
        // Contains: Nothing (Page name is a constant)
        AnalyticsHelper.sendScreenView(SCREEN_LABEL);

        registerHideableHeaderView(findViewById(R.id.headerbar));
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        enableActionBarAutoHide((CollectionView) findViewById(R.id.explore_collection_view));
    }

    @Override
    protected int getSelfNavDrawerItem() {
        return NAVDRAWER_ITEM_EXPLORE;
    }

    @Override
    protected void onActionBarAutoShowOrHide(boolean shown) {
        super.onActionBarAutoShowOrHide(shown);
        DrawShadowFrameLayout frame = (DrawShadowFrameLayout) findViewById(R.id.main_content);
        frame.setShadowVisible(shown, shown);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        // Add the search button to the toolbar.
        Toolbar toolbar = getActionBarToolbar();
        toolbar.inflateMenu(R.menu.explore_io_menu);
        toolbar.setOnMenuItemClickListener(this);
        return true;
    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.menu_search:
                startActivity(new Intent(this, SearchActivity.class));
                return true;
        }
        return false;
    }

    public void sessionDetailItemClicked(View viewClicked) {
        LogUtils.LOGD(TAG, "clicked: " + viewClicked + " " +
                ((viewClicked != null) ? viewClicked.getTag() : ""));
        Object tag = null;
        if (viewClicked != null) {
            tag = viewClicked.getTag();
        }
        if (tag instanceof SessionData) {
            SessionData sessionData = (SessionData)viewClicked.getTag();
            if (!TextUtils.isEmpty(sessionData.getSessionId())) {
                Intent intent = new Intent(getApplicationContext(), SessionDetailActivity.class);
                Uri sessionUri = ScheduleContract.Sessions.buildSessionUri(sessionData.getSessionId());
                intent.setData(sessionUri);
                startActivity(intent);
            } else {
                LogUtils.LOGE(TAG, "Theme item clicked but session data was null:" + sessionData);
                Toast.makeText(this, R.string.unknown_error, Toast.LENGTH_LONG).show();
            }
        }
    }

    public void cardHeaderClicked(View viewClicked) {
        LogUtils.LOGD(TAG, "clicked: " + viewClicked + " " +
                ((viewClicked != null) ? viewClicked.getTag() : ""));
        View moreButton = viewClicked.findViewById(android.R.id.button1);
        Object tag = moreButton != null ? moreButton.getTag() : null;
        Intent intent = new Intent(getApplicationContext(), ExploreSessionsActivity.class);
        if (tag instanceof LiveStreamData) {
            intent.setData(ScheduleContract.Sessions.buildSessionsAfterUri(UIUtils.getCurrentTime(this)));
            intent.putExtra(ExploreSessionsActivity.EXTRA_SHOW_LIVE_STREAM_SESSIONS, true);
        } else if (tag instanceof ItemGroup) {
            intent.putExtra(ExploreSessionsActivity.EXTRA_FILTER_TAG, ((ItemGroup)tag).getId());
        }
        startActivity(intent);
    }
}
