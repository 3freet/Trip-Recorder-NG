package org.triprecorderng;

import android.app.Application;

/** Loads the language setting once for every part of the app (screen, recorder service, receivers). */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        L.init(this);
    }
}
