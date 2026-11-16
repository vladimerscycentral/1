package dev.pocketai;

import static org.junit.Assert.*;

import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MainActivityTest {
  @Before
  public void resetPreferences() {
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("workspace", 0)
        .edit()
        .clear()
        .commit();
    RuntimeEnvironment.getApplication().getSharedPreferences("agent", 0).edit().clear().commit();
  }

  private static String action(String op, String path) {
    return "```pocket-action\n{\"op\":\"" + op + "\",\"path\":\"" + path + "\"}\n```";
  }

  @Test
  public void groupsActionsAndKeepsExpansionAcrossRefreshAndChatSwitch() throws Exception {
    AppState state = new AppState(RuntimeEnvironment.getApplication());
    String chat = state.activeId;
    String read = action("github_read", "build.gradle");
    String write = action("github_write", "build.gradle");
    state.message(chat, "user", "Собери мод");
    state.message(chat, "assistant", read);
    state.message(chat, "user", AgentRunner.result(AgentFiles.action(read), "Исходный файл"));
    state.message(chat, "assistant", write);
    state.message(chat, "user", AgentRunner.result(AgentFiles.action(write), "Коммит создан"));
    state.message(chat, "assistant", "Готовый ответ");
    state.message(chat, "user", "Обычное сообщение");
    try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity a = controller.get();
      View root = a.getWindow().getDecorView();
      assertNotNull(find(root, "Собери мод"));
      assertNotNull(find(root, "Готовый ответ"));
      assertNotNull(find(root, "Обычное сообщение"));
      assertNull(find(root, read));
      assertNotNull(find(root, "Запись файла: build.gradle"));
      find(root, "▸ Ход работы · действий: 2").performClick();
      assertNotNull(find(root, read));
      assertNotNull(find(root, AgentRunner.result(AgentFiles.action(write), "Коммит создан")));
      find(root, "Чаты").performClick();
      assertNotNull(find(root, "▾ Ход работы · действий: 2"));
      assertNotNull(find(root, read));
      find(root, "+").performClick();
      find(root, "Чат 1").performClick();
      assertNotNull(find(root, "▾ Ход работы · действий: 2"));
      assertNotNull(find(root, read));
      find(root, "▾ Ход работы · действий: 2").performClick();
      assertEquals(View.GONE, ((View) find(root, read).getParent().getParent()).getVisibility());
      assertEquals(7, new AppState(RuntimeEnvironment.getApplication()).history.length());
    }
  }

  @Test
  public void pendingManualActionRemainsVisibleAndExecutable() {
    AppState state = new AppState(RuntimeEnvironment.getApplication());
    state.message(state.activeId, "user", "Измени файл");
    state.message(state.activeId, "assistant", action("github_read", "a.txt"));
    try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
      View root = controller.get().getWindow().getDecorView();
      assertNotNull(find(root, "▾ Ход работы · действий: 1"));
      assertNotNull(find(root, "Выполнить: github_read"));
    }
  }

  @Test
  public void failedActionIsMarkedEvenWhenCollapsed() {
    AppState state = new AppState(RuntimeEnvironment.getApplication());
    String action = action("github_read", "a.txt");
    state.message(state.activeId, "user", "Прочитай");
    state.message(state.activeId, "assistant", action);
    state.message(
        state.activeId,
        "user",
        AgentRunner.result(AgentFiles.action(action), "Ошибка действия. GitHub HTTP 403"));
    try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
      View root = controller.get().getWindow().getDecorView();
      assertNotNull(find(root, "▸ Ход работы · действий: 1 · ошибка"));
      find(root, "▸ Ход работы · действий: 1 · ошибка").performClick();
      assertNotNull(
          find(
              root,
              AgentRunner.result(AgentFiles.action(action), "Ошибка действия. GitHub HTTP 403")));
    }
  }

  @Test
  public void opensCreatesTabsAndSettings() {
    try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity a = controller.get();
      View root = a.getWindow().getDecorView();
      assertNotNull(find(root, "Чаты"));
      assertNotNull(find(root, "Чат 1"));
      find(root, "+").performClick();
      assertNotNull(find(root, "Чат 2"));
      find(root, "Чат 1").performClick();
      assertNotNull(find(root, "Что создадим?"));
      find(root, "Подключение").performClick();
      assertNotNull(find(root, "GitHub, файлы и авторежим →"));
    }
  }

  private View find(View root, String text) {
    if (root instanceof TextView && ((TextView) root).getText().toString().equals(text))
      return root;
    if (root instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) root;
      for (int i = 0; i < g.getChildCount(); i++) {
        View found = find(g.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }
}
