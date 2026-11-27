package io.github.gabrielxyz404.gabiflix.tv;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Auditoria 2026-11-27: addons lentos não prendem a fila dos catálogos. */
public class NetStremioTest {

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
