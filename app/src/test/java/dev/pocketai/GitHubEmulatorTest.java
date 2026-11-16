package dev.pocketai;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Opt-in contract smoke against pinned CodeRabbit emulate v0.0.1. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GitHubEmulatorTest {
  @Test
  public void connectsCreatesAndEdits() throws Exception {
    String base = System.getenv("POCKET_GITHUB_EMULATOR_URL");
    Assume.assumeNotNull(base);
    String token = "pocket_synthetic_test";
    ChatClient.Call call = new ChatClient.Call();
    GitHubClient gh = new GitHubClient(call, base, token, "pocket-test/pocket-fixture", "main");
    assertEquals("pocket-test", gh.connect());
    String file = "fixture-" + System.nanoTime() + ".txt";
    gh.contents("github_write", file, "first", "");
    JSONObject read = new JSONObject(gh.contents("github_read", file, "", ""));
    assertEquals("first", read.getString("content"));
    gh.contents("github_write", file, "second", read.getString("sha"));
    assertEquals(
        "second", new JSONObject(gh.contents("github_read", file, "", "")).getString("content"));
    assertNotNull(gh.workflows());
    // This pinned emulator does not discover workflow files or expose workflow seeds.
    // Dispatch request/response contracts are covered by GitHubClientTest.
  }
}
