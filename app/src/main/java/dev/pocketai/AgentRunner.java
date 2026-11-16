package dev.pocketai;

import org.json.*;

/** One bounded, cancellable sequence. A failed mutation is never retried automatically. */
final class AgentRunner {
  /** A request rejected before any local or remote operation has started. */
  static final class ActionNotStartedException extends java.io.IOException {
    ActionNotStartedException(String message) {
      super(message);
    }
  }

  interface Model {
    String reply(JSONArray history) throws Exception;
  }

  interface Tools {
    String execute(JSONObject action) throws Exception;
  }

  interface Journal {
    void append(String role, String value);
  }

  interface Cancellation {
    void check() throws Exception;
  }

  static String result(JSONObject action, String value) {
    return "Результат действия "
        + action.optString("op")
        + " "
        + action.optString("path")
        + ":\n"
        + value;
  }

  static String run(
      JSONArray history,
      boolean auto,
      Model model,
      Tools tools,
      Journal journal,
      Cancellation cancellation)
      throws Exception {
    int rejectedInARow = 0;
    for (int step = 0; step <= 24; step++) {
      cancellation.check();
      String answer = model.reply(history);
      history.put(AppState.object("role", "assistant", "content", answer));
      journal.append("assistant", answer);
      JSONObject action = AgentFiles.action(answer);
      if (action == null) return "Ответ получен.";
      if (!auto) return "Действие готово к выполнению.";
      if (step == 24) return "Достигнут предел 24 действий. Проверь журнал и нажми «Продолжить».";
      cancellation.check();
      String outcome;
      try {
        outcome = tools.execute(action);
        rejectedInARow = 0;
      } catch (ActionNotStartedException error) {
        // This exception can only be raised before any filesystem or network operation.
        String value = result(action, "Действие не выполнено: " + error.getMessage());
        history.put(AppState.object("role", "user", "content", value));
        journal.append("user", value);
        if (++rejectedInARow >= 3)
          return "Несколько действий отклонены из-за настроек доступа. Проверь настройки и"
                     + " продолжи.";
        continue;
      } catch (Exception error) {
        // The server may have applied a write even if the response was lost.
        outcome =
            "Ошибка действия. Проверь фактическое состояние перед повтором. "
                + (error instanceof java.io.IOException
                    ? error.getMessage()
                    : "Проверь путь, формат и настройки доступа.");
        journal.append("user", result(action, outcome));
        return outcome;
      }
      String value = result(action, outcome);
      history.put(AppState.object("role", "user", "content", value));
      journal.append("user", value);
    }
    throw new IllegalStateException("Unreachable");
  }
}
