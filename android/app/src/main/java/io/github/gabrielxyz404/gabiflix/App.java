package io.github.gabrielxyz404.gabiflix;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Liga o relatório de erros em cada processo (principal e ":audiocap") e deixa migalhas do ciclo de vida de todas as telas. */
public final class App extends Application {
    private static final java.util.concurrent.atomic.AtomicInteger trimLow = new java.util.concurrent.atomic.AtomicInteger(),
            trimCritical = new java.util.concurrent.atomic.AtomicInteger(), trimOther = new java.util.concurrent.atomic.AtomicInteger();

    /** quantos pedidos de memória o sistema fez nesta sessão (vai no relatório MEMORIA da TV) */
    static String trimSummary() { return "RUNNING_LOW=" + trimLow.get() + " RUNNING_CRITICAL=" + trimCritical.get() + " outros=" + trimOther.get(); }

    /**
     * o sistema pede memória de volta (a morte do processo por falta de memória não deixa rasto no Android 9): fica nas migalhas.
     * Com o filme tocando e o sistema apertado (RUNNING_LOW ou RUNNING_CRITICAL), o player solta o que pode (amostras de barras, caches da navegação, coleta de lixo).
     */
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        CrashReporter.crumbMem("onTrimMemory nível " + level);
        if (level == TRIM_MEMORY_RUNNING_CRITICAL) trimCritical.incrementAndGet();
        else if (level == TRIM_MEMORY_RUNNING_LOW) trimLow.incrementAndGet();
        else trimOther.incrementAndGet();
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) PlayerActivity.onMemoryPressure(level);
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        CrashReporter.crumbMem("onLowMemory");
    }

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
