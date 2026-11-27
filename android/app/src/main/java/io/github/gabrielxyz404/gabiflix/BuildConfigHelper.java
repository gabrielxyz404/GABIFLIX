package io.github.gabrielxyz404.gabiflix;

import android.content.Context;

final class BuildConfigHelper {
    private BuildConfigHelper() {}

    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1";
        }
    }

    /** para diagnóstico (relatórios, registos): "2.0 (build 121)"; o utilizador vê só versionName */
    static String versionLabel(Context c) {
        long code = AppUpdate.versionCode(c);
        return versionName(c) + (code > 0 ? " (build " + code + ")" : "");
    }
}
