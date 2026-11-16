package dev.pocketai;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  private static final int BG = 0xff10141e,
      CARD = 0xff1a2030,
      TEXT = 0xffeef3fc,
      MUTED = 0xffa1b0c6,
      GREEN = 0xff91e4bd,
      BORDER = 0xff303a4d;
  private AppState state;
  private LinearLayout root, nav, pane;
  private EditText composer;
  private String apiKey = "", status = "", busyChat = "", exportText;
  private int page;
  private boolean busy;
  private ChatClient.Call current;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private Future<?> deadline;
  private final Map<JSONObject, Boolean> expandedWork = new WeakHashMap<>();
  private final Map<String, Integer> chatScrollPositions = new HashMap<>();
  private final Map<String, Boolean> chatFollowsEnd = new HashMap<>();
  private ScrollView chatScroll;

  private SharedPreferences access() {
    return getSharedPreferences("agent", 0);
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    state = new AppState(this);
    try {
      apiKey = SecretStore.load(this);
    } catch (Exception e) {
      status = "Введи API-ключ заново в подключении.";
    }
    root = column();
    root.setBackgroundColor(BG);
    root.setPadding(dp(16), dp(12), dp(16), dp(8));
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(
                dp(16) + bars.left,
                dp(12) + bars.top,
                dp(16) + bars.right,
                dp(8) + Math.max(bars.bottom, ime.bottom));
            return insets;
          });
    }
    root.addView(text("✦  Pocket AI", 24, GREEN, true));
    nav = row();
    add(root, nav, 12);
    pane = column();
    root.addView(pane, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    show(0);
  }

  private void capture() {
    if (composer != null) state.draft = composer.getText().toString();
    if (chatScroll != null) {
      chatScrollPositions.put(state.activeId, chatScroll.getScrollY());
      chatFollowsEnd.put(state.activeId, !chatScroll.canScrollVertically(1));
    }
  }

  private void show(int selected) {
    capture();
    composer = null;
    chatScroll = null;
    page = selected;
    nav.removeAllViews();
    pane.removeAllViews();
    String[] labels = {"Чаты", "Промпты", "Подключение"};
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      Button b = button(labels[i], selected == i);
      nav.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
      b.setOnClickListener(v -> show(index));
    }
    if (selected == 0) chat();
    else if (selected == 1) prompts();
    else settings();
  }

  private void chat() {
    HorizontalScrollView tabs = new HorizontalScrollView(this);
    LinearLayout strip = row();
    tabs.addView(strip);
    tabs.setHorizontalScrollBarEnabled(false);
    for (int i = 0; i < state.chats.length(); i++) {
      JSONObject c = state.chats.optJSONObject(i);
      String id = c.optString("id");
      Button b = button(c.optString("name"), id.equals(state.activeId));
      b.setContentDescription("Чат: " + c.optString("name"));
      strip.addView(b);
      b.setOnClickListener(
          v -> {
            capture();
            composer = null;
            chatScroll = null;
            state.select(id);
            status = "";
            show(0);
          });
    }
    Button plus = button("+", false);
    plus.setContentDescription("Новый чат");
    strip.addView(plus);
    plus.setOnClickListener(
        v -> {
          capture();
          composer = null;
          chatScroll = null;
          state.create();
          status = "";
          show(0);
        });
    add(pane, tabs, 10);
    LinearLayout actions = row();
    Button rename = button("Имя", false);
    actions.addView(rename);
    rename.setOnClickListener(v -> renameChat());
    Button remove = button("Удалить", false);
    actions.addView(remove);
    remove.setOnClickListener(
        v -> {
          if (busy && busyChat.equals(state.activeId)) {
            toast("Сначала останови задачу этого чата.");
            return;
          }
          new AlertDialog.Builder(this)
              .setTitle("Удалить этот чат?")
              .setMessage("Можно сначала сохранить переписку через «Экспорт».")
              .setNegativeButton("Отмена", null)
              .setPositiveButton(
                  "Удалить",
                  (d, w) -> {
                    composer = null;
                    chatScroll = null;
                    state.deleteActive();
                    show(0);
                  })
              .show();
        });
    Button export = button("Экспорт", false);
    actions.addView(export);
    export.setOnClickListener(v -> exportChat());
    add(pane, actions, 4);
    ScrollView scroll = new ScrollView(this);
    chatScroll = scroll;
    final String displayedChat = state.activeId;
    LinearLayout messages = column();
    scroll.addView(messages);
    pane.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (state.history.length() == 0) {
      LinearLayout welcome = card();
      welcome.addView(text("Что создадим?", 24, TEXT, true));
      add(
          welcome,
          text(
              "Подключи модель, выбери папку или репозиторий GitHub и опиши задачу. Включи"
                  + " авторежим, чтобы ИИ сам выполнял действия.",
              15,
              MUTED,
              false),
          12);
      add(messages, welcome, 12);
    }
    for (int i = 0; i < state.history.length(); ) {
      JSONObject message = state.history.optJSONObject(i);
      JSONObject action = actionOf(message);
      if (action == null) {
        if (message != null) add(messages, messageBubble(message, i), 10);
        i++;
        continue;
      }
      int end = workEnd(i);
      add(messages, workGroup(i, end), 10);
      i = end;
    }
    if (busy)
      add(
          messages,
          text(
              "● Задача выполняется" + (busyChat.equals(state.activeId) ? "" : " в другом чате"),
              13,
              GREEN,
              false),
          10);
    if (!status.isEmpty()) add(messages, text(status, 13, MUTED, false), 8);
    LinearLayout tools = row();
    Button system = button("Инструкция ИИ", false);
    tools.addView(system);
    system.setOnClickListener(v -> editSystem());
    Button permissions =
        button(access().getBoolean("auto", false) ? "Авто: вкл" : "Доступ и GitHub", false);
    tools.addView(permissions);
    permissions.setOnClickListener(v -> accessSettings());
    if (!busy && state.history.length() > 0) {
      Button retry = button("Продолжить", false);
      tools.addView(retry);
      retry.setOnClickListener(v -> send(true));
    }
    HorizontalScrollView toolScroll = new HorizontalScrollView(this);
    toolScroll.addView(tools);
    add(pane, toolScroll, 8);
    LinearLayout input = row();
    composer = field("Напиши задачу…", state.draft, false);
    composer.setMaxLines(5);
    composer.setInputType(147457);
    composer.setFilters(new InputFilter[] {new InputFilter.LengthFilter(32000)});
    input.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
    Button send = button(busy ? "Стоп" : "↑", true);
    send.setContentDescription(busy ? "Остановить задачу" : "Отправить сообщение");
    input.addView(send);
    send.setOnClickListener(
        v -> {
          if (busy) {
            current.cancel();
            status = "Останавливаем… Уже отправленный коммит или запуск сборки может завершиться.";
            show(0);
          } else send(false);
        });
    add(pane, input, 8);
    boolean followEnd = chatFollowsEnd.getOrDefault(displayedChat, true);
    int previousScroll = chatScrollPositions.getOrDefault(displayedChat, 0);
    scroll.post(
        () -> {
          if (followEnd) scroll.fullScroll(View.FOCUS_DOWN);
          else scroll.scrollTo(0, previousScroll);
        });
  }

  private JSONObject actionOf(JSONObject message) {
    return message != null && "assistant".equals(message.optString("role"))
        ? AgentFiles.action(message.optString("content"))
        : null;
  }

  /** Consume adjacent assistant action/result pairs without hiding ordinary user messages. */
  private int workEnd(int start) {
    int index = start;
    while (index < state.history.length()) {
      JSONObject action = actionOf(state.history.optJSONObject(index));
      if (action == null) break;
      index++;
      JSONObject result = state.history.optJSONObject(index);
      String prefix = AgentRunner.result(action, "");
      if (result == null
          || !"user".equals(result.optString("role"))
          || !result.optString("content").startsWith(prefix)) break;
      index++;
    }
    return index;
  }

  private LinearLayout workGroup(int start, int end) {
    JSONObject anchor = state.history.optJSONObject(start);
    boolean pendingAction =
        end == state.history.length()
            && actionOf(state.history.optJSONObject(end - 1)) != null
            && !busy;
    boolean expanded = expandedWork.getOrDefault(anchor, pendingAction);
    int count = 0;
    boolean hasError = false;
    JSONObject lastAction = null;
    for (int i = start; i < end; i++) {
      JSONObject message = state.history.optJSONObject(i);
      JSONObject nextAction = actionOf(message);
      if (nextAction != null) {
        count++;
        lastAction = nextAction;
      } else if (message != null
          && lastAction != null
          && "user".equals(message.optString("role"))) {
        String value = message.optString("content");
        if (value.startsWith(AgentRunner.result(lastAction, "Ошибка действия."))) hasError = true;
      }
    }
    LinearLayout group = card();
    final String title = "Ход работы · действий: " + count + (hasError ? " · ошибка" : "");
    Button toggle = button((expanded ? "▾ " : "▸ ") + title, false);
    toggle.setContentDescription(title + (expanded ? ": свернуть" : ": развернуть"));
    group.addView(toggle);
    if (lastAction != null) {
      String path = lastAction.optString("path");
      add(
          group,
          text(
              actionLabel(lastAction.optString("op")) + (path.isEmpty() ? "" : ": " + path),
              12,
              MUTED,
              false),
          6);
    }
    LinearLayout details = column();
    group.addView(details);
    if (expanded) populateWork(details, start, end);
    details.setVisibility(expanded ? View.VISIBLE : View.GONE);
    toggle.setOnClickListener(
        v -> {
          boolean opening = details.getVisibility() != View.VISIBLE;
          if (opening && details.getChildCount() == 0) populateWork(details, start, end);
          details.setVisibility(opening ? View.VISIBLE : View.GONE);
          expandedWork.put(anchor, opening);
          toggle.setText((opening ? "▾ " : "▸ ") + title);
          toggle.setContentDescription(title + (opening ? ": свернуть" : ": развернуть"));
        });
    return group;
  }

  private String actionLabel(String op) {
    switch (op) {
      case "local_list":
      case "github_list":
        return "Просмотр файлов";
      case "local_read":
      case "github_read":
        return "Чтение файла";
      case "local_write":
      case "github_write":
        return "Запись файла";
      case "local_mkdir":
        return "Создание папки";
      case "github_build":
        return "Запуск сборки";
      case "github_runs":
      case "github_jobs":
        return "Проверка сборки";
      default:
        return "Действие";
    }
  }

  private void populateWork(LinearLayout details, int start, int end) {
    for (int i = start; i < end; i++) {
      JSONObject message = state.history.optJSONObject(i);
      if (message != null) add(details, messageBubble(message, i), 8);
    }
  }

  private LinearLayout messageBubble(JSONObject message, int index) {
    String value = message.optString("content");
    boolean assistant = "assistant".equals(message.optString("role"));
    LinearLayout bubble = card();
    bubble.addView(
        text(
            assistant ? "✦ МОДЕЛЬ" : value.startsWith("Результат действия") ? "ДЕЙСТВИЕ" : "ТЫ",
            11,
            GREEN,
            true));
    TextView body = text(value, 15, TEXT, false);
    body.setTextIsSelectable(true);
    android.text.util.Linkify.addLinks(body, android.text.util.Linkify.WEB_URLS);
    add(bubble, body, 8);
    Button copy = button("Копировать", false);
    copy.setOnClickListener(
        v -> {
          ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
              .setPrimaryClip(ClipData.newPlainText("Pocket AI", value));
          toast("Скопировано");
        });
    add(bubble, copy, 8);
    JSONObject action =
        assistant && index == state.history.length() - 1 ? AgentFiles.action(value) : null;
    if (action != null && !busy) {
      Button execute = button("Выполнить: " + action.optString("op"), true);
      add(bubble, execute, 6);
      execute.setOnClickListener(v -> confirm(action));
    }
    return bubble;
  }

  private void renameChat() {
    final String id = state.activeId;
    EditText name = field("Название", state.find(id).optString("name"), true);
    name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(60)});
    new AlertDialog.Builder(this)
        .setTitle("Название чата")
        .setView(name)
        .setNegativeButton("Отмена", null)
        .setPositiveButton(
            "Сохранить",
            (d, w) -> {
              String n = name.getText().toString().trim();
              if (!n.isEmpty() && id.equals(state.activeId)) {
                state.rename(n);
                show(0);
              }
            })
        .show();
  }

  private boolean idle() {
    if (busy) {
      toast("Сначала дождись завершения или нажми Стоп в чате.");
      return false;
    }
    return true;
  }

  interface Work {
    String run(ChatClient.Call call) throws Exception;
  }

  interface Done {
    void accept(String value);
  }

  private void launch(Work work, Done done) {
    if (!idle()) return;
    busy = true;
    ChatClient.Call call = new ChatClient.Call();
    current = call;
    deadline = timer.schedule(call::cancel, 10, TimeUnit.MINUTES);
    worker.execute(
        () -> {
          String result;
          try {
            result = work.run(call);
          } catch (Exception e) {
            result = "Ошибка: " + friendly(e);
          }
          final String value = result;
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                busy = false;
                current = null;
                busyChat = "";
                if (deadline != null) deadline.cancel(false);
                done.accept(value);
              });
        });
  }

  private String friendly(Exception e) {
    return e instanceof IOException && e.getMessage() != null
        ? e.getMessage()
        : "Проверь соединение, формат ответа API и настройки доступа.";
  }

  private void publish(String id, String role, String value) {
    runOnUiThread(
        () -> {
          if (isDestroyed()) return;
          capture();
          state.message(id, role, value);
          if (page == 0) show(0);
        });
  }

  private void send(boolean resume) {
    if (!idle()) return;
    capture();
    final String id = state.activeId;
    final String base;
    try {
      base = ChatClient.baseUrl(state.endpoint, state.localHttp);
      if (state.model.isEmpty()) throw new IOException("Укажи модель в подключении.");
    } catch (Exception e) {
      toast(friendly(e));
      show(2);
      return;
    }
    String value =
        resume
            ? "Продолжи задачу с учётом последних результатов. Сначала проверь состояние предыдущих"
                + " изменений."
            : state.draft.trim();
    if (value.isEmpty()) return;
    state.message(id, "user", value);
    state.draft = "";
    composer.setText("");
    state.save();
    final JSONArray history = AppState.array(state.history.toString());
    final String model = state.model, key = apiKey;
    final AgentFiles files = new AgentFiles(this, new ChatClient.Call());
    final String system = state.system + files.instructions();
    final boolean auto = access().getBoolean("auto", false);
    busyChat = id;
    status = "";
    launch(
        call -> {
          AgentFiles agent = new AgentFiles(this, call);
          return AgentRunner.run(
              history,
              auto,
              messages -> call.chat(base, key, model, system, messages),
              agent::execute,
              (role, text) -> publish(id, role, text),
              call::check);
        },
        result -> {
          status = result;
          if (page == 0) show(0);
          else toast(result);
        });
    show(0);
  }

  private static String toolResult(JSONObject action, String result) {
    return AgentRunner.result(action, result);
  }

  private void confirm(JSONObject action) {
    if (!idle()) return;
    String id = state.activeId;
    boolean write =
        action.optString("op").endsWith("write") || action.optString("op").equals("github_build");
    TextView preview = text(action.toString(), 13, TEXT, false);
    preview.setTextIsSelectable(true);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(preview);
    new AlertDialog.Builder(this)
        .setTitle(write ? "Выполнить изменение?" : "Выполнить действие?")
        .setView(scroll)
        .setNegativeButton("Отмена", null)
        .setPositiveButton(
            "Выполнить",
            (d, w) -> {
              busyChat = id;
              launch(
                  call -> {
                    String result = new AgentFiles(this, call).execute(action);
                    publish(id, "user", toolResult(action, result));
                    return "Готово. Нажми «Продолжить», чтобы передать результат модели.";
                  },
                  result -> {
                    status = result;
                    show(0);
                  });
              show(0);
            })
        .show();
  }

  private void settings() {
    LinearLayout box = scrollPane();
    box.addView(text("Подключение модели", 22, TEXT, true));
    EditText endpoint = field("Адрес API (…/v1)", state.endpoint, true),
        model = field("ID модели", state.model, true),
        key = secret("API-ключ", apiKey);
    add(box, endpoint, 12);
    add(box, model, 10);
    add(box, key, 10);
    Switch local = toggle("HTTP для локального сервера", state.localHttp);
    add(box, local, 10);
    Switch visible = toggle("Показать API-ключ", false);
    add(box, visible, 4);
    visible.setOnCheckedChangeListener((b, on) -> key.setInputType(on ? 145 : 129));
    Button preset = button("Настроить OpenRouter", false);
    add(box, preset, 10);
    preset.setOnClickListener(
        v -> {
          if (!endpoint.getText().toString().startsWith("https://openrouter.ai/")) key.setText("");
          endpoint.setText("https://openrouter.ai/api/v1");
          model.setText("openrouter/free");
          local.setChecked(false);
        });
    Button keys = button("Открыть страницу API-ключей ↗", false);
    add(box, keys, 8);
    keys.setOnClickListener(v -> open("https://openrouter.ai/settings/keys"));
    Button save = button("Сохранить подключение", true);
    add(box, save, 14);
    save.setOnClickListener(
        v -> {
          if (!idle()) return;
          try {
            String base = ChatClient.baseUrl(endpoint.getText().toString(), local.isChecked());
            String m = model.getText().toString().trim();
            if (m.isEmpty()) {
              model.setError("Нужно ID модели");
              return;
            }
            String token = key.getText().toString().trim();
            if (token.contains("\n") || token.contains("\r"))
              throw new IOException("Ключ должен быть в одну строку.");
            SecretStore.save(this, token);
            apiKey = token;
            state.endpoint = base;
            state.model = m;
            state.localHttp = local.isChecked();
            state.save();
            status = "Подключение сохранено.";
            show(0);
          } catch (Exception e) {
            toast(friendly(e));
          }
        });
    Switch free = toggle("Только бесплатные модели OpenRouter", true);
    add(box, free, 12);
    Button models = button("Загрузить список моделей", false);
    add(box, models, 8);
    models.setOnClickListener(
        v -> {
          if (!idle()) return;
          try {
            String base = ChatClient.baseUrl(endpoint.getText().toString(), local.isChecked()),
                token = key.getText().toString();
            boolean onlyFree = free.isChecked() && base.startsWith("https://openrouter.ai/");
            launch(
                call -> {
                  List<String> ids = call.models(base, token);
                  if (onlyFree)
                    ids.removeIf(s -> !s.endsWith(":free") && !s.equals("openrouter/free"));
                  return new JSONArray(ids).toString();
                },
                result -> {
                  if (result.startsWith("Ошибка:")) {
                    toast(result);
                    return;
                  }
                  JSONArray ids = AppState.array(result);
                  String[] names = new String[ids.length()];
                  for (int i = 0; i < names.length; i++) names[i] = ids.optString(i);
                  new AlertDialog.Builder(this)
                      .setTitle("Выбери модель")
                      .setItems(names, (d, w) -> model.setText(names[w]))
                      .setNegativeButton("Закрыть", null)
                      .show();
                });
          } catch (Exception e) {
            toast(friendly(e));
          }
        });
    Button access = button("GitHub, файлы и авторежим →", true);
    add(box, access, 18);
    access.setOnClickListener(v -> accessSettings());
    add(
        box,
        text(
            "Поддерживается Chat Completions API. Для Ollama укажи http://локальный-IP:11434/v1 и"
                + " включи локальный HTTP. Ключ можно оставить пустым. Модель не встроена в APK. В"
                + " API отправляются инструкция и до 20 последних сообщений текущего чата, включая"
                + " прочитанные файлы.",
            13,
            MUTED,
            false),
        18);
  }

  private void accessSettings() {
    if (!idle()) return;
    LinearLayout box = column();
    box.setPadding(dp(16), dp(12), dp(16), dp(12));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(box);
    Switch local = toggle("Доступ к выбранной папке телефона", access().getBoolean("local", false));
    box.addView(local);
    Switch github = toggle("Доступ к GitHub", access().getBoolean("github", false));
    box.addView(github);
    Switch auto = toggle("Выполнять действия ИИ автоматически", access().getBoolean("auto", false));
    box.addView(auto);
    add(
        box,
        text(
            "Авторежим сам читает, создаёт и перезаписывает файлы в выбранной папке и репозитории,"
                + " создаёт коммиты и запускает сборки. До 24 действий за запрос. Стоп находится в"
                + " чате. Пока задача выполняется, держи приложение открытым.",
            13,
            MUTED,
            false),
        8);
    Button folder = button("Выбрать папку телефона", false);
    add(box, folder, 12);
    folder.setOnClickListener(
        v -> {
          Intent intent =
              new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                  .addFlags(
                      Intent.FLAG_GRANT_READ_URI_PERMISSION
                          | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                          | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
          try {
            startActivityForResult(intent, 11);
          } catch (ActivityNotFoundException e) {
            toast("Нет системного выбора папки.");
          }
        });
    add(box, text("Папка: " + access().getString("tree", "не выбрана"), 11, MUTED, false), 6);
    EditText repo = field("Репозиторий: owner/repo", access().getString("repo", ""), true),
        branch = field("Ветка", access().getString("branch", "main"), true),
        workflow = field("Workflow: android.yml или ID", access().getString("workflow", ""), true);
    EditText token = secret("GitHub personal access token", "");
    try {
      token.setText(SecretStore.load(this, "github"));
    } catch (Exception e) {
      toast("Вставь GitHub-токен заново.");
    }
    add(box, repo, 14);
    add(box, branch, 10);
    add(box, token, 10);
    add(box, workflow, 10);
    TextView connection =
        text(
            access().getString("login", "").isEmpty()
                ? "GitHub ещё не проверен"
                : "Последняя проверка: @" + access().getString("login", ""),
            13,
            GREEN,
            false);
    add(box, connection, 12);
    Button createToken = button("Создать токен в GitHub ↗", false);
    add(box, createToken, 8);
    createToken.setOnClickListener(
        v -> open("https://github.com/settings/personal-access-tokens/new"));
    add(
        box,
        text(
            "Выбери нужный репозиторий. Права: Contents — Read and write; Actions — Read and write."
                + " Для изменения .github/workflows также Workflows — Read and write. Токен"
                + " шифруется Android Keystore и не передаётся ИИ. Workflow для сборки должен"
                + " поддерживать workflow_dispatch и быть в основной ветке.",
            12,
            MUTED,
            false),
        8);
    Button connect = button("Проверить GitHub и загрузить workflows", true);
    add(box, connect, 12);
    connect.setOnClickListener(
        v -> {
          if (!idle()) return;
          String r = repo.getText().toString().trim(),
              b = branch.getText().toString().trim(),
              t = token.getText().toString().trim();
          connection.setText("Проверяем…");
          launch(
              call -> {
                GitHubClient gh = new GitHubClient(call, t, r, b);
                String login = gh.connect();
                JSONArray workflows = gh.workflows();
                return AppState.object("login", login, "workflows", workflows).toString();
              },
              result -> {
                if (result.startsWith("Ошибка:")) {
                  connection.setText(result);
                  return;
                }
                try {
                  JSONObject data = new JSONObject(result);
                  connection.setText(
                      "Подключение проверено: @"
                          + data.getString("login")
                          + ". Нажми «Сохранить».");
                  JSONArray workflows = data.getJSONArray("workflows");
                  String[] names = new String[workflows.length()];
                  for (int i = 0; i < names.length; i++)
                    names[i] = workflows.getJSONObject(i).getString("name");
                  if (names.length == 0) {
                    toast("В репозитории нет workflows. Добавь файл сборки в .github/workflows.");
                    return;
                  }
                  new AlertDialog.Builder(this)
                      .setTitle("Workflow для сборки")
                      .setItems(
                          names,
                          (d, w) -> {
                            try {
                              workflow.setText(workflows.getJSONObject(w).getString("id"));
                            } catch (JSONException e) {
                              toast("Не удалось выбрать workflow.");
                            }
                          })
                      .setNegativeButton("Закрыть", null)
                      .show();
                } catch (JSONException e) {
                  connection.setText("Неверный ответ GitHub.");
                }
              });
        });
    Button builds = button("Открыть сборки и скачать APK ↗", false);
    add(box, builds, 8);
    builds.setOnClickListener(
        v -> {
          String r = repo.getText().toString().trim();
          if (r.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            open("https://github.com/" + r + "/actions");
          else repo.setError("Нужно owner/repo");
        });
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Файлы и GitHub")
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .setPositiveButton("Сохранить", null)
            .create();
    dialog.show();
    dialog
        .getButton(-1)
        .setOnClickListener(
            v -> {
              if (!idle()) return;
              String r = repo.getText().toString().trim(),
                  b = branch.getText().toString().trim(),
                  t = token.getText().toString().trim();
              if ((github.isChecked() || !r.isEmpty())
                  && (!r.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || b.isEmpty())) {
                repo.setError("Нужно owner/repo и ветка");
                return;
              }
              if (t.contains("\n") || t.contains("\r")) {
                token.setError("Токен должен быть в одну строку");
                return;
              }
              if (github.isChecked() && t.isEmpty()) {
                token.setError("Вставь GitHub-токен");
                return;
              }
              try {
                SecretStore.save(this, "github", t);
                access()
                    .edit()
                    .putString("repo", r)
                    .putString("branch", b)
                    .putString("workflow", workflow.getText().toString().trim())
                    .putBoolean("local", local.isChecked())
                    .putBoolean("github", github.isChecked())
                    .putBoolean("auto", auto.isChecked())
                    .apply();
                dialog.dismiss();
                status = "Настройки доступа сохранены.";
                show(page);
              } catch (Exception e) {
                toast("Не удалось сохранить токен.");
              }
            });
  }

  private void prompts() {
    LinearLayout box = scrollPane();
    box.addView(text("Библиотека промптов", 22, TEXT, true));
    Button create = button("+ Создать промпт", true);
    add(box, create, 12);
    create.setOnClickListener(v -> editPrompt(-1));
    for (int i = 0; i < state.prompts.length(); i++) {
      final int index = i;
      JSONObject p = state.prompts.optJSONObject(i);
      LinearLayout card = card();
      card.addView(text(p.optString("name"), 18, TEXT, true));
      TextView preview = text(p.optString("text"), 13, MUTED, false);
      preview.setMaxLines(4);
      add(card, preview, 8);
      Button use = button("Применить к этому чату", true);
      add(card, use, 10);
      use.setOnClickListener(
          v -> {
            if (!idle()) return;
            state.system = p.optString("text");
            state.save();
            status = "Инструкция применена к текущему чату.";
            show(0);
          });
      Button edit = button("Изменить", false);
      add(card, edit, 6);
      edit.setOnClickListener(v -> editPrompt(index));
      add(box, card, 14);
    }
  }

  private void editSystem() {
    if (!idle()) return;
    EditText field = field("Инструкция", state.system, false);
    field.setMinLines(5);
    field.setMaxLines(10);
    new AlertDialog.Builder(this)
        .setTitle("Инструкция текущего чата")
        .setView(field)
        .setNegativeButton("Отмена", null)
        .setPositiveButton(
            "Сохранить",
            (d, w) -> {
              state.system = field.getText().toString();
              state.save();
            })
        .show();
  }

  private void editPrompt(int index) {
    JSONObject p = index < 0 ? new JSONObject() : state.prompts.optJSONObject(index);
    LinearLayout box = card();
    EditText name = field("Название", p.optString("name"), true),
        value = field("Инструкция", p.optString("text"), false);
    value.setMinLines(5);
    value.setMaxLines(10);
    box.addView(name);
    add(box, value, 10);
    AlertDialog.Builder builder =
        new AlertDialog.Builder(this)
            .setTitle("Промпт")
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null);
    if (index >= 0)
      builder.setNeutralButton(
          "Удалить",
          (d, w) -> {
            state.prompts.remove(index);
            state.save();
            show(1);
          });
    AlertDialog dialog = builder.create();
    dialog.show();
    dialog
        .getButton(-1)
        .setOnClickListener(
            v -> {
              if (name.getText().toString().trim().isEmpty()
                  || value.getText().toString().trim().isEmpty()) {
                toast("Заполни название и инструкцию.");
                return;
              }
              if (index < 0 && state.prompts.length() >= 50) {
                toast("Можно хранить до 50 промптов.");
                return;
              }
              try {
                JSONObject item =
                    AppState.object(
                        "name",
                        name.getText().toString().trim(),
                        "text",
                        value.getText().toString());
                if (index < 0) state.prompts.put(item);
                else state.prompts.put(index, item);
                state.save();
                dialog.dismiss();
                show(1);
              } catch (JSONException e) {
                toast("Не удалось сохранить.");
              }
            });
  }

  private void exportChat() {
    capture();
    StringBuilder b =
        new StringBuilder(
            "Pocket AI\nМодель: " + state.model + "\nИнструкция: " + state.system + "\n\n");
    for (int i = 0; i < state.history.length(); i++) {
      JSONObject m = state.history.optJSONObject(i);
      b.append(m.optString("role")).append(":\n").append(m.optString("content")).append("\n\n");
    }
    exportText = b.toString();
    try {
      startActivityForResult(
          new Intent(Intent.ACTION_CREATE_DOCUMENT)
              .addCategory(Intent.CATEGORY_OPENABLE)
              .setType("text/plain")
              .putExtra(Intent.EXTRA_TITLE, "pocket-ai-chat.txt"),
          10);
    } catch (ActivityNotFoundException e) {
      toast("Нет приложения для сохранения файла.");
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    if (request == 11) {
      try {
        int flags =
            data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
          getContentResolver()
              .takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
          getContentResolver()
              .takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        access().edit().putString("tree", data.getData().toString()).apply();
        toast("Папка выбрана. Включи доступ и сохрани настройки.");
      } catch (Exception e) {
        toast("Нет доступа к папке.");
      }
    } else if (request == 10) {
      if (exportText == null) {
        toast("Повтори экспорт.");
        return;
      }
      try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
        if (out == null) throw new IOException();
        out.write(exportText.getBytes(StandardCharsets.UTF_8));
        toast("Переписка сохранена.");
        exportText = null;
      } catch (IOException e) {
        toast("Не удалось сохранить файл.");
      }
    }
  }

  @Override
  protected void onPause() {
    capture();
    state.save();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (current != null) current.cancel();
    worker.shutdownNow();
    timer.shutdownNow();
    super.onDestroy();
  }

  private void open(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      toast("Не найден браузер.");
    }
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private LinearLayout column() {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    return box;
  }

  private LinearLayout row() {
    LinearLayout box = new LinearLayout(this);
    box.setGravity(Gravity.CENTER_VERTICAL);
    return box;
  }

  private LinearLayout card() {
    LinearLayout box = column();
    box.setPadding(dp(14), dp(14), dp(14), dp(14));
    box.setBackground(shape(CARD));
    return box;
  }

  private GradientDrawable shape(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(12));
    d.setStroke(dp(1), BORDER);
    return d;
  }

  private TextView text(String s, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    if (bold) t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  private Button button(String s, boolean accent) {
    Button b = new Button(this);
    b.setText(s);
    b.setAllCaps(false);
    b.setTextSize(12);
    b.setTextColor(accent ? BG : TEXT);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(dp(48));
    b.setPadding(dp(10), dp(6), dp(10), dp(6));
    b.setBackground(shape(accent ? GREEN : CARD));
    return b;
  }

  private EditText field(String hint, String value, boolean single) {
    EditText f = new EditText(this);
    f.setHint(hint);
    f.setText(value);
    f.setTextColor(TEXT);
    f.setHintTextColor(MUTED);
    f.setTextSize(14);
    f.setSingleLine(single);
    f.setPadding(dp(12), dp(12), dp(12), dp(12));
    f.setBackground(shape(CARD));
    f.setFilters(new InputFilter[] {new InputFilter.LengthFilter(12000)});
    return f;
  }

  private EditText secret(String hint, String value) {
    EditText f = field(hint, value, true);
    f.setInputType(129);
    f.setSaveEnabled(false);
    f.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    return f;
  }

  private Switch toggle(String title, boolean checked) {
    Switch s = new Switch(this);
    s.setText(title);
    s.setTextColor(TEXT);
    s.setTextSize(12);
    s.setMinHeight(dp(48));
    s.setChecked(checked);
    return s;
  }

  private void add(LinearLayout parent, View child, int margin) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(margin);
    parent.addView(child, p);
  }

  private LinearLayout scrollPane() {
    ScrollView s = new ScrollView(this);
    LinearLayout box = column();
    box.setPadding(0, dp(12), 0, dp(16));
    s.addView(box);
    pane.addView(s, new LinearLayout.LayoutParams(-1, -1));
    return box;
  }
}
