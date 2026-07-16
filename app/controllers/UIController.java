package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Set;
import play.api.mvc.AnyContent;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;

/**
 * Serves the compiled React UI (built from ui/ and served from the classpath at /public).
 *
 * <p>Requests are classified as either static assets (served from the classpath with appropriate
 * caching) or client-side routes (served the SPA shell, index.html, so that React Router can take
 * over). Vite content-hashes everything under assets/, so those are served with
 * play.assets.aggressiveCache (a year, immutable). index.html cannot be cached
 * that way, since it is the only document naming the current bundles: a stale shell would send
 * browsers after bundle URLs that no longer exist after a deploy. It falls back instead to
 * play.assets.defaultCache, which conf/application.conf overrides to "max-age=0, must-revalidate"
 * for precisely that reason -- Play's own default of one hour is not safe here.
 */
public class UIController extends Controller {

  // Directories whose contents are content-hashed by the Vite build. A changed file always gets a
  // new URL, so these can be cached forever ("immutable").
  //
  // This is Vite's default output layout: JS, CSS and imported assets all land in assets/ with a
  // hash in the filename. Files copied verbatim from ui/public (favicon.svg, icons.svg) land at
  // the root instead and keep their names across releases, so the prefix test correctly excludes
  // them. Overriding rollupOptions.output.*FileNames in vite.config.ts means updating this set.
  private static final Set<String> HASHED_ASSET_DIRS = Set.of("assets/");

  // File extensions that identify a request for a real static asset. Anything else is assumed
  // to be a client-side route and is served the SPA shell instead.
  private static final Set<String> STATIC_EXTENSIONS =
      Set.of(
          "js", "mjs", "css", "map", "json", "html", "txt", "xml", "webmanifest", // code/docs
          "ico", "png", "jpg", "jpeg", "gif", "svg", "webp", "avif", // images
          "woff", "woff2", "ttf", "eot", "otf", // fonts
          "wasm");

  private final Assets assets;

  @Inject
  public UIController(Assets assets) {
    this.assets = assets;
  }

  public play.api.mvc.Action<AnyContent> index() {
    return assets.at("/public", "index.html", false);
  }

  public play.api.mvc.Action<AnyContent> assetOrDefault(String resource) {
    if (isStaticResource(resource)) {
      // Content-hashed bundles get aggressive caching (play.assets.aggressiveCache);
      // other static files (favicon, robots.txt, ...) keep the default cache policy with
      // ETag revalidation since their URLs do not change across releases.
      return assets.at("/public", resource, isContentHashed(resource));
    }
    // Not a static asset: assume a client-side route and let React Router handle it.
    return index();
  }

  // API paths must never fall through to the SPA shell; return a JSON 404 rather than an HTML page
  // an API client cannot parse.
  public Result unknown(String resource) {
    ObjectNode error = Json.newObject();
    error.put("success", false);
    error.put("error", String.format("%s not found", resource));
    return notFound(error);
  }

  /**
   * True if {@code resource} names a real file under /public rather than a client-side route.
   *
   * <p>Classification is by extension against a closed whitelist, not by "has a dot": a route like
   * user/jane.doe carries an extension that is not a known asset type and so is correctly handed to
   * React Router. The whitelist is the load-bearing part -- widening it to "any extension" would
   * break such routes.
   *
   * <p>Package-private for testing.
   */
  static boolean isStaticResource(String resource) {
    if (resource.contains("..")) {
      // Route traversal attempts through the Assets controller, which safely rejects
      // any path escaping /public with a 404 (never serve them the SPA shell).
      return true;
    }
    String path = resource.toLowerCase(Locale.ROOT);
    String fileName = path.substring(path.lastIndexOf('/') + 1);
    int extensionAt = fileName.lastIndexOf('.');
    if (extensionAt < 0) {
      return false;
    }
    return STATIC_EXTENSIONS.contains(fileName.substring(extensionAt + 1));
  }

  /**
   * True if {@code resource} lives in a directory Vite content-hashes, and so may be cached
   * immutably forever.
   *
   * <p>Matched case-insensitively to stay consistent with {@link #isStaticResource}, which
   * lowercases before testing the extension. This only decides a cache header; the file lookup
   * itself still uses the caller's exact casing.
   *
   * <p>Package-private for testing.
   */
  static boolean isContentHashed(String resource) {
    String path = resource.toLowerCase(Locale.ROOT);
    return HASHED_ASSET_DIRS.stream().anyMatch(path::startsWith);
  }
}
