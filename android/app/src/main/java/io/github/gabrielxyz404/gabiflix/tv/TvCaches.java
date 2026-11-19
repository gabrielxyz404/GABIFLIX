package io.github.gabrielxyz404.gabiflix.tv;

import android.content.Context;

/** Caches da navegação da TV que o player pode soltar quando o sistema pede memória no meio do filme. */
public final class TvCaches {
    private TvCaches() {}

    /** respostas JSON do catálogo (Net) e bitmaps de pôsteres (Glide): voltam a ser baixados/decodificados se o usuário voltar à navegação. Só na thread principal. */
    public static void release(Context c) {
        try { Net.clearCache(); } catch (Throwable ignored) {}
        try { com.bumptech.glide.Glide.get(c.getApplicationContext()).clearMemory(); } catch (Throwable ignored) {}
    }
}
