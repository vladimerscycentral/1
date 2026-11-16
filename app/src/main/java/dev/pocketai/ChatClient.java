package dev.pocketai;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

public final class ChatClient {
  public static String baseUrl(String raw, boolean allowLocalHttp) throws IOException {
    try {
      URI u = new URI(raw.trim());
      if (u.getHost() == null
          || u.getUserInfo() != null
          || u.getQuery() != null
          || u.getFragment() != null
          || u.getPort() > 65535
          || u.getPort() == 0) throw new IOException("Укажи адрес API без логина, параметров и #.");
      if (!"https".equalsIgnoreCase(u.getScheme())
          && !("http".equalsIgnoreCase(u.getScheme()) && allowLocalHttp && localHost(u.getHost())))
        throw new IOException("Нужен HTTPS. Для локального IP включи HTTP в настройках.");
      return u.toASCIIString().replaceAll("/+$", "").replaceAll("/(chat/completions|models)$", "");
    } catch (URISyntaxException e) {
      throw new IOException("Неверный адрес API.");
    }
  }

  private static boolean localHost(String host) {
    if (host.equalsIgnoreCase("localhost") || host.equals("[::1]")) return true;
    String[] p = host.split("\\.");
    if (p.length != 4) return false;
    int[] n = new int[4];
    for (int i = 0; i < 4; i++) {
      if (!p[i].matches("[0-9]{1,3}")) return false;
      n[i] = Integer.parseInt(p[i]);
      if (n[i] > 255) return false;
    }
    return n[0] == 127
        || n[0] == 10
        || n[0] == 192 && n[1] == 168
        || n[0] == 172 && n[1] >= 16 && n[1] <= 31;
  }

  static final class Response {
    final int code;
    final String body;

    Response(int code, String body) {
      this.code = code;
      this.body = body;
    }
  }

  public static final class Call {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile HttpURLConnection connection;

    public void cancel() {
      cancelled.set(true);
      HttpURLConnection c = connection;
      if (c != null) c.disconnect();
    }

    void check() throws IOException {
      if (cancelled.get() || Thread.currentThread().isInterrupted())
        throw new IOException(
            "Остановлено. Проверь результат уже отправленных изменений перед повтором.");
    }

    Response request(String url, String token, String method, JSONObject body, boolean github)
        throws IOException {
      check();
      HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
      connection = c;
      try {
        check();
        c.setConnectTimeout(12000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", github ? "application/vnd.github+json" : "application/json");
        if (github) c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (token.contains("\n") || token.contains("\r"))
          throw new IOException("Токен должен быть в одну строку.");
        if (!token.trim().isEmpty())
          c.setRequestProperty("Authorization", "Bearer " + token.trim());
        if (body != null) {
          byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
          c.setDoOutput(true);
          c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
          c.setFixedLengthStreamingMode(bytes.length);
          check();
          try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
          }
        }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) return new Response(code, "");
        try (InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
          byte[] bytes = new byte[8192];
          int n;
          while ((n = in.read(bytes)) != -1) {
            check();
            if (out.size() + n > 4 * 1024 * 1024)
              throw new IOException("Слишком большой ответ сервера.");
            out.write(bytes, 0, n);
          }
          return new Response(code, out.toString("UTF-8"));
        }
      } finally {
        c.disconnect();
        connection = null;
      }
    }

    public String chat(String base, String key, String model, String system, JSONArray history)
        throws Exception {
      if (model.trim().isEmpty()) throw new IOException("Укажи модель в настройках.");
      JSONArray messages = new JSONArray();
      messages.put(AppState.object("role", "system", "content", system));
      int start = Math.max(0, history.length() - 20);
      while (start < history.length()
          && !"user".equals(history.getJSONObject(start).optString("role"))) start++;
      for (int i = start; i < history.length(); i++) messages.put(history.getJSONObject(i));
      Response r =
          request(
              base + "/chat/completions",
              key,
              "POST",
              AppState.object("model", model, "stream", false, "messages", messages),
              false);
      if (r.code != 200)
        throw new IOException(
            "API модели: HTTP " + r.code + ". Проверь ключ, адрес и доступность модели.");
      JSONObject m =
          new JSONObject(r.body).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
      Object content = m.opt("content");
      String answer = "";
      if (content instanceof String) answer = (String) content;
      else if (content instanceof JSONArray) {
        StringBuilder b = new StringBuilder();
        JSONArray blocks = (JSONArray) content;
        for (int i = 0; i < blocks.length(); i++) {
          JSONObject block = blocks.optJSONObject(i);
          if (block != null && "text".equals(block.optString("type")))
            b.append(block.optString("text"));
        }
        answer = b.toString();
      }
      if (answer.trim().isEmpty())
        throw new IOException("Модель вернула пустой ответ. Выбери другую модель.");
      if (answer.length() > 32000)
        throw new IOException("Ответ длиннее 32000 символов. Попроси модель разбить изменения.");
      return answer;
    }

    public List<String> models(String base, String key) throws Exception {
      Response r = request(base + "/models", key, "GET", null, false);
      if (r.code != 200) throw new IOException("Список моделей: HTTP " + r.code);
      JSONArray data = new JSONObject(r.body).getJSONArray("data");
      List<String> ids = new ArrayList<>();
      for (int i = 0; i < data.length() && ids.size() < 2000; i++) {
        String id = data.getJSONObject(i).optString("id");
        if (!id.isEmpty()) ids.add(id);
      }
      if (ids.isEmpty()) throw new IOException("Список пуст. Введи ID модели вручную.");
      Collections.sort(ids);
      return ids;
    }
  }
}
