package io.github.gabrielxyz404.gabiflix.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Auditoria 2026-11-27: a busca pede filmes e séries ao mesmo tempo, e addons lentos não prendem a fila dos catálogos. */
public class NetStremioTest {

    private static String page(String type, String... ids) {
        StringBuilder b = new StringBuilder("{\"metas\":[");
        for (int i = 0; i < ids.length; i++) b.append(i > 0 ? "," : "").append("{\"id\":\"").append(ids[i]).append("\",\"name\":\"").append(type).append(i).append("\"}");
        return b.append("]}").toString();
    }

    /** o Uri.encode do android.jar da JVM devolve null: o endereço fica "...search=null.json", e o tipo é lido pelo "/movie/" ou "/series/" */
    @Test public void searchAsksMoviesAndSeriesAtTheSameTime() {
        final long delayMs = 400;
        Stremio.Fetch fake = url -> {
            Thread.sleep(delayMs);
            return url.contains("/movie/") ? page("movie", "tt1", "tt2") : page("series", "tt2", "tt3");
        };
        long t0 = System.nanoTime();
        List<JSONObject> r = Stremio.search("matrix", fake);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // um depois do outro seriam >= 800 ms
        assertTrue("busca demorou " + ms + " ms (devia ser ~" + delayMs + ")", ms < 700);
        // mesma ordem de antes: filmes primeiro, depois séries, sem repetir o mesmo código (tt2 só aparece uma vez, como filme)
        assertEquals(3, r.size());
        assertEquals("tt1", r.get(0).optString("id"));
        assertEquals("movie", r.get(0).optString("type"));
        assertEquals("tt2", r.get(1).optString("id"));
        assertEquals("movie", r.get(1).optString("type"));
        assertEquals("tt3", r.get(2).optString("id"));
        assertEquals("series", r.get(2).optString("type"));
    }

    @Test public void searchSurvivesOneTypeFailing() {
        Stremio.Fetch fake = url -> { if (url.contains("/series/")) throw new java.io.IOException("HTTP 500"); return page("movie", "tt9"); };
        List<JSONObject> r = Stremio.search("x", fake);
        assertEquals(1, r.size());
        assertEquals("tt9", r.get(0).optString("id"));
    }

    @Test public void stuckAddonsDoNotStarveTheCatalogQueue() throws Exception {
        // a fila inteira dos addons parada (8 threads) e mais 4 pedidos à espera
        final CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 12; i++) Net.ADDONS.execute(() -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} });
        try {
            final CountDownLatch ran = new CountDownLatch(1);
            long t0 = System.nanoTime();
            Net.IO.execute(ran::countDown);   // catálogo / título / progresso
            assertTrue("a fila dos catálogos ficou presa atrás dos addons", ran.await(1, TimeUnit.SECONDS));
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 500);
        } finally {
            release.countDown();
        }
    }
}
