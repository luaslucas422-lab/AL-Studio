package com.alstudio.appbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives updates from AL Studio (only apps signed with AL Studio's key can send them). */
public class LinkReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        LinkState.save(c, i.getStringExtra("apps"), i.getBooleanExtra("enabled", false));
        AppBarService s = AppBarService.instance;
        if (s != null) s.refresh();
    }
}
