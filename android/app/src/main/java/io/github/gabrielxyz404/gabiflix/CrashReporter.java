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
                String sent = sentIds();
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
                    if (r == ApplicationExitInfo.REASON_CRASH_NATIVE) {   // o Android 11 não dá o rasto: o próprio processo escreveu "Fatal signal" no registo do sistema
                        b.append("\nRegisto de queda do sistema (logcat, filtrado):\n").append(crashLogcat(i.getPid())).append('\n');
                    }
                    b.append("\nMigalhas do processo ").append(pn).append(" antes da morte:\n").append(readCrumbsPrev(pn));
                    if (send(name, b.toString())) markSent(id);
                    boolean serious = r == ApplicationExitInfo.REASON_CRASH || r == ApplicationExitInfo.REASON_CRASH_NATIVE || r == ApplicationExitInfo.REASON_ANR
                            || r == ApplicationExitInfo.REASON_LOW_MEMORY || r == ApplicationExitInfo.REASON_SIGNALED || r == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                            || r == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE;
                    if (serious && System.currentTimeMillis() - i.getTimestamp() < 6 * 3600_000L && !sp.getString("noticed", "").contains(id)) {
                        notice.add(name + "-" + shortId(i.getTimestamp()));
                        sp.edit().putString("noticed", id).apply();
                    }
                }
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
            case "silero": silero(a); break;
            default: break;
        }
    }

    // ------------------------------------------------------------ etapa nativa em curso (para explicar uma queda sem rasto)

    private static final Object STAGE_LOCK = new Object();

    /**
     * Marca, de forma SÍNCRONA (com fsync), a etapa nativa que este processo vai começar (por exemplo "captura cfg=0 play" ou "vad ort-inferência"):
     * se o processo cair sem deixar rasto (Android 11 não dá o tombstone), o ficheiro diz o que estava a correr. Chamado ANTES de cada etapa nativa.
     * Guarda só as últimas linhas. Cada processo tem o seu ficheiro (stage-main.txt, stage-audiocap.txt).
     */
    static void stage(String what) {
        crumb("ETAPA " + what);
        File d = dir;
        if (d == null) return;
        try {
            synchronized (STAGE_LOCK) {
                File f = new File(d, "stage-" + proc + ".txt");
                String old = "";
                try { old = readFile(f); } catch (Throwable ignored) {}
                String[] ls = old.isEmpty() ? new String[0] : old.split("\n");
                StringBuilder b = new StringBuilder();
                for (int i = Math.max(0, ls.length - 11); i < ls.length; i++) b.append(ls[i]).append('\n');
                b.append(HMS.format(new Date())).append(" pid=").append(android.os.Process.myPid()).append(' ').append(what.replace('\n', ' ')).append('\n');
                writeFile(f, b.toString());
            }
        } catch (Throwable ignored) {}
    }

    /** o conteúdo do ficheiro de etapas do processo `procName` (vazio se não existe) */
    static String readStage(String procName) {
        try { return dir == null ? "" : readFile(new File(dir, "stage-" + procName + ".txt")); } catch (Throwable t) { return ""; }
    }

    /** as migalhas de um processo (do arquivo atual, ou do anterior se o processo já foi substituído por um novo) */
    static String readCrumbsOf(String procName) {
        try {
            File cur = new File(dir, "crumbs-" + procName + ".txt");
            return cur.exists() ? tail(readFile(cur), CRUMBS_MAX) : readCrumbsPrev(procName);
        } catch (Throwable t) { return "(sem migalhas)"; }
    }

    static String abiLine() {
        try {
            return java.util.Arrays.toString(Build.SUPPORTED_ABIS) + " (64 bits: " + java.util.Arrays.toString(Build.SUPPORTED_64_BIT_ABIS) + ") processo=" + (android.os.Process.is64Bit() ? "64" : "32") + " bits";
        } catch (Throwable t) { return "?"; }
    }

    private static synchronized String sentIds() {
        return app == null ? "" : app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sent", "");
    }

    private static synchronized void markSent(String id) {
        try {
            SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String ns = sp.getString("sent", "");
            if (ns.contains("|" + id + "|")) return;
            ns = (ns.isEmpty() ? "|" : ns) + id + "|";
            if (ns.length() > 600) ns = ns.substring(ns.length() - 600);
            if (!ns.startsWith("|")) ns = ns.substring(ns.indexOf('|'));
            sp.edit().putString("sent", ns).apply();
        } catch (Throwable ignored) {}
    }

    private static String runCmd(String[] cmd, long ms) {
        try {
            final Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            Thread rd = new Thread(() -> { try (InputStream in = pr.getInputStream()) { bo.write(readAll(in, 600 * 1024)); } catch (Throwable ignored) {} });
            rd.start();
            rd.join(ms);
            pr.destroy();
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) { return "(sem registo: " + t + ")"; }
    }

    private static final java.util.regex.Pattern CRASH_KEY = java.util.regex.Pattern.compile(
            "Fatal signal|backtrace|#\\d+\\s+pc|Abort message|signal \\d+ \\(|pid: \\d+|Cause:|fault addr|ABI:|Build fingerprint|Cmdline|SIGSEGV|SIGABRT|SIGILL|SIGBUS|\\*\\*\\* \\*\\*\\*");

    /**
     * O que o sistema registou da queda nativa do processo `pid` (0 = não sei qual), lido do próprio registo do app (uma app enxerga o do seu UID):
     * o buffer "crash" (bloco do tombstone com "pid:", "signal", "backtrace", "#00 pc ...", "Abort message") e a linha "Fatal signal" do buffer principal.
     */
    static String crashLogcat(int pid) {
        try {
            String crash = runCmd(new String[]{"logcat", "-d", "-b", "crash", "-v", "time", "-t", "2500"}, 7000);
            String main = runCmd(new String[]{"logcat", "-d", "-b", "main", "-v", "time", "-t", "6000", "libc:F", "DEBUG:V", "*:S"}, 7000);
            StringBuilder out = new StringBuilder();
            java.util.regex.Pattern pp = pid > 0 ? java.util.regex.Pattern.compile("(?<![0-9])" + pid + "(?![0-9])") : null;
            for (String text : new String[]{main, crash}) {
                boolean in = pid <= 0;
                ArrayList<String> keep = new ArrayList<>();
                for (String l : text.split("\n")) {
                    if (pp != null) {
                        boolean mentions = pp.matcher(l).find();
                        if (l.contains("*** *** ***") && !mentions) in = false;
                        if (mentions) in = true;
                    }
                    if (in && CRASH_KEY.matcher(l).find()) keep.add(l.trim().replaceAll(" \\(BuildId: [0-9a-f]+\\)", ""));
                }
                int from = Math.max(0, keep.size() - 50);
                for (int i = from; i < keep.size(); i++) out.append(keep.get(i)).append('\n');
            }
            if (out.length() == 0) return "(nenhuma linha de queda do pid " + pid + " no registo do sistema; buffer crash=" + crash.length() + " bytes, principal=" + main.length() + " bytes)";
            return cut(out.toString(), 5 * 1024);
        } catch (Throwable t) { return "(sem registo de queda: " + t + ")"; }
    }

    private static final java.util.concurrent.atomic.AtomicInteger deathReports = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * O processo de captura (:audiocap) acabou de cair durante uma análise: manda o relatório CAPTURA_CAIU JÁ, sem esperar o próximo arranque.
     * @param kind       etapa em curso segundo o processo principal ("captura" ou "vad")
     * @param detail     configuração e circunstâncias (texto livre)
     * @param stageSnap  ficheiro de etapas do processo que caiu, copiado no momento da queda (o novo processo o reescreve)
     * @param crumbSnap  migalhas do processo que caiu, copiadas no momento da queda
     */
    static void audiocapDied(final String kind, final String detail, final String stageSnap, final String crumbSnap, final long diedAt) {
        if (app == null || deathReports.incrementAndGet() > 6) return;
        crumb("CAPTURA_CAIU etapa=" + kind + " " + detail);
        Thread th = new Thread(() -> {
            try {
                String lastStage = "";
                int deadPid = 0;
                String[] ls = stageSnap.split("\n");
                for (int i = ls.length - 1; i >= 0; i--) if (!ls[i].trim().isEmpty()) { lastStage = ls[i]; break; }
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("pid=(\\d+)").matcher(lastStage);
                if (m.find()) deadPid = Integer.parseInt(m.group(1));
                StringBuilder b = new StringBuilder(header("CAPTURA_CAIU", "audiocap"));
                b.append("Etapa em curso (processo principal): ").append(kind).append("  ").append(detail).append('\n');
                b.append("Última etapa marcada pelo processo que caiu: ").append(lastStage.isEmpty() ? "(nenhuma)" : lastStage).append('\n');
                b.append("Hora da queda: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(diedAt))).append('\n');
                String exitId = null;
                if (Build.VERSION.SDK_INT >= 30) {
                    ApplicationExitInfo hit = null;
                    for (int tries = 0; tries < 4 && hit == null; tries++) {
                        try { Thread.sleep(tries == 0 ? 1500 : 2000); } catch (InterruptedException ignored) {}
                        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
                        for (ApplicationExitInfo i : am.getHistoricalProcessExitReasons(null, 0, 12)) {
                            String pn = i.getProcessName();
                            if (pn == null || !pn.startsWith(app.getPackageName()) || !pn.endsWith(":audiocap")) continue;
                            if (i.getTimestamp() < diedAt - 15_000L) continue;
                            if (sentIds().contains("|" + i.getPid() + "@" + i.getTimestamp() + "|")) continue;
                            if (deadPid > 0 && i.getPid() != deadPid) continue;
                            hit = i;
                            break;
                        }
                    }
                    if (hit != null) {
                        exitId = hit.getPid() + "@" + hit.getTimestamp();
                        deadPid = hit.getPid();
                        int r = hit.getReason();
                        b.append("Motivo da morte (getHistoricalProcessExitReasons): ").append(reasonName(r)).append(" (").append(r).append(")  estado: ").append(hit.getStatus())
                                .append("  pid: ").append(hit.getPid()).append("  importância: ").append(hit.getImportance()).append('\n');
                        b.append("Descrição: ").append(hit.getDescription()).append("  PSS: ").append(hit.getPss()).append(" kB  RSS: ").append(hit.getRss()).append(" kB\n");
                        if (r == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                            try (InputStream in = hit.getTraceInputStream()) {
                                if (in != null) b.append("\nRasto do sistema (tombstone):\n").append(printable(readAll(in, 96 * 1024), STACK_MAX)).append('\n');
                            } catch (Throwable t) { b.append("\n(rasto não lido: ").append(t).append(")\n"); }
                        }
                    } else b.append("Motivo da morte: o sistema ainda não registou (getHistoricalProcessExitReasons sem entrada de :audiocap)\n");
                } else b.append("Motivo da morte: indisponível antes do Android 11 (API ").append(Build.VERSION.SDK_INT).append(")\n");
                b.append("\nRegisto de queda do sistema (logcat, filtrado, pid ").append(deadPid).append("):\n").append(crashLogcat(deadPid)).append('\n');
                b.append("\nEtapas marcadas (últimas):\n").append(stageSnap);
                b.append("\nMigalhas do processo que caiu:\n").append(crumbSnap);
                if (send("CAPTURA_CAIU", b.toString()) && exitId != null) markSent(exitId);
            } catch (Throwable t) { crumb("CAPTURA_CAIU: relatório falhou " + t); }
        }, "gf-capdied-report");
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
    }

    /** só nos testes (gfCrashTest=silero): roda o Silero de verdade no processo :audiocap sobre 20 s de ruído e registra se funcionou, em que ABI e quanto demorou */
    private static void silero(final Activity a) {
        new Thread(() -> {
            String abi = abiLine();
            try {
                AudioCap c = new AudioCap(a);
                boolean allowed = AudioCap.sileroAllowed(a);
                File in = new File(a.getCacheDir(), "sv-in.raw"), out = new File(a.getCacheDir(), "sv-out.raw");
                java.util.Random rnd = new java.util.Random(7);
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16000 * 20 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < 16000 * 20; i++) bb.putShort((short) ((rnd.nextGaussian() * 3000) * (0.5 + 0.5 * Math.sin(i / 1600.0))));
                try (FileOutputStream fo = new FileOutputStream(in)) { fo.write(bb.array()); }
                long t0 = System.nanoTime();
                boolean ok = allowed && c.vad(in, out);
                android.util.Log.i("GFCrash", "teste silero: abi=" + abi + " permitido=" + allowed + " ok=" + ok + " ms=" + (System.nanoTime() - t0) / 1_000_000 + " saida=" + (out.exists() ? out.length() : -1) + " bytes");
                AudioCap.close();
            } catch (Throwable t) { android.util.Log.w("GFCrash", "teste silero: erro " + t + " abi=" + abi); }
        }, "gf-silero-test").start();
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
        b.append("ABI: ").append(abiLine()).append('\n');
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
            android.util.Log.i("GFCrash", "corpo de " + kind + ":\n" + text.substring(0, Math.min(text.length(), 15000)));
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
