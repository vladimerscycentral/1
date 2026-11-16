package dev.pocketai;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;
import org.json.*;

/** Local sessions; migrates the single conversation from APK 1.1 on first save. */
final class AppState {
  static final String DEFAULT_SYSTEM =
      "Ты внимательный помощник по программированию. Отвечай по-русски. Честно обозначай"
          + " непроверенные части и ограничения.";
  static final int MESSAGE_LIMIT = 32000;
  private final SharedPreferences prefs;
  String endpoint, model, system, draft, activeId;
  boolean localHttp;
  JSONArray history, chats, prompts;

  AppState(Context context) {
    prefs = context.getSharedPreferences("workspace", 0);
    endpoint = prefs.getString("endpoint", "");
    model = prefs.getString("model", "");
    localHttp = prefs.getBoolean("localHttp", false);
    chats = array(prefs.getString("chats", "[]"));
    prompts = array(prefs.getString("prompts", "[]"));
    if (!prefs.contains("prompts")) {
      addPrompt("Разработчик", DEFAULT_SYSTEM);
      addPrompt(
          "Android",
          "Ты Android-разработчик. Помогай с Java, Kotlin, C++ и NDK. Учитывай версию Android и"
              + " разрешения.");
      addPrompt(
          "Разбор кода",
          "Проанализируй код, найди ошибки и предложи минимальное исправление. Не выдумывай"
              + " результаты запуска.");
    }
    if (chats.length() == 0)
      chats.put(
          object(
              "id",
              UUID.randomUUID().toString(),
              "name",
              "Чат 1",
              "history",
              array(prefs.getString("history", "[]")),
              "draft",
              prefs.getString("draft", ""),
              "system",
              prefs.getString("system", DEFAULT_SYSTEM)));
    activeId = prefs.getString("activeId", chats.optJSONObject(0).optString("id"));
    if (find(activeId) == null) activeId = chats.optJSONObject(0).optString("id");
    loadActive();
  }

  static JSONArray array(String value) {
    try {
      return new JSONArray(value);
    } catch (JSONException e) {
      return new JSONArray();
    }
  }

  static JSONObject object(Object... pairs) {
    JSONObject value = new JSONObject();
    try {
      for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return value;
  }

  JSONObject find(String id) {
    for (int i = 0; i < chats.length(); i++)
      if (chats.optJSONObject(i).optString("id").equals(id)) return chats.optJSONObject(i);
    return null;
  }

  private void loadActive() {
    JSONObject chat = find(activeId);
    history = chat.optJSONArray("history");
    if (history == null) history = new JSONArray();
    draft = chat.optString("draft");
    system = chat.optString("system", DEFAULT_SYSTEM);
  }

  private void flush() {
    JSONObject chat = find(activeId);
    if (chat != null)
      try {
        chat.put("history", history).put("draft", draft).put("system", system);
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
  }

  void select(String id) {
    if (find(id) == null) return;
    flush();
    activeId = id;
    loadActive();
    save();
  }

  void create() {
    flush();
    String id = UUID.randomUUID().toString();
    chats.put(
        object(
            "id",
            id,
            "name",
            "Чат " + (chats.length() + 1),
            "history",
            new JSONArray(),
            "draft",
            "",
            "system",
            system));
    activeId = id;
    loadActive();
    save();
  }

  void deleteActive() {
    for (int i = 0; i < chats.length(); i++)
      if (chats.optJSONObject(i).optString("id").equals(activeId)) {
        chats.remove(i);
        break;
      }
    if (chats.length() == 0) {
      create();
      return;
    }
    activeId = chats.optJSONObject(0).optString("id");
    loadActive();
    save();
  }

  void rename(String name) {
    try {
      find(activeId).put("name", name);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    save();
  }

  void addPrompt(String name, String text) {
    prompts.put(object("name", name, "text", text));
  }

  void message(String id, String role, String text) {
    JSONObject chat = find(id);
    if (chat == null) return;
    JSONArray messages = id.equals(activeId) ? history : chat.optJSONArray("history");
    messages.put(object("role", role, "content", text));
    // Keep complete user-led turns within the same limit as the original APK.
    while (messages.length() > 100) messages.remove(0);
    while (messages.length() > 0 && !"user".equals(messages.optJSONObject(0).optString("role")))
      messages.remove(0);
    save();
  }

  void save() {
    flush();
    prefs
        .edit()
        .putString("endpoint", endpoint)
        .putString("model", model)
        .putBoolean("localHttp", localHttp)
        .putString("chats", chats.toString())
        .putString("activeId", activeId)
        .putString("prompts", prompts.toString())
        .remove("history")
        .remove("draft")
        .remove("system")
        .apply();
  }
}
