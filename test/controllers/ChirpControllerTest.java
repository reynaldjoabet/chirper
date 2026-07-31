package controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.CREATED;
import static play.mvc.Http.Status.FORBIDDEN;
import static play.mvc.Http.Status.NOT_FOUND;
import static play.mvc.Http.Status.NO_CONTENT;
import static play.mvc.Http.Status.OK;
import static play.mvc.Http.Status.UNAUTHORIZED;
import static play.test.Helpers.DELETE;
import static play.test.Helpers.GET;
import static play.test.Helpers.POST;
import static play.test.Helpers.contentAsString;
import static play.test.Helpers.route;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.test.WithApplication;

/**
 * Routes real requests through the whole stack: router, CSRF config, controller, dispatcher, and
 * the real Ebean repository against H2. Each test gets a fresh application and its own uniquely
 * named in-memory database (evolutions apply the schema on startup), so tests are isolated and
 * independent of dev seed data — seeding is dev-mode only and test apps run in test mode.
 */
public class ChirpControllerTest extends WithApplication {

  @Override
  protected play.Application provideApplication() {
    return new play.inject.guice.GuiceApplicationBuilder()
        .configure(
            "db.default.url",
            "jdbc:h2:mem:test-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
        .build();
  }

  private Result routeJson(String method, String uri, JsonNode body) {
    Http.RequestBuilder request = new Http.RequestBuilder().method(method).uri(uri);
    if (body != null) {
      request.bodyJson(body);
    }
    return route(app, request);
  }

  private JsonNode parse(Result result) {
    return Json.parse(contentAsString(result));
  }

  private JsonNode createChirp(String author, String body) {
    // Authorship comes from the session, so the helper signs the request as `author`.
    Http.RequestBuilder request =
        new Http.RequestBuilder()
            .method(POST)
            .uri("/api/chirps")
            .session("handle", author)
            .bodyJson(Json.newObject().put("body", body));
    Result result = route(app, request);
    assertEquals(CREATED, result.status());
    JsonNode json = parse(result);
    assertTrue(json.get("success").asBoolean());
    return json.get("data");
  }

  @Test
  public void listStartsEmptyAndReturnsNewestFirst() {
    // Test apps are unseeded: the database starts empty.
    Result empty = route(app, new Http.RequestBuilder().method(GET).uri("/api/chirps"));
    assertEquals(OK, empty.status());
    assertEquals(0, parse(empty).get("data").size());

    createChirp("ordering", "first");
    createChirp("ordering", "second");
    createChirp("ordering", "third");

    Result result = route(app, new Http.RequestBuilder().method(GET).uri("/api/chirps"));
    assertEquals(OK, result.status());
    JsonNode json = parse(result);
    assertTrue(json.get("success").asBoolean());
    JsonNode data = json.get("data");
    assertEquals(3, data.size());
    assertEquals("third", data.get(0).get("body").asText());
    // Newest first: ids strictly descending.
    for (int i = 1; i < data.size(); i++) {
      assertTrue(data.get(i - 1).get("id").asLong() > data.get(i).get("id").asLong());
    }
  }

  @Test
  public void createRoundTrips() {
    JsonNode created = createChirp("testuser", "hello from the test suite");
    assertEquals("testuser", created.get("author").asText());
    assertEquals("hello from the test suite", created.get("body").asText());
    assertEquals(0, created.get("likes").asInt());

    Result get =
        route(
            app,
            new Http.RequestBuilder().method(GET).uri("/api/chirps/" + created.get("id").asLong()));
    assertEquals(OK, get.status());
    assertEquals(created, parse(get).get("data"));
  }

  @Test
  public void createValidatesInput() {
    // Blank body.
    Result blankBody =
        route(
            app,
            new Http.RequestBuilder()
                .method(POST)
                .uri("/api/chirps")
                .session("handle", "ok")
                .bodyJson(Json.newObject().put("body", "   ")));
    assertEquals(BAD_REQUEST, blankBody.status());

    // Over the 280 limit.
    Result tooLong =
        route(
            app,
            new Http.RequestBuilder()
                .method(POST)
                .uri("/api/chirps")
                .session("handle", "ok")
                .bodyJson(Json.newObject().put("body", "x".repeat(281))));
    assertEquals(BAD_REQUEST, tooLong.status());

    // No JSON body at all.
    Result noBody =
        route(
            app,
            new Http.RequestBuilder().method(POST).uri("/api/chirps").session("handle", "ok"));
    assertEquals(BAD_REQUEST, noBody.status());
  }

  @Test
  public void writesRequireASession() {
    // Create without a session -> 401, and nothing is stored.
    Result anonymous =
        routeJson(POST, "/api/chirps", Json.newObject().put("body", "should not exist"));
    assertEquals(UNAUTHORIZED, anonymous.status());

    // Delete without a session -> 401; someone else's chirp -> 403.
    long id = createChirp("owner", "mine").get("id").asLong();
    Result noSession =
        route(app, new Http.RequestBuilder().method(DELETE).uri("/api/chirps/" + id));
    assertEquals(UNAUTHORIZED, noSession.status());
    Result wrongUser =
        route(
            app,
            new Http.RequestBuilder()
                .method(DELETE)
                .uri("/api/chirps/" + id)
                .session("handle", "intruder"));
    assertEquals(FORBIDDEN, wrongUser.status());

    // The owner can.
    Result owner =
        route(
            app,
            new Http.RequestBuilder()
                .method(DELETE)
                .uri("/api/chirps/" + id)
                .session("handle", "owner"));
    assertEquals(NO_CONTENT, owner.status());
  }

  @Test
  public void authorFilterReturnsOnlyThatAuthor() {
    createChirp("filterme", "first");
    createChirp("filterme", "second");
    createChirp("someoneelse", "third");

    Result result =
        route(app, new Http.RequestBuilder().method(GET).uri("/api/chirps?author=filterme"));
    assertEquals(OK, result.status());
    JsonNode data = parse(result).get("data");
    assertEquals(2, data.size());
    data.forEach(c -> assertEquals("filterme", c.get("author").asText()));
  }

  @Test
  public void likeIncrementsAndPersists() {
    long id = createChirp("liker", "like me").get("id").asLong();

    Result liked =
        route(app, new Http.RequestBuilder().method(POST).uri("/api/chirps/" + id + "/like"));
    assertEquals(OK, liked.status());
    assertEquals(1, parse(liked).get("data").get("likes").asInt());

    Result get = route(app, new Http.RequestBuilder().method(GET).uri("/api/chirps/" + id));
    assertEquals(1, parse(get).get("data").get("likes").asInt());
  }

  @Test
  public void deleteRemovesTheChirp() {
    long id = createChirp("deleter", "delete me").get("id").asLong();

    Result deleted =
        route(
            app,
            new Http.RequestBuilder()
                .method(DELETE)
                .uri("/api/chirps/" + id)
                .session("handle", "deleter"));
    assertEquals(NO_CONTENT, deleted.status());

    Result get = route(app, new Http.RequestBuilder().method(GET).uri("/api/chirps/" + id));
    assertEquals(NOT_FOUND, get.status());
  }

  @Test
  public void unknownIdAndUnknownApiPathReturnJsonNotFound() {
    Result missing = route(app, new Http.RequestBuilder().method(GET).uri("/api/chirps/999999"));
    assertEquals(NOT_FOUND, missing.status());
    assertFalse(parse(missing).get("success").asBoolean());

    Result unknownPath = route(app, new Http.RequestBuilder().method(GET).uri("/api/nope"));
    assertEquals(NOT_FOUND, unknownPath.status());
    assertFalse(parse(unknownPath).get("success").asBoolean());
  }
}

