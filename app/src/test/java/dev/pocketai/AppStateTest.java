package dev.pocketai;

import static org.junit.Assert.*;

import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AppStateTest {
  private Context context;

  @Before
  public void before() {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("workspace", 0).edit().clear().commit();
  }

  @Test
  public void migratesLegacyAndKeepsChatDraftsSeparate() {
    context
        .getSharedPreferences("workspace", 0)
        .edit()
        .putString("history", "[{\"role\":\"user\",\"content\":\"old\"}]")
        .putString("draft", "draft one")
        .putString("system", "old system")
        .commit();
    AppState s = new AppState(context);
    String first = s.activeId;
    assertEquals("old", s.history.optJSONObject(0).optString("content"));
    assertEquals("old system", s.system);
    s.create();
    String second = s.activeId;
    assertEquals(0, s.history.length());
    assertEquals("", s.draft);
    s.draft = "draft two";
    s.system = "second system";
    s.select(first);
    assertEquals("draft one", s.draft);
    assertEquals("old system", s.system);
    s.message(second, "assistant", "background result");
    assertEquals(1, s.history.length());
    AppState restored = new AppState(context);
    restored.select(second);
    assertEquals("draft two", restored.draft);
    assertEquals("second system", restored.system);
    // A valid turn begins with a user message; background writes never pollute first chat.
    assertEquals(1, restored.find(first).optJSONArray("history").length());
  }

  @Test
  public void deleteLastCreatesUsableChat() {
    AppState s = new AppState(context);
    s.deleteActive();
    s.draft = "new";
    s.save();
    AppState r = new AppState(context);
    assertEquals(1, r.chats.length());
    assertEquals("new", r.draft);
  }

  @Test
  public void appendsToOriginAfterSwitch() {
    AppState s = new AppState(context);
    String first = s.activeId;
    s.message(first, "user", "task");
    s.create();
    s.message(first, "assistant", "done");
    assertEquals(0, s.history.length());
    s.select(first);
    assertEquals("done", s.history.optJSONObject(1).optString("content"));
  }
}
