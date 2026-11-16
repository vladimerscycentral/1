package dev.pocketai;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AgentRunnerTest {
  private final String action =
      "```pocket-action\n{\"op\":\"github_write\",\"path\":\"a.txt\",\"content\":\"new\"}\n```";

  @Test
  public void executesAndFeedsResultIntoNextTurn() throws Exception {
    AtomicInteger turns = new AtomicInteger(), writes = new AtomicInteger();
    List<String> journal = new ArrayList<>();
    String outcome =
        AgentRunner.run(
            new JSONArray(),
            true,
            h -> {
              if (turns.getAndIncrement() == 0) return action;
              assertTrue(h.getJSONObject(1).getString("content").contains("commit-ok"));
              return "done";
            },
            a -> {
              writes.incrementAndGet();
              return "commit-ok";
            },
            (r, t) -> journal.add(r),
            () -> {});
    assertEquals(1, writes.get());
    assertEquals(Arrays.asList("assistant", "user", "assistant"), journal);
    assertEquals("Ответ получен.", outcome);
  }

  @Test
  public void manualModeDoesNotExecute() throws Exception {
    AgentRunner.run(
        new JSONArray(),
        false,
        h -> action,
        a -> {
          fail("manual execution");
          return "";
        },
        (r, t) -> {},
        () -> {});
  }

  @Test
  public void uncertainWriteIsNotRetried() throws Exception {
    AtomicInteger turns = new AtomicInteger();
    String result =
        AgentRunner.run(
            new JSONArray(),
            true,
            h -> {
              turns.incrementAndGet();
              return action;
            },
            a -> {
              throw new IOException("timeout");
            },
            (r, t) -> {},
            () -> {});
    assertEquals(1, turns.get());
    assertTrue(result.contains("timeout"));
  }

  @Test
  public void boundedAt24Actions() throws Exception {
    AtomicInteger writes = new AtomicInteger();
    String result =
        AgentRunner.run(
            new JSONArray(),
            true,
            h -> action,
            a -> {
              writes.incrementAndGet();
              return "ok";
            },
            (r, t) -> {},
            () -> {});
    assertEquals(24, writes.get());
    assertTrue(result.contains("24"));
  }

  @Test
  public void cancellationAfterModelPreventsWrite() throws Exception {
    AtomicInteger checks = new AtomicInteger();
    try {
      AgentRunner.run(
          new JSONArray(),
          true,
          h -> action,
          a -> {
            fail();
            return "";
          },
          (r, t) -> {},
          () -> {
            if (checks.incrementAndGet() == 2) throw new IOException("stop");
          });
      fail();
    } catch (IOException expected) {
      assertEquals("stop", expected.getMessage());
    }
  }

  @Test
  public void rejectsTraversalAndAmbiguousActions() throws Exception {
    for (String p : Arrays.asList("../key", "/a", "a//b", "a/./b", "a\\b")) {
      try {
        AgentFiles.path(p);
        fail(p);
      } catch (IOException expected) {
      }
    }
    assertNull(AgentFiles.action(action + action));
    assertNotNull(AgentFiles.action(action));
  }

  @Test
  public void endpointRejectsPublicHttpAndEmbeddedCredentials() throws Exception {
    for (String url :
        Arrays.asList(
            "http://example.com/v1",
            "https://user:pass@example.com/v1",
            "https://example.com/v1?q=1")) {
      try {
        ChatClient.baseUrl(url, true);
        fail(url);
      } catch (IOException expected) {
      }
    }
    assertEquals(
        "http://192.168.1.2:11434/v1",
        ChatClient.baseUrl("http://192.168.1.2:11434/v1/chat/completions", true));
  }
}
