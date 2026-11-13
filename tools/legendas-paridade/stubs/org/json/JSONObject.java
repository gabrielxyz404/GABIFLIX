package org.json;
import java.util.*;
/** JSON mínimo para o teste de paridade: parser completo, mas só com os métodos que o SubtitleSync usa */
public class JSONObject {
    public static final Object NULL = new Object() { public String toString() { return "null"; } };
    final Map<String, Object> map = new LinkedHashMap<>();
    JSONObject() {}
    public JSONObject(String s) throws Exception {
        P p = new P(s);
        Object v = p.value();
        if (!(v instanceof JSONObject)) throw new Exception("não é objeto");
        map.putAll(((JSONObject) v).map);
    }
    public JSONArray optJSONArray(String k) { Object o = map.get(k); return o instanceof JSONArray ? (JSONArray) o : null; }
    public JSONObject optJSONObject(String k) { Object o = map.get(k); return o instanceof JSONObject ? (JSONObject) o : null; }
    public String optString(String k, String d) { Object o = map.get(k); return o == null ? d : o instanceof String ? (String) o : String.valueOf(o); }
    public boolean has(String k) { return map.containsKey(k); }

    static final class P {
        final String s; int i = 0;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() throws Exception {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++; JSONObject o = new JSONObject(); ws();
                if (s.charAt(i) == '}') { i++; return o; }
                while (true) {
                    ws(); String k = str(); ws();
                    if (s.charAt(i++) != ':') throw new Exception("esperava :");
                    o.map.put(k, value()); ws();
                    char d = s.charAt(i++);
                    if (d == '}') return o;
                    if (d != ',') throw new Exception("esperava ,");
                }
            }
            if (c == '[') {
                i++; ArrayList<Object> l = new ArrayList<>(); ws();
                if (s.charAt(i) == ']') { i++; return new JSONArray(l); }
                while (true) {
                    l.add(value()); ws();
                    char d = s.charAt(i++);
                    if (d == ']') return new JSONArray(l);
                    if (d != ',') throw new Exception("esperava ,");
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return NULL; }
            int j = i;
            while (j < s.length() && "+-0123456789.eE".indexOf(s.charAt(j)) >= 0) j++;
            String n = s.substring(i, j); i = j;
            if (n.isEmpty()) throw new Exception("valor inválido");
            if (n.matches("-?\\d+")) return Long.parseLong(n);
            return Double.parseDouble(n);
        }
        String str() throws Exception {
            if (s.charAt(i) != '"') throw new Exception("esperava string");
            i++; StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char d = s.charAt(i++);
                    switch (d) {
                        case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: b.append(d);
                    }
                } else b.append(c);
            }
        }
    }
}
