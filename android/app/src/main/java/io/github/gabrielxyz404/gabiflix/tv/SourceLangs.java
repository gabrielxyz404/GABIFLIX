package io.github.gabrielxyz404.gabiflix.tv;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Descobre os idiomas de áudio de uma fonte a partir do texto do addon (nome, título, descrição).
 * As MESMAS regras existem em gabiflix/detail.js (função sourceLangs): se mudar uma, mude a outra.
 * Devolve "🇧🇷 🇬🇧 +2", "Multi" ou "" (nada encontrado: não chuta).
 */
final class SourceLangs {
    private SourceLangs() {}

    private static final int MAX_FLAGS = 6;
    private static final String L = "a-z0-9\\u00e0-\\u00ff";
    // "legendado", "subs"…: quem fala de legenda não conta como áudio
    private static final String NOSUB = "(?![ ._-]*(?:subs?|subtitles?|legendas?|legendad[oa]s?)(?![" + L + "]))";

    /** palavra inteira (nada de letra/número colado dos lados) */
    private static Pattern word(String alt) {
        return Pattern.compile("(?:^|[^" + L + "])(?:" + alt + ")(?![" + L + "])" + NOSUB, Pattern.MULTILINE);
    }

    /** sigla de 3 letras (POR, ENG…): só entre separadores (/ , | . - _ [ ( ), nunca no meio de uma frase */
    private static Pattern code(String alt) {
        return Pattern.compile("(?:^|[^" + L + " ])(?:" + alt + ")(?=$|[^" + L + " ])" + NOSUB, Pattern.MULTILINE);
    }

    /** uma língua: bandeira, palavras "fortes" (qualquer linha), palavras "fracas" (nomes de países, que
     *  aparecem em títulos de filmes: ignora a 1ª linha do título) e siglas */
    private static final class Rule {
        final String flag;
        final Pattern strong, weak, code;

        Rule(String flag, String strong, String weak, String code) {
            this.flag = flag;
            this.strong = strong == null ? null : word(strong);
            this.weak = weak == null ? null : word(weak);
            this.code = code == null ? null : code(code);
        }
    }

    private static final Rule BR = new Rule("🇧🇷", "pt[-_. ]?br|brazilian|brasileir[oa]|dublad[oa]", null, null);
    private static final Rule PT = new Rule("🇵🇹", "pt[-_. ]?pt|portugu[eê]s europeu|european portuguese", null, null);
    private static final Pattern PT_GENERIC_WORD = word("portuguese|portugu[eê]s");
    private static final Pattern PT_GENERIC_CODE = code("por");
    private static final Rule US = new Rule("🇺🇸", "en[-_. ]?us|us english|american english", null, null);
    private static final Rule GB = new Rule("🇬🇧", null, "english|ingl[eê]s", "eng");
    private static final Rule MX = new Rule("🇲🇽", "latino|latin american", null, null);
    private static final Rule ES = new Rule("🇪🇸", null, "spanish|espa[nñ]ol|espanhol|castellano", "esp|spa");
    private static final Rule[] OTHERS = {
        new Rule("🇫🇷", null, "french|fran[cç]ais|franc[eê]s", "fre|fra|vff|vfq"),
        new Rule("🇮🇹", null, "italian|italiano", "ita"),
        new Rule("🇩🇪", null, "german|deutsch|alem[aã]o", "ger|deu"),
        new Rule("🇯🇵", null, "japanese|jap[oó]n[eê]s|japones", "jap|jpn"),
        new Rule("🇰🇷", null, "korean|coreano", "kor"),
        new Rule("🇮🇳", null, "hindi", "hin"),
        new Rule("🇷🇺", null, "russian|russo", "rus"),
    };
    private static final Pattern MULTI = word("multi(?:[ ._-]?(?:audios?|[aá]udios?|lang(?:uages?)?|idiomas?))?");

    private static boolean hit(Rule r, String all, String weak, boolean words) {
        if (r.strong != null && r.strong.matcher(all).find()) return true;
        if (r.code != null && r.code.matcher(all).find()) return true;
        return words && r.weak != null && r.weak.matcher(weak).find();
    }

    private static boolean isRegional(int cp) { return cp >= 0x1F1E6 && cp <= 0x1F1FF; }

    /** name e title/description do stream; resultado já pronto para mostrar */
    static String detect(String name, String title) {
        String rawName = name == null ? "" : name;
        String rawTitle = title == null ? "" : title;
        String n = rawName.toLowerCase(Locale.ROOT);
        String t = rawTitle.toLowerCase(Locale.ROOT);
        String all = n + "\n" + t;
        // texto das palavras fracas: sem a 1ª linha do título quando há mais linhas (ela é o nome do arquivo/filme)
        int nl = t.indexOf('\n');
        String weak = n + "\n" + (nl >= 0 ? t.substring(nl + 1) : t);

        LinkedHashSet<String> found = new LinkedHashSet<>();
        // 1) bandeiras que o addon já mandou (pares de indicadores regionais U+1F1E6..U+1F1FF)
        String raw = rawName + "\n" + rawTitle;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            int len = Character.charCount(cp);
            if (isRegional(cp) && i + len < raw.length()) {
                int cp2 = raw.codePointAt(i + len);
                if (isRegional(cp2)) {
                    found.add(new String(Character.toChars(cp)) + new String(Character.toChars(cp2)));
                    i += len + Character.charCount(cp2);
                    continue;
                }
            }
            i += len;
        }
        // 2) palavras e siglas
        boolean pt = hit(PT, all, weak, false);
        boolean br = hit(BR, all, weak, false);
        // "portuguese"/"português"/"por" sem qualificador: Brasil (a menos que diga PT-PT)
        if (!br && !pt && (PT_GENERIC_WORD.matcher(weak).find() || PT_GENERIC_CODE.matcher(all).find())) br = true;
        if (br) found.add(BR.flag);
        if (pt) found.add(PT.flag);
        boolean us = hit(US, all, weak, false);
        if (us) found.add(US.flag);
        if (hit(GB, all, weak, !us)) found.add(GB.flag);   // "US English" não vira 🇬🇧
        boolean mx = hit(MX, all, weak, false);
        if (mx) found.add(MX.flag);
        if (hit(ES, all, weak, !mx)) found.add(ES.flag);   // "Español Latino" não vira 🇪🇸
        for (Rule r : OTHERS) if (hit(r, all, weak, true)) found.add(r.flag);

        if (found.isEmpty()) return MULTI.matcher(all).find() ? "Multi" : "";
        // 🇧🇷 primeiro, depois 🇵🇹, depois o resto na ordem em que apareceu
        List<String> out = new ArrayList<>();
        if (found.remove(BR.flag)) out.add(BR.flag);
        if (found.remove(PT.flag)) out.add(PT.flag);
        out.addAll(found);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size() && i < MAX_FLAGS; i++) {
            if (i > 0) sb.append(' ');
            sb.append(out.get(i));
        }
        if (out.size() > MAX_FLAGS) sb.append(" +").append(out.size() - MAX_FLAGS);
        return sb.toString();
    }
}
