package dev.pocketai;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ChatClientRetryTest {
  MockWebServer server;
  String base;
  private static final String DONE = "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}";

  @Before
  public void start() throws Exception {
    server = new MockWebServer();
    server.start();
    base = server.url("/v1").toString().replaceAll("/+$", "");
  }

  @After
  public void stop() throws Exception {
    server.shutdown();
  }

  @Test
  public void retriesOnly429AndResendsSameModelRequest() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After", "0"));
    server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After", "2"));
    server.enqueue(new MockResponse().setBody(DONE));
    List<Long> delays = new ArrayList<>();
    List<String> progress = new ArrayList<>();
    ChatClient.Call call = new ChatClient.Call(delays::add);
    String answer =
        call.chat(
            base,
            "synthetic",
            "model",
            "system",
            new JSONArray(),
            new ChatClient.Call.RetryListener() {
              public void waiting(long millis, int attempt) {
                progress.add("wait:" + attempt);
              }

              public void retrying() {
                progress.add("retry");
              }
            });
    assertEquals("done", answer);
    assertEquals(Arrays.asList(1000L, 2000L), delays);
    assertEquals(Arrays.asList("wait:1", "retry", "wait:2", "retry"), progress);
    String one = server.takeRequest().getBody().readUtf8();
    assertEquals(one, server.takeRequest().getBody().readUtf8());
    assertEquals(one, server.takeRequest().getBody().readUtf8());
  }

  @Test
  public void longServerDelayIsRespectedWithoutAutomaticRetry() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After", "120"));
    ChatClient.Call call =
        new ChatClient.Call(
            millis -> {
              fail("must not retry early");
            });
    try {
      call.chat(base, "", "model", "system", new JSONArray());
      fail();
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("120 сек"));
      assertTrue(e.getMessage().contains("429"));
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test
  public void repeated429StopsWithHelpfulMessage() throws Exception {
    for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(429));
    List<Long> delays = new ArrayList<>();
    ChatClient.Call call = new ChatClient.Call(delays::add);
    try {
      call.chat(base, "", "model", "system", new JSONArray());
      fail();
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("лимит"));
      assertTrue(e.getMessage().contains("Продолжить"));
    }
    assertEquals(Arrays.asList(5000L, 10000L), delays);
    assertEquals(3, server.getRequestCount());
  }

  @Test
  public void parseHttpDateAndLargeRetryValues() {
    long now = java.time.Instant.parse("2026-11-16T20:00:00Z").toEpochMilli();
    assertEquals(120000, ChatClient.Call.retryAfterMillis("Mon, 16 Nov 2026 20:02:00 GMT", now));
    assertEquals(
        Long.MAX_VALUE, ChatClient.Call.retryAfterMillis("9999999999999999999999999999999", now));
    assertEquals(-1, ChatClient.Call.retryAfterMillis("invalid", now));
  }

  @Test
  public void cancellationStopsWaitingPromptly() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After", "30"));
    CountDownLatch waiting = new CountDownLatch(1);
    ChatClient.Call call = new ChatClient.Call();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<String> future =
          executor.submit(
              () -> {
                try {
                  call.chat(
                      base,
                      "",
                      "model",
                      "system",
                      new JSONArray(),
                      new ChatClient.Call.RetryListener() {
                        public void waiting(long millis, int attempt) {
                          waiting.countDown();
                        }

                        public void retrying() {}
                      });
                  return "success";
                } catch (IOException e) {
                  return e.getMessage();
                }
              });
      assertTrue(waiting.await(5, TimeUnit.SECONDS));
      call.cancel();
      assertTrue(future.get(2, TimeUnit.SECONDS).contains("Остановлено"));
      assertEquals(1, server.getRequestCount());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void agentDoesNotRepeatWriteWhenModelIsRateLimited() throws Exception {
    String operation =
        "```pocket-action\n"
            + new JSONObject()
                .put("op", "github_write")
                .put("path", "a.txt")
                .put("content", "ok")
                .toString()
            + "\n```";
    server.enqueue(
        new MockResponse()
            .setBody(
                new JSONObject()
                    .put(
                        "choices",
                        new JSONArray()
                            .put(
                                new JSONObject()
                                    .put("message", new JSONObject().put("content", operation))))
                    .toString()));
    server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After", "0"));
    server.enqueue(new MockResponse().setBody(DONE));
    AtomicInteger writes = new AtomicInteger();
    ChatClient.Call call = new ChatClient.Call(millis -> {});
    String result =
        AgentRunner.run(
            new JSONArray(),
            true,
            h -> call.chat(base, "", "model", "system", h),
            action -> {
              writes.incrementAndGet();
              return "commit";
            },
            (role, value) -> {},
            () -> {});
    assertEquals("Ответ получен.", result);
    assertEquals(1, writes.get());
    assertEquals(3, server.getRequestCount());
    server.takeRequest();
    assertEquals(
        server.takeRequest().getBody().readUtf8(), server.takeRequest().getBody().readUtf8());
  }

  @Test
  public void serverErrorIsNotRetried() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));
    ChatClient.Call call =
        new ChatClient.Call(
            millis -> {
              fail();
            });
    try {
      call.chat(base, "", "model", "system", new JSONArray());
      fail();
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("500"));
    }
    assertEquals(1, server.getRequestCount());
  }
}
