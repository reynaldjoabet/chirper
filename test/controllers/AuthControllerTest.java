package controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.CONFLICT;
import static play.mvc.Http.Status.CREATED;
import static play.mvc.Http.Status.NO_CONTENT;
import static play.mvc.Http.Status.OK;
import static play.mvc.Http.Status.UNAUTHORIZED;
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

/** Registration, login and session lifecycle against the real stack (see ChirpControllerTest). */
public class AuthControllerTest extends WithApplication {

  @Override
  protected play.Application provideApplication() {
    return new play.inject.guice.GuiceApplicationBuilder()
        .configure(
            "db.default.url",
            "jdbc:h2:mem:test-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
        .build();
  }

  private JsonNode parse(Result result) {
    return Json.parse(contentAsString(result));
  }

  private Result authPost(String uri, String handle, String password) {
    return route(
        app,
        new Http.RequestBuilder()
            .method(POST)
            .uri(uri)
            .bodyJson(Json.newObject().put("handle", handle).put("password", password)));
  }

  @Test
  public void registerSignsIn() {
    Result result = authPost("/api/auth/register", "newuser", "correct-horse");
    assertEquals(CREATED, result.status());
    JsonNode data = parse(result).get("data");
    assertEquals("newuser", data.get("handle").asText());
    // The password hash must never appear in any response.
    assertFalse(contentAsString(result).toLowerCase().contains("password"));
    // Registration establishes the session.
    assertEquals("newuser", result.session().get("handle").orElse(""));
  }

  @Test
  public void registerValidatesAndRejectsDuplicates() {
    assertEquals(BAD_REQUEST, authPost("/api/auth/register", "bad handle", "long-enough").status());
    assertEquals(BAD_REQUEST, authPost("/api/auth/register", "shortpw", "seven77").status());

    assertEquals(CREATED, authPost("/api/auth/register", "taken", "long-enough").status());
    Result duplicate = authPost("/api/auth/register", "taken", "another-password");
    assertEquals(CONFLICT, duplicate.status());
    assertFalse(parse(duplicate).get("success").asBoolean());
  }

  @Test
  public void loginVerifiesThePassword() {
    authPost("/api/auth/register", "returning", "right-password");

    Result ok = authPost("/api/auth/login", "returning", "right-password");
    assertEquals(OK, ok.status());
    assertEquals("returning", ok.session().get("handle").orElse(""));

    // Wrong password and unknown handle answer identically: no handle enumeration.
    Result wrong = authPost("/api/auth/login", "returning", "wrong-password");
    assertEquals(UNAUTHORIZED, wrong.status());
    Result unknown = authPost("/api/auth/login", "nobody", "whatever-long");
    assertEquals(UNAUTHORIZED, unknown.status());
    assertEquals(parse(wrong).get("error"), parse(unknown).get("error"));
  }

  @Test
  public void meReflectsTheSession() {
    Result anonymous = route(app, new Http.RequestBuilder().method(GET).uri("/api/auth/me"));
    assertEquals(UNAUTHORIZED, anonymous.status());

    authPost("/api/auth/register", "session_user", "long-enough");
    Result me =
        route(
            app,
            new Http.RequestBuilder()
                .method(GET)
                .uri("/api/auth/me")
                .session("handle", "session_user"));
    assertEquals(OK, me.status());
    assertEquals("session_user", parse(me).get("data").get("handle").asText());

    // A stale cookie naming a nonexistent user is cleared, not an error loop.
    Result stale =
        route(
            app,
            new Http.RequestBuilder().method(GET).uri("/api/auth/me").session("handle", "ghost"));
    assertEquals(UNAUTHORIZED, stale.status());
  }

  @Test
  public void logoutClearsTheSession() {
    authPost("/api/auth/register", "leaver", "long-enough");
    Result out =
        route(
            app,
            new Http.RequestBuilder()
                .method(POST)
                .uri("/api/auth/logout")
                .session("handle", "leaver"));
    assertEquals(NO_CONTENT, out.status());
    assertTrue(out.session().data().isEmpty());
  }
}
