package com.francistech.vinylist;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaStorePlugin.class);
        registerPlugin(MediaControlPlugin.class);
        registerPlugin(AppUpdatePlugin.class);
        registerPlugin(BatteryPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
