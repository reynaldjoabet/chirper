package controllers;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.favre.lib.crypto.bcrypt.BCrypt;
import io.ebean.DuplicateKeyException;
import jakarta.inject.Inject;
import models.User;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import repository.DatabaseExecutionContext;
import repository.UserRepository;

/**
 * Registration, login and session management under /api/auth.
 *
 * <p>The session is Play's signed cookie holding only the handle; the browser keeps it and the
 * SameSite=Lax default is what makes the +nocsrf routes tolerable (a cross-site POST does not
 * carry the cookie in modern browsers). Login and register respond identically on failure —
 * "invalid handle or password" — so the API does not confirm which handles exist.
 *
 * <p>bcrypt with cost 12 for password hashes. Verification runs on the database dispatcher too:
 * bcrypt is deliberately slow, which is exactly what must not run on the request dispatcher.
 */
public class AuthController extends Controller {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  static final String SESSION_HANDLE = "handle";

  private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_]{1,15}");
  private static final int MIN_PASSWORD = 8;
  private static final int BCRYPT_COST = 12;

  private final UserRepository users;
  private final DatabaseExecutionContext ec;

  @Inject
  public AuthController(UserRepository users, DatabaseExecutionContext ec) {
    this.users = users;
    this.ec = ec;
  }

  public CompletionStage<Result> register(Http.Request request) {
    JsonNode json = request.body().asJson();
    if (json == null) {
      return completedFuture(badRequestJson("expected a JSON body"));
    }
    String handle = json.path("handle").asText("").trim();
    String password = json.path("password").asText("");
    if (!HANDLE.matcher(handle).matches()) {
      return completedFuture(badRequestJson("handle must be 1-15 letters, digits or underscores"));
    }
    if (password.length() < MIN_PASSWORD) {
      return completedFuture(
          badRequestJson("password must be at least " + MIN_PASSWORD + " characters"));
    }
    return supplyAsync(
            () -> {
              String hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
              return users.create(handle, hash);
            },
            ec)
        .<Result>thenApply(
            user -> {
              log.info("user @{} registered", user.handle);
              return created(envelope(toJson(user))).addingToSession(request, SESSION_HANDLE, user.handle);
            })
        .exceptionally(
            e -> {
              if (e.getCause() instanceof DuplicateKeyException) {
                return status(CONFLICT, errorJson("handle is already taken"));
              }
              throw new RuntimeException(e);
            });
  }

  public CompletionStage<Result> login(Http.Request request) {
    JsonNode json = request.body().asJson();
    if (json == null) {
      return completedFuture(badRequestJson("expected a JSON body"));
    }
    String handle = json.path("handle").asText("").trim();
    String password = json.path("password").asText("");
    return supplyAsync(
            () -> {
              Optional<User> user = users.findByHandle(handle);
              boolean ok =
                  user.isPresent()
                      && BCrypt.verifyer()
                          .verify(password.toCharArray(), user.get().passwordHash)
                          .verified;
              return ok ? user : Optional.<User>empty();
            },
            ec)
        .thenApply(
            user ->
                user.map(
                        u ->
                            ok(envelope(toJson(u)))
                                .addingToSession(request, SESSION_HANDLE, u.handle))
                    .orElseGet(
                        () -> unauthorized(errorJson("invalid handle or password"))));
  }

  public Result logout(Http.Request request) {
    return noContent().withNewSession();
  }

  /** The session's user, or 401. The UI calls this on load to restore a signed-in state. */
  public CompletionStage<Result> me(Http.Request request) {
    Optional<String> handle = request.session().get(SESSION_HANDLE);
    if (handle.isEmpty()) {
      return completedFuture(unauthorized(errorJson("not signed in")));
    }
    return supplyAsync(() -> users.findByHandle(handle.get()), ec)
        .thenApply(
            user ->
                user.map(u -> ok(envelope(toJson(u))))
                    // A session naming a deleted user is a stale cookie, not an error loop.
                    .orElseGet(() -> unauthorized(errorJson("not signed in")).withNewSession()));
  }

  private static ObjectNode toJson(User u) {
    ObjectNode node = Json.newObject();
    node.put("id", u.id);
    node.put("handle", u.handle);
    node.put("createdAt", u.createdAt.toString());
    return node;
  }

  private static ObjectNode envelope(JsonNode data) {
    ObjectNode node = Json.newObject();
    node.put("success", true);
    node.set("data", data);
    return node;
  }

  private static ObjectNode errorJson(String message) {
    ObjectNode node = Json.newObject();
    node.put("success", false);
    node.put("error", message);
    return node;
  }

  private static Result badRequestJson(String message) {
    return badRequest(errorJson(message));
  }
}
