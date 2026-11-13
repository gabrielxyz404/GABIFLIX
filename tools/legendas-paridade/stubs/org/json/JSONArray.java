package org.json;
import java.util.List;
/** JSON mínimo para o teste de paridade (só o que o SubtitleSync usa) */
public class JSONArray {
    final List<Object> items;
    JSONArray(List<Object> items) { this.items = items; }
    public int length() { return items.size(); }
    public JSONObject optJSONObject(int i) { Object o = i >= 0 && i < items.size() ? items.get(i) : null; return o instanceof JSONObject ? (JSONObject) o : null; }
}
