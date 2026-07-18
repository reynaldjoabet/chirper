package controllers;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import models.Chirp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import repository.ChirpRepository;
import repository.DatabaseExecutionContext;

/**
 * The JSON API under /api. Responses share one envelope with {@link UIController#unknown}:
 * {@code {"success":true,"data":...}} on success, {@code {"success":false,"error":"..."}} on
 * failure, so a client can branch on {@code success} without caring which controller answered.
 *
 * <p>Repository calls block on JDBC, so every one of them is dispatched to
 * {@link DatabaseExecutionContext} via supplyAsync — the default dispatcher never waits on a
 * connection. Validation failures short-circuit before touching the database.
 *
 * <p>JSON is built by hand with ObjectNode rather than reflective {@code Json.toJson(record)}: the
 * wire format stays pinned even if the record grows fields, and Instant needs no Jackson module
 * this way. GETs are marked no-store — timelines change constantly and an intermediary caching a
 * JSON body would serve deleted chirps.
 */
public class ChirpController extends Controller {

  private static final Logger log = LoggerFactory.getLogger(ChirpController.class);

  /** Twitter's classic handle rules: word characters, at most 15. */
  private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_]{1,15}");

  /** Code points, not UTF-16 chars: an emoji chirp is not half the length it looks. */
  private static final int MAX_BODY = 280;

  private final ChirpRepository chirps;
  private final DatabaseExecutionContext ec;

  @Inject
  public ChirpController(ChirpRepository chirps, DatabaseExecutionContext ec) {
    this.chirps = chirps;
    this.ec = ec;
  }

  public CompletionStage<Result> list(Http.Request request) {
    Optional<String> author = request.queryString("author");
    return supplyAsync(() -> chirps.list(author), ec)
        .thenApply(
            list -> {
              ArrayNode data = Json.newArray();
              list.forEach(c -> data.add(toJson(c)));
              return noStore(ok(envelope(data)));
            });
  }

  public CompletionStage<Result> get(Long id) {
    return supplyAsync(() -> chirps.find(id), ec)
        .thenApply(
            found ->
                found
                    .map(c -> noStore(ok(envelope(toJson(c)))))
                    .orElseGet(() -> notFoundJson(id)));
  }

  public CompletionStage<Result> create(Http.Request request) {
    JsonNode json = request.body().asJson();
    if (json == null) {
      return completedFuture(badRequestJson("expected a JSON body"));
    }
    String author = json.path("author").asText("").trim();
    String body = json.path("body").asText("").trim();
    if (!HANDLE.matcher(author).matches()) {
      return completedFuture(badRequestJson("author must be 1-15 letters, digits or underscores"));
    }
    if (body.isEmpty()) {
      return completedFuture(badRequestJson("body must not be empty"));
    }
    if (body.codePointCount(0, body.length()) > MAX_BODY) {
      return completedFuture(badRequestJson("body must be at most " + MAX_BODY + " characters"));
    }
    return supplyAsync(() -> chirps.create(author, body), ec)
        .thenApply(
            chirp -> {
              log.info("chirp {} created by @{}", chirp.id(), chirp.author());
              return created(envelope(toJson(chirp)));
            });
  }

  public CompletionStage<Result> like(Long id) {
    return supplyAsync(() -> chirps.like(id), ec)
        .thenApply(
            liked -> liked.map(c -> ok(envelope(toJson(c)))).orElseGet(() -> notFoundJson(id)));
  }

  public CompletionStage<Result> delete(Long id) {
    return supplyAsync(() -> chirps.delete(id), ec)
        .thenApply(
            deleted -> {
              if (deleted) {
                log.info("chirp {} deleted", id);
                return noContent();
              }
              return notFoundJson(id);
            });
  }

  private static Result noStore(Result result) {
    return result.withHeader(Http.HeaderNames.CACHE_CONTROL, "no-store");
  }

  private static ObjectNode toJson(Chirp c) {
    ObjectNode node = Json.newObject();
    node.put("id", c.id());
    node.put("author", c.author());
    node.put("body", c.body());
    node.put("createdAt", c.createdAt().toString()); // ISO-8601, e.g. 2026-07-17T09:15:30Z
    node.put("likes", c.likes());
    return node;
  }

  private static ObjectNode envelope(JsonNode data) {
    ObjectNode node = Json.newObject();
    node.put("success", true);
    node.set("data", data);
    return node;
  }

  private static Result badRequestJson(String message) {
    ObjectNode node = Json.newObject();
    node.put("success", false);
    node.put("error", message);
    return badRequest(node);
  }

  private static Result notFoundJson(Long id) {
    ObjectNode node = Json.newObject();
    node.put("success", false);
    node.put("error", "chirp " + id + " not found");
    return notFound(node);
  }
}
