package dev.pocketai;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    final boolean githubRateLimited, githubSsoRequired;
    final String retryAfter;

    Response(int code, String body) {
      this(code, body, false, false, null);
    }

    Response(int code, String body, boolean githubRateLimited, boolean githubSsoRequired) {
      this(code, body, githubRateLimited, githubSsoRequired, null);
    }

    Response(
        int code,
        String body,
        boolean githubRateLimited,
        boolean githubSsoRequired,
        String retryAfter) {
      this.code = code;
      this.body = body;
      this.githubRateLimited = githubRateLimited;
      this.githubSsoRequired = githubSsoRequired;
      this.retryAfter = retryAfter;
    }
  }

  public static final class Call {
    interface WaitStrategy {
      void waitFor(long millis) throws IOException;
    }

    public interface RetryListener {
      void waiting(long millis, int attempt);

      void retrying();
    }

    private static final int MAX_RETRIES = 2;
    private static final long MAX_WAIT_MS = 60000;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CountDownLatch cancelSignal = new CountDownLatch(1);
    private final WaitStrategy waitStrategy;
    private volatile HttpURLConnection connection;

    public Call() {
      this.waitStrategy = this::waitForCancellation;
    }

    Call(WaitStrategy waitStrategy) {
      this.waitStrategy = waitStrategy;
    }

    public void cancel() {
      cancelled.set(true);
      cancelSignal.countDown();
      HttpURLConnection c = connection;
      if (c != null) c.disconnect();
    }

    boolean isCancelled() {
      return cancelled.get();
    }

    void check() throws IOException {
      if (cancelled.get() || Thread.currentThread().isInterrupted())
        throw new IOException(
            "Остановлено. Проверь результат уже отправленных изменений перед повтором.");
    }

    private void waitForCancellation(long millis) throws IOException {
      try {
        cancelSignal.await(millis, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Запрос остановлен.", e);
      }
      check();
    }

    // Retry-After is either nonnegative seconds or an HTTP date. -1 means absent/invalid.
    static long retryAfterMillis(String value, long nowMillis) {
      if (value == null || value.trim().isEmpty()) return -1;
      String trimmed = value.trim();
      if (trimmed.matches("[0-9]+")) {
        try {
          long seconds = Long.parseLong(trimmed);
          return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
        } catch (NumberFormatException overflow) {
          return Long.MAX_VALUE;
        }
      }
      try {
        long dateMillis =
            ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli();
        return dateMillis <= nowMillis ? 0 : dateMillis - nowMillis;
      } catch (DateTimeParseException | ArithmeticException invalid) {
        return -1;
      }
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
        if (code < 200 || code >= 300) {
          if (!github && code == 429)
            return new Response(code, "", false, false, c.getHeaderField("Retry-After"));
          if (!github || code != 403) return new Response(code, "");
          String detail = "";
          try (InputStream error = c.getErrorStream();
              ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (error != null) {
              byte[] chunk = new byte[4096];
              int n;
              while ((n = error.read(chunk)) != -1) {
                check();
                if (out.size() + n > 16384) {
                  out.reset();
                  break;
                }
                out.write(chunk, 0, n);
              }
              detail = out.toString("UTF-8");
            }
          } catch (IOException ignored) {
            check(); // Respect cancellation; otherwise retain the HTTP status.
          }
          String sso = c.getHeaderField("X-GitHub-SSO");
          return new Response(
              code,
              detail,
              "0".equals(c.getHeaderField("X-RateLimit-Remaining")),
              sso != null && sso.toLowerCase(Locale.ROOT).contains("required"));
        }
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
      return chat(base, key, model, system, history, null);
    }

    public String chat(
        String base,
        String key,
        String model,
        String system,
        JSONArray history,
        RetryListener listener)
        throws Exception {
      if (model.trim().isEmpty()) throw new IOException("Укажи модель в настройках.");
      JSONArray messages = new JSONArray();
      messages.put(AppState.object("role", "system", "content", system));
      int start = Math.max(0, history.length() - 20);
      while (start < history.length()
          && !"user".equals(history.getJSONObject(start).optString("role"))) start++;
      for (int i = start; i < history.length(); i++) messages.put(history.getJSONObject(i));
      JSONObject body = AppState.object("model", model, "stream", false, "messages", messages);
      Response r;
      int retries = 0;
      while (true) {
        check();
        r = request(base + "/chat/completions", key, "POST", body, false);
        if (r.code != 429) break;
        long requested = retryAfterMillis(r.retryAfter, System.currentTimeMillis());
        if (retries >= MAX_RETRIES || requested > MAX_WAIT_MS) {
          String delay =
              requested > MAX_WAIT_MS && requested != Long.MAX_VALUE
                  ? " Сервер просит подождать не меньше "
                      + (requested / 1000 + (requested % 1000 == 0 ? 0 : 1))
                      + " сек."
                  : "";
          throw new IOException(
              "API модели: HTTP 429. Достигнут лимит запросов."
                  + delay
                  + " Подожди и нажми «Продолжить» или выбери другую модель. Проверь лимиты"
                  + " провайдера.");
        }
        long delay = requested >= 0 ? Math.max(1000, requested) : (5000L << retries);
        retries++;
        if (listener != null) listener.waiting(delay, retries);
        check();
        waitStrategy.waitFor(delay);
        check();
        if (listener != null) listener.retrying();
      }
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
      if (r.code == 429)
        throw new IOException(
            "Список моделей: HTTP 429. Лимит запросов. Подожди и попробуй снова.");
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
