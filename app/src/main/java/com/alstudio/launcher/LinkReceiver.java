package com.alstudio.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The app bar add-on's Close button: drop that app from the Running row. */
public class LinkReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String pkg = i == null ? null : i.getStringExtra("pkg");
        if (pkg != null) Hosted.remove(c, pkg);
    }
}
