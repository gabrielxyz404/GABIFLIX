package io.github.gabrielxyz404.gabiflix;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Liga o relatório de erros em cada processo (principal e ":audiocap") e deixa migalhas do ciclo de vida de todas as telas. */
public final class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        CrashReporter.install(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            private String n(Activity a) { return a.getClass().getSimpleName(); }
            @Override public void onActivityCreated(Activity a, Bundle b) { CrashReporter.crumb(n(a) + " criada" + (b != null ? " (restaurada)" : "") + " intent=" + (a.getIntent() == null ? "-" : a.getIntent().getFlags())); }
            @Override public void onActivityStarted(Activity a) { CrashReporter.crumb(n(a) + " iniciada"); }
            @Override public void onActivityResumed(Activity a) { CrashReporter.crumb(n(a) + " retomada"); }
            @Override public void onActivityPaused(Activity a) { CrashReporter.crumb(n(a) + " pausada"); }
            @Override public void onActivityStopped(Activity a) { CrashReporter.crumb(n(a) + " parada"); }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { CrashReporter.crumb(n(a) + " destruída (terminando=" + a.isFinishing() + ")"); }
        });
    }
}
