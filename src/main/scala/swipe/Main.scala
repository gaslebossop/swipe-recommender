package swipe

import cats.effect.{ExitCode, IO, IOApp}
import com.comcast.ip4s.{Port, host, port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.slf4j.LoggerFactory

object Main extends IOApp:
  private val logger = LoggerFactory.getLogger("swipe.Main")

  def run(args: List[String]): IO[ExitCode] =
    val cfg = Config.load()
    val db = new Db(cfg)
    val cache = new Cache(cfg)
    val app = Routes.all(cfg, db, cache).orNotFound

    EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(Port.fromInt(cfg.port).getOrElse(port"3003"))
      .withHttpApp(app)
      .build
      .use { server =>
        IO(logger.info(s"swipe-recommender démarré sur ${server.address}")) *> IO.never
      }
      .as(ExitCode.Success)
