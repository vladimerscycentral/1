package dev.pocketai;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import org.json.*;

/** Only talks to the configured repository. Tokens never enter model context. */
final class GitHubClient {
  private final ChatClient.Call call;
  private final String base, token, repo, branch;

  GitHubClient(ChatClient.Call call, String token, String repo, String branch) throws IOException {
    this(call, "https://api.github.com", token, repo, branch);
  }

  GitHubClient(ChatClient.Call call, String base, String token, String repo, String branch)
      throws IOException {
    if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || branch.trim().isEmpty())
      throw new IOException("Укажи owner/repo и ветку.");
    this.call = call;
    this.base = base;
    this.token = token;
    this.repo = repo;
    this.branch = branch;
  }

  static String encode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    } catch (java.io.UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  private String route(String path) {
    return "/repos/" + repo + path;
  }

  private ChatClient.Response raw(String method, String path, JSONObject body) throws IOException {
    return call.request(base + path, token, method, body, true);
  }

  private String request(String method, String path, JSONObject body) throws IOException {
    ChatClient.Response r = raw(method, path, body);
    if (r.code < 200 || r.code >= 300) throw failure(r);
    return r.body;
  }

  private IOException failure(ChatClient.Response response) {
    String guidance = "Проверь токен, права, ветку и защиту репозитория.";
    if (response.code == 403) {
      String message = "";
      try {
        message = new JSONObject(response.body).optString("message").toLowerCase(Locale.ROOT);
      } catch (JSONException ignored) {
        /* Keep the generic message for unknown responses. */
      }
      if (response.githubSsoRequired) {
        guidance = "Организация требует авторизовать токен через SSO в настройках GitHub.";
      } else if (response.githubRateLimited || message.contains("rate limit")) {
        guidance = "Превышен лимит запросов GitHub. Подожди и попробуй позже.";
      } else if (message.contains("resource not accessible by personal access token")
          || message.contains("resource not accessible by integration")) {
        guidance =
            "GitHub сообщает, что токену не хватает доступа. Проверь владельца, выбранный"
                + " репозиторий и Contents: Read and write в настройках токена.";
      } else if (message.contains("protected branch")
          || message.contains("repository rule violations")
          || message.contains("changes must be made through a pull request")) {
        guidance =
            "Правила репозитория запрещают прямую запись в эту ветку. Используй ветку,"
                + " в которую разрешено записывать, или обратись к владельцу репозитория.";
      }
    }
    // Never expose raw server messages: they can contain sensitive or untrusted text.
    return new IOException(
        "GitHub HTTP "
            + response.code
            + ". "
            + guidance
            + " Для записи или сборки при сетевом сбое сначала проверь результат.");
  }

  String connect() throws Exception {
    if (token.isEmpty()) throw new IOException("Вставь GitHub-токен.");
    String login = new JSONObject(request("GET", "/user", null)).getString("login");
    request("GET", route("/branches/" + encode(branch)), null);
    return login;
  }

  String contents(String op, String path, String content, String sha) throws Exception {
    String url = route("/contents/" + encode(path).replace("%2F", "/"));
    ChatClient.Response r = raw("GET", url + "?ref=" + encode(branch), null);
    if (op.equals("github_write")) {
      if (path.isEmpty() || token.isEmpty())
        throw new IOException("Для записи нужны путь и токен с Contents: read and write.");
      JSONObject body =
          AppState.object(
              "message",
              "Update " + path + " via Pocket AI",
              "branch",
              branch,
              "content",
              Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
      if (r.code == 200) {
        String actual = new JSONObject(r.body).getString("sha");
        if (!actual.equals(sha))
          throw new IOException(
              "Версия изменилась или sha не указан. Сначала прочитай текущий файл.");
        body.put("sha", actual);
      } else if (r.code != 404) throw failure(r);
      else if (!sha.isEmpty())
        throw new IOException("Файл удалён после чтения. Проверь путь перед созданием.");
      String written = request("PUT", url, body);
      return AppState.object(
              "commit", new JSONObject(written).getJSONObject("commit").getString("html_url"))
          .toString();
    }
    if (r.code == 404
        && !path.isEmpty()
        && (op.equals("github_read") || op.equals("github_list"))) {
      // A 404 may also mean the repository, branch or token is inaccessible. Verify the
      // root listing for the same ref before treating a path as missing.
      ChatClient.Response root = raw("GET", route("/contents/?ref=" + encode(branch)), null);
      if (root.code != 200) {
        throw new IOException(
            "GitHub HTTP 404. Не удалось проверить репозиторий и ветку. Проверь owner/repo,"
                + " ветку и права токена на Contents.");
      }
      try {
        new JSONArray(root.body);
      } catch (JSONException invalidRoot) {
        throw new IOException("GitHub вернул неверный список файлов. Проверь подключение.");
      }
      return AppState.object(
              "found",
              false,
              "path",
              path,
              "message",
              "GitHub не нашёл этот путь в доступной ветке. "
                  + "Если нужен новый файл, создай его через github_write без sha.")
          .toString();
    }
    if (r.code != 200) throw failure(r);
    if (op.equals("github_list")) {
      JSONArray files = new JSONArray(r.body), result = new JSONArray();
      for (int i = 0; i < files.length() && i < 100; i++) {
        JSONObject f = files.getJSONObject(i);
        result.put(AppState.object("path", f.optString("path"), "type", f.optString("type")));
      }
      return AppState.object("entries", result, "truncated", files.length() > 100).toString();
    }
    JSONObject file = new JSONObject(r.body);
    if (!"file".equals(file.optString("type")) || !"base64".equals(file.optString("encoding")))
      throw new IOException("Нужен обычный текстовый файл.");
    byte[] bytes = Base64.getMimeDecoder().decode(file.getString("content"));
    String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    if (value.length() > 24000 || value.indexOf(0) >= 0)
      throw new IOException("Файл слишком большой или двоичный.");
    return AppState.object("content", value, "sha", file.getString("sha")).toString();
  }

  JSONArray workflows() throws Exception {
    return new JSONObject(request("GET", route("/actions/workflows?per_page=100"), null))
        .getJSONArray("workflows");
  }

  String actions(String op, String workflow, JSONObject action) throws Exception {
    if (workflow.isEmpty()) throw new IOException("Выбери workflow в настройках GitHub.");
    String workflowPath = route("/actions/workflows/" + encode(workflow));
    if (op.equals("github_build")) {
      JSONObject body = AppState.object("ref", branch);
      if (action.has("inputs")) body.put("inputs", action.getJSONObject("inputs"));
      request("POST", workflowPath + "/dispatches", body);
      return "Запуск сборки запрошен для ветки "
          + branch
          + ". Это ещё не результат сборки. Проверь github_runs. Workflow должен поддерживать"
          + " workflow_dispatch и присутствовать в основной ветке.";
    }
    if (op.equals("github_runs")) {
      JSONArray
          runs =
              new JSONObject(
                      request(
                          "GET",
                          workflowPath + "/runs?branch=" + encode(branch) + "&per_page=5",
                          null))
                  .getJSONArray("workflow_runs"),
          result = new JSONArray();
      for (int i = 0; i < runs.length(); i++) {
        JSONObject r = runs.getJSONObject(i);
        result.put(
            AppState.object(
                "id",
                r.getLong("id"),
                "status",
                r.optString("status"),
                "conclusion",
                r.optString("conclusion"),
                "url",
                r.optString("html_url"),
                "sha",
                r.optString("head_sha"),
                "event",
                r.optString("event")));
      }
      return result.toString();
    }
    String id = action.optString("run_id");
    if (!id.matches("[0-9]{1,20}")) throw new IOException("Нужен числовой run_id из github_runs.");
    JSONArray
        jobs =
            new JSONObject(
                    request("GET", route("/actions/runs/" + id + "/jobs?per_page=100"), null))
                .getJSONArray("jobs"),
        result = new JSONArray();
    for (int i = 0; i < jobs.length(); i++) {
      JSONObject j = jobs.getJSONObject(i);
      result.put(
          AppState.object(
              "name",
              j.optString("name"),
              "status",
              j.optString("status"),
              "conclusion",
              j.optString("conclusion"),
              "steps",
              j.optJSONArray("steps"),
              "url",
              j.optString("html_url")));
    }
    String text = result.toString();
    return text.length() > 24000 ? text.substring(0, 24000) + "\n(обрезано)" : text;
  }
}
