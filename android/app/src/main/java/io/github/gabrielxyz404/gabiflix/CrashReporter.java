package io.github.gabrielxyz404.gabiflix;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Relatório de erros do app (sem dados pessoais). Três fontes:
 *  1) exceção Java não tratada (em qualquer processo): grava o rasto num ficheiro e deixa o sistema fechar o app como de costume;
 *  2) a partir do Android 11, o motivo da morte de cada processo ({@link ActivityManager#getHistoricalProcessExitReasons}), com o rasto nativo
 *     (tombstone) quando houve queda nativa; vale para o processo principal e para o ":audiocap";
 *  3) "migalhas": as últimas linhas do que o app fez (ciclo de vida das telas, registo da legenda/sincronização), guardadas em ficheiro, para
 *     explicar também o fecho que não deixa rasto nenhum.
 * No arranque seguinte mostra um aviso discreto e envia o relatório (texto simples) para o tópico ntfy abaixo. Se o envio falhar, tenta no próximo arranque.
 */
public final class CrashReporter {
    private CrashReporter() {}

    static final String TOPIC_URL = "https://ntfy.sh/frameplay-crash-7f3k9q2w";
    private static final int STACK_MAX = 8 * 1024, CRUMBS_MAX = 3 * 1024, PART_MAX = 3600;

    private static Context app;
    private static String proc = "main";
    private static int prevPid = -1;
    private static File dir;
    private static HandlerThread writerThread;
    private static Handler writer;
    private static final ArrayDeque<String> RING = new ArrayDeque<>();
    private static final SimpleDateFormat HMS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    // ------------------------------------------------------------ arranque (chamado pelo App.onCreate, em cada processo)

    static void install(Context c) {
        app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        try {
            String n = Build.VERSION.SDK_INT >= 28 ? android.app.Application.getProcessName() : null;
            if (n != null && n.contains(":")) proc = n.substring(n.indexOf(':') + 1);
        } catch (Throwable ignored) {}
        dir = new File(app.getFilesDir(), "crash");
        dir.mkdirs();
        // as migalhas do processo anterior ficam de lado (o relatório do arranque seguinte usa-as); este processo começa um ficheiro novo
        try {
            File cur = new File(dir, "crumbs-" + proc + ".txt"), prev = new File(dir, "crumbs-" + proc + ".prev.txt");
            if (cur.exists()) { prev.delete(); cur.renameTo(prev); }
        } catch (Throwable ignored) {}
        try {   // o pid do processo anterior (para ler o registo do sistema dele) e o deste
            File pf = new File(dir, "pid-" + proc + ".txt");
            if (pf.exists()) prevPid = Integer.parseInt(readFile(pf).trim());
            writeFile(pf, String.valueOf(android.os.Process.myPid()));
        } catch (Throwable ignored) {}
        writerThread = new HandlerThread("gf-crumbs", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        writerThread.start();
        writer = new Handler(writerThread.getLooper());
        crumb("processo " + proc + " iniciado, versão " + BuildConfigHelper.versionName(app) + ", pid " + android.os.Process.myPid());

        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                crumb("EXCEÇÃO NÃO TRATADA em " + t.getName());
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String body = header("CRASH_JAVA", proc) + "Thread: " + t.getName() + "\nExceção: " + e + "\n\nRasto:\n" + cut(sw.toString(), STACK_MAX)
                        + "\n\nMigalhas:\n" + tail(readCrumbsNow(), CRUMBS_MAX);
                writeFile(new File(dir, "java-" + System.currentTimeMillis() + "-" + proc + ".txt"), body);
            } catch (Throwable ignored) {}
            if (old != null) old.uncaughtException(t, e);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        });

        if ("main".equals(proc)) {
            Thread th = new Thread(CrashReporter::reportPending, "gf-crash-report");
            th.setPriority(Thread.MIN_PRIORITY);
            th.start();
        }
    }

    // ------------------------------------------------------------ migalhas

    /** memória do app e do aparelho numa linha (PSS do processo, pilha Java, livre no sistema, aviso de pouca memória) */
    static String memLine() {
        try {
            Runtime r = Runtime.getRuntime();
            String sys = "";
            if (app != null) {
                ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                sys = " livre=" + (mi.availMem >> 20) + "MB limiar=" + (mi.threshold >> 20) + "MB poucaMemoria=" + mi.lowMemory;
            }
            return "pss=" + (android.os.Debug.getPss() >> 10) + "MB java=" + ((r.totalMemory() - r.freeMemory()) >> 20) + "/" + (r.maxMemory() >> 20) + "MB nativo=" + (android.os.Debug.getNativeHeapAllocatedSize() >> 20) + "MB" + sys;
        } catch (Throwable t) { return "(memória: " + t + ")"; }
    }

    /** uma linha do que o app está a fazer (hora local no começo); vai para um ficheiro por processo, numa thread própria */
    static void crumb(String s) {
        try {
            final String line;
            synchronized (RING) {
                line = HMS.format(new Date()) + " " + s.replace('\n', ' ');
                RING.add(line);
                while (RING.size() > 80) RING.removeFirst();
            }
            Handler w = writer;
            if (w != null && dir != null) w.post(() -> appendFile(new File(dir, "crumbs-" + proc + ".txt"), line + "\n"));
        } catch (Throwable ignored) {}
    }

    private static String readCrumbsNow() {
        StringBuilder b = new StringBuilder();
        synchronized (RING) { for (String l : RING) b.append(l).append('\n'); }
        return b.toString();
    }

    private static String readCrumbsPrev(String procName) {
        try { return tail(readFile(new File(dir, "crumbs-" + procName + ".prev.txt")), CRUMBS_MAX); } catch (Throwable t) { return "(sem migalhas)"; }
    }

    // ------------------------------------------------------------ relatório do arranque seguinte

    private static final String PREFS = "crash-reporter";

    private static void reportPending() {
        try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
        try {
            SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            ArrayList<String> notice = new ArrayList<>();

            // 1) rastos Java gravados pelo tratador (qualquer processo)
            File[] fs = dir.listFiles((d, n) -> n.startsWith("java-") && n.endsWith(".txt"));
            if (fs != null) for (File f : fs) {
                String body = readFile(f);
                if (send("CRASH_JAVA", body)) { f.delete(); }
                if (System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L) notice.add("CRASH_JAVA-" + shortId(f.lastModified()));
            }

            // 1b) Android 9/10 (Fire TV Stick 4K): não há motivo da morte; se o player estava aberto quando o processo anterior sumiu, manda as migalhas
            //     e o registo do sistema desse processo (uma queda nativa deixa "Fatal signal ..." escrito pelo próprio processo)
            if (Build.VERSION.SDK_INT < 30 && prevPid > 0) {
                String prev = readCrumbsPrev("main");
                if (playerWasAlive(prev)) {
                    String lg = prevLogcat(prevPid);
                    String body = header("MORTE_SUSPEITA", "main") + "O processo anterior (pid " + prevPid + ") sumiu com a tela do player aberta (sem exceção Java gravada).\n\nRegisto do sistema (W+):\n"
                            + tail(lg, 4 * 1024) + "\n\nMigalhas:\n" + prev;
                    send("MORTE_SUSPEITA", body);
                    notice.add("MORTE-" + shortId(System.currentTimeMillis()));
                }
            }

            // 2) motivo da morte dos processos (Android 11+)
            if (Build.VERSION.SDK_INT >= 30) {
                ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, 12);
                String sent = sp.getString("sent", "");
                StringBuilder newSent = new StringBuilder(sent);
                int count = 0;
                for (ApplicationExitInfo i : list) {
                    int r = i.getReason();
                    if (r == ApplicationExitInfo.REASON_EXIT_SELF || r == ApplicationExitInfo.REASON_USER_REQUESTED || r == 11 /* USER_STOPPED */) continue;
                    if (i.getProcessName() == null || !i.getProcessName().startsWith(app.getPackageName())) continue;   // processos do WebView e afins não são do app
                    String id = i.getPid() + "@" + i.getTimestamp();
                    if (sent.contains("|" + id + "|") || count >= 6) continue;
                    count++;
                    String name = reasonName(r);
                    String p = i.getProcessName() == null ? "?" : i.getProcessName();
                    String pn = p.contains(":") ? p.substring(p.indexOf(':') + 1) : "main";
                    StringBuilder b = new StringBuilder(header(name, p));
                    b.append("Código: ").append(r).append("  estado: ").append(i.getStatus()).append("  importância: ").append(i.getImportance()).append('\n');
                    b.append("Hora da morte: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(i.getTimestamp()))).append('\n');
                    b.append("Descrição: ").append(i.getDescription()).append("\nPSS: ").append(i.getPss()).append(" kB  RSS: ").append(i.getRss()).append(" kB\n");
                    if (r == ApplicationExitInfo.REASON_CRASH_NATIVE || r == ApplicationExitInfo.REASON_ANR) {
                        try (InputStream in = i.getTraceInputStream()) {
                            if (in != null) b.append("\nRasto do sistema (").append(r == ApplicationExitInfo.REASON_ANR ? "ANR" : "tombstone").append("):\n").append(printable(readAll(in, 96 * 1024), STACK_MAX)).append('\n');
                        } catch (Throwable t) { b.append("\n(rasto não lido: ").append(t).append(")\n"); }
                    }
                    b.append("\nMigalhas do processo ").append(pn).append(" antes da morte:\n").append(readCrumbsPrev(pn));
                    if (send(name, b.toString())) { newSent.append(id).append('|'); if (!newSent.toString().startsWith("|")) newSent.insert(0, '|'); }
                    boolean serious = r == ApplicationExitInfo.REASON_CRASH || r == ApplicationExitInfo.REASON_CRASH_NATIVE || r == ApplicationExitInfo.REASON_ANR
                            || r == ApplicationExitInfo.REASON_LOW_MEMORY || r == ApplicationExitInfo.REASON_SIGNALED || r == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                            || r == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE;
                    if (serious && System.currentTimeMillis() - i.getTimestamp() < 6 * 3600_000L && !sp.getString("noticed", "").contains(id)) {
                        notice.add(name + "-" + shortId(i.getTimestamp()));
                        sp.edit().putString("noticed", id).apply();
                    }
                }
                String ns = newSent.toString();
                if (ns.length() > 600) ns = ns.substring(ns.length() - 600);
                sp.edit().putString("sent", ns).apply();
            }

            if (!notice.isEmpty()) {
                final String code = notice.get(0);
                new Handler(Looper.getMainLooper()).post(() -> {
                    try { Toast.makeText(app, "A app fechou por um erro (código " + code + ")", Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable t) {
            crumb("relatório: erro " + t);
        }
    }

    /** nas migalhas do processo anterior: a última tela do player foi criada e nunca destruída */
    private static boolean playerWasAlive(String crumbs) {
        int c = crumbs.lastIndexOf("PlayerActivity criada"), d = crumbs.lastIndexOf("PlayerActivity destruída");
        return c >= 0 && d < c;
    }

    /** o que o sistema registou (aviso ou pior) para o pid; um app só enxerga o registo do próprio UID, que inclui o "Fatal signal" do processo que caiu */
    private static String prevLogcat(int pid) {
        try {
            final Process pr = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=" + pid, "-t", "600", "*:W").redirectErrorStream(true).start();
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            Thread rd = new Thread(() -> { try (InputStream in = pr.getInputStream()) { bo.write(readAll(in, 200 * 1024)); } catch (Throwable ignored) {} });
            rd.start();
            rd.join(6000);
            pr.destroy();
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) { return "(sem registo: " + t + ")"; }
    }

    /** relatório de um fecho inesperado que não deixa rasto do sistema (por exemplo, a tela do player fechada sem o usuário pedir) */
    static void event(String kind, String detail) {
        crumb("EVENTO " + kind + ": " + detail);
        if (app == null) return;
        final String body = header(kind, proc) + detail + "\n\nMigalhas:\n" + tail(readCrumbsNow(), CRUMBS_MAX);
        Thread th = new Thread(() -> send(kind, body), "gf-crash-event");
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
    }

    // ------------------------------------------------------------ só nos testes: am start --es gfCrashTest post|java|native|event

    static void testHook(Activity a) {
        String t;
        try { t = a.getIntent().getStringExtra("gfCrashTest"); } catch (Throwable e) { return; }
        if (t == null) return;
        crumb("teste do relatório: " + t);
        final Handler h = new Handler(Looper.getMainLooper());
        switch (t) {
            case "post":
                new Thread(() -> {
                    boolean ok = send("TESTE", header("TESTE", proc) + "Envio de teste do relatório de erros (sem dados pessoais).\n");
                    android.util.Log.i("GFCrash", "teste post: " + (ok ? "ENVIADO (HTTP 2xx)" : "FALHOU") + " " + lastHttp);
                }, "gf-crash-test").start();
                break;
            case "java": h.postDelayed(() -> { throw new IllegalStateException("queda de teste (Java)"); }, 1500); break;
            case "native": h.postDelayed(() -> android.os.Process.sendSignal(android.os.Process.myPid(), 6), 1500); break;   // SIGABRT
            case "event": event("TESTE_EVENTO", "evento de teste"); break;
            default: break;
        }
    }

    // ------------------------------------------------------------ texto e rede

    private static String header(String kind, String procName) {
        StringBuilder b = new StringBuilder();
        b.append("FramePlay ").append(BuildConfigHelper.versionName(app)).append(" | ").append(kind).append(" | processo ").append(procName).append('\n');
        b.append("Aparelho: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" | Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")");
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            b.append(" | RAM ").append(mi.totalMem >> 20).append(" MB, livre ").append(mi.availMem >> 20).append(" MB");
        } catch (Throwable ignored) {}
        b.append(" | tv=").append(MainActivity.isTv(app)).append('\n');
        return b.toString();
    }

    private static String reasonName(int r) {
        switch (r) {
            case 0: return "DESCONHECIDO"; case 1: return "EXIT_SELF"; case 2: return "SIGNALED"; case 3: return "LOW_MEMORY"; case 4: return "CRASH";
            case 5: return "CRASH_NATIVE"; case 6: return "ANR"; case 7: return "INITIALIZATION_FAILURE"; case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE"; case 10: return "USER_REQUESTED"; case 11: return "USER_STOPPED"; case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER"; case 14: return "FREEZER"; case 15: return "PACKAGE_STATE_CHANGE"; case 16: return "PACKAGE_UPDATED";
            default: return "MOTIVO_" + r;
        }
    }

    private static String shortId(long ts) { return Long.toHexString(ts / 1000L & 0xFFFFL); }

    static volatile String lastHttp = "";

    /** POST de texto simples ao tópico; divide em partes de ~3,6 KB (o ntfy transforma mensagens maiores em anexo). true se todas as partes foram aceitas */
    private static boolean send(String kind, String text) {
        try {
            android.util.Log.i("GFCrash", "corpo de " + kind + ":\n" + text.substring(0, Math.min(text.length(), 1800)));
            byte[] all = text.getBytes(StandardCharsets.UTF_8);
            int parts = Math.max(1, (all.length + PART_MAX - 1) / PART_MAX);
            if (parts > 4) parts = 4;
            for (int p = 0; p < parts; p++) {
                int from = p * PART_MAX, to = Math.min(all.length, from + PART_MAX);
                byte[] chunk = java.util.Arrays.copyOfRange(all, from, to);
                HttpURLConnection c = (HttpURLConnection) new URL(TOPIC_URL).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                c.setRequestProperty("User-Agent", "FramePlay-crash");
                c.setRequestProperty("Title", "FramePlay " + BuildConfigHelper.versionName(app) + " " + kind.replaceAll("[^A-Za-z0-9_ -]", "") + (parts > 1 ? " (" + (p + 1) + "/" + parts + ")" : ""));
                c.setRequestProperty("Tags", "warning");
                try (OutputStream os = c.getOutputStream()) { os.write(chunk); }
                int code = c.getResponseCode();
                lastHttp = "HTTP " + code;
                android.util.Log.i("GFCrash", "POST " + kind + " parte " + (p + 1) + "/" + parts + " (" + chunk.length + " bytes) -> HTTP " + code);
                c.disconnect();
                if (code < 200 || code >= 300) return false;
            }
            return true;
        } catch (Throwable t) {
            lastHttp = "erro " + t;
            android.util.Log.w("GFCrash", "POST " + kind + " falhou: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------ ficheiros e texto

    private static void writeFile(File f, String s) throws java.io.IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); o.getFD().sync(); }
    }

    private static void appendFile(File f, String s) {
        try {
            if (f.length() > 64 * 1024) {   // não deixa crescer sem fim: guarda o fim
                String t = tail(readFile(f), 24 * 1024);
                writeFile(f, t);
            }
            try (FileOutputStream o = new FileOutputStream(f, true)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        } catch (Throwable ignored) {}
    }

    private static String readFile(File f) throws java.io.IOException {
        try (InputStream in = new java.io.FileInputStream(f)) { return new String(readAll(in, 256 * 1024), StandardCharsets.UTF_8); }
    }

    private static byte[] readAll(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0 && bo.size() < max) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    private static String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "\n…(cortado)"; }

    private static String tail(String s, int max) { return s.length() <= max ? s : "…" + s.substring(s.length() - max); }

    /** o rasto nativo vem em formato binário (protobuf) a partir do Android 12: tira as sequências de texto legíveis (nomes de funções, ficheiros, mensagem de abort) */
    private static String printable(byte[] b, int max) {
        boolean text = true;
        for (int i = 0; i < Math.min(b.length, 400); i++) { int c = b[i] & 0xFF; if (c < 9 || (c > 13 && c < 32) || c > 126) { text = false; break; } }
        if (text) return cut(new String(b, StandardCharsets.UTF_8), max);
        StringBuilder out = new StringBuilder(), run = new StringBuilder();
        for (byte x : b) {
            int c = x & 0xFF;
            if (c >= 32 && c <= 126) run.append((char) c);
            else { if (run.length() >= 5) out.append(run).append('\n'); run.setLength(0); }
        }
        if (run.length() >= 5) out.append(run);
        return cut(out.toString(), max);
    }
}
