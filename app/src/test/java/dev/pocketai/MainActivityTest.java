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
