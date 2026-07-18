import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import io.ebean.DB;
import io.ebean.Database;
import jakarta.inject.Singleton;
import play.db.ebean.EbeanDynamicEvolutions;

/**
 * Play loads a root-package class named {@code Module} automatically.
 *
 * <p>play-ebean initializes the default Ebean server but does not bind {@link Database} for
 * injection — anything injecting it fails with Guice's MissingImplementation. The provider's
 * {@link EbeanDynamicEvolutions} parameter is unused on purpose: depending on it forces Ebean's
 * initialization (and evolutions) to complete before {@code DB.getDefault()} is first asked for
 * the server.
 */
public class Module extends AbstractModule {

  @Provides
  @Singleton
  Database database(EbeanDynamicEvolutions unusedForcesEbeanInit) {
    return DB.getDefault();
  }
}
