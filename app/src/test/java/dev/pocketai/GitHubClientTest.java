package dev.pocketai;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GitHubClientTest {
  MockWebServer server;
  GitHubClient client;
  List<String> requests;
  String currentSha = "sha-1", content = "old";
  int dispatches;

  @Before
  public void start() throws Exception {
    requests = new ArrayList<>();
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest e) {
            try {
              String path = e.getRequestUrl().encodedPath(), method = e.getMethod();
              requests.add(method + " " + e.getPath());
              assertEquals("Bearer synthetic-test-token", e.getHeader("Authorization"));
              String response = "{}";
              int status = 200;
              if (path.equals("/user")) response = "{\"login\":\"synthetic\"}";
              else if (path.contains("/branches/")) response = "{}";
              else if (path.contains("/contents/")) {
                if (method.equals("GET"))
                  response =
                      new JSONObject()
                          .put("type", "file")
                          .put("encoding", "base64")
                          .put("sha", currentSha)
                          .put(
                              "content",
                              Base64.getEncoder()
                                  .encodeToString(content.getBytes(StandardCharsets.UTF_8)))
                          .toString();
                else {
                  JSONObject body = new JSONObject(e.getBody().readUtf8());
                  assertEquals("feature/tabs", body.getString("branch"));
                  assertEquals(currentSha, body.getString("sha"));
                  content =
                      new String(
                          Base64.getDecoder().decode(body.getString("content")),
                          StandardCharsets.UTF_8);
                  response =
                      "{\"commit\":{\"html_url\":\"https://github.com/example/demo/commit/test\"}}";
                }
              } else if (path.endsWith("/dispatches")) {
                assertEquals("POST", method);
                JSONObject body = new JSONObject(e.getBody().readUtf8());
                assertEquals("feature/tabs", body.getString("ref"));
                dispatches++;
                status = 204;
              } else if (path.endsWith("/runs")) {
                assertTrue(e.getRequestUrl().encodedQuery().contains("branch=feature%2Ftabs"));
                response =
                    "{\"workflow_runs\":[{\"id\":12,\"status\":\"completed\",\"conclusion\":\"failure\",\"head_sha\":\"abc\"}]}";
              }
              return new MockResponse()
                  .setResponseCode(status)
                  .setBody(status == 204 ? "" : response);
            } catch (Exception failure) {
              throw new RuntimeException(failure);
            }
          }
        });
    server.start();
    client =
        new GitHubClient(
            new ChatClient.Call(),
            server.url("/").toString().replaceAll("/+$", ""),
            "synthetic-test-token",
            "example/demo",
            "feature/tabs");
  }

  @After
  public void stop() throws Exception {
    server.shutdown();
  }

  @Test
  public void readsWritesWithVersionAndBranch() throws Exception {
    assertEquals("synthetic", client.connect());
    String sha =
        new JSONObject(client.contents("github_read", "src/a b.txt", "", "")).getString("sha");
    client.contents("github_write", "src/a b.txt", "новое", sha);
    assertEquals("новое", content);
    assertTrue(requests.stream().anyMatch(r -> r.contains("a%20b.txt?ref=feature%2Ftabs")));
  }

  @Test
  public void staleVersionNeverWrites() throws Exception {
    try {
      client.contents("github_write", "a.txt", "bad", "old-sha");
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Версия"));
    }
    assertFalse(requests.stream().anyMatch(r -> r.startsWith("PUT")));
  }

  @Test
  public void dispatchAndFailureStatusStayDistinct() throws Exception {
    String result = client.actions("github_build", "android.yml", new JSONObject());
    assertEquals(1, dispatches);
    assertTrue(result.contains("ещё не результат"));
    JSONArray runs = new JSONArray(client.actions("github_runs", "android.yml", new JSONObject()));
    assertEquals("failure", runs.getJSONObject(0).getString("conclusion"));
    assertEquals(12, runs.getJSONObject(0).getLong("id"));
  }
}
