package repository;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.pekko.actor.ActorSystem;
import play.libs.concurrent.CustomExecutionContext;

/**
 * Where blocking JDBC work runs. Sized in application.conf ("database.dispatcher") to match the
 * connection pool, so a burst of slow queries queues here instead of starving the default
 * dispatcher that serves requests.
 */
@Singleton
public class DatabaseExecutionContext extends CustomExecutionContext {

  @Inject
  public DatabaseExecutionContext(ActorSystem actorSystem) {
    super(actorSystem, "database.dispatcher");
  }
}
