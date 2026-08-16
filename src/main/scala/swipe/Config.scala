package swipe

/** Configuration lue exclusivement depuis l'environnement — jamais de secret
  * en dur ni de fichier `.env` dans ce dépôt (public).
  */
final case class Config(
  port: Int,
  dbHost: String,
  dbPort: Int,
  dbName: String,
  dbUser: String,
  dbPassword: String,
  redisHost: String,
  redisPort: Int,
  redisPassword: Option[String],
  internalSecret: String
)

final case class RedisFromUrl(host: String, port: Int, password: Option[String])

object Config:
  private def env(key: String): Option[String] = sys.env.get(key).filter(_.nonEmpty)
  private def envOr(key: String, default: String): String = env(key).getOrElse(default)

  // Le VPS configure Redis via une seule REDIS_URL (redis://:password@host:port),
  // même convention que rust-recommender/api — pas de REDIS_HOST/PORT/PASSWORD
  // séparés en pratique. On garde ces trois-là en repli pour un usage local.
  private def parseRedisUrl(raw: String): Option[RedisFromUrl] =
    try
      val uri = new java.net.URI(raw)
      val host = Option(uri.getHost).getOrElse("localhost")
      val port = if uri.getPort > 0 then uri.getPort else 6379
      val password = Option(uri.getUserInfo).map(_.stripPrefix(":")).filter(_.nonEmpty)
      Some(RedisFromUrl(host, port, password))
    catch case _: Exception => None

  def load(): Config =
    val fromUrl = env("REDIS_URL").flatMap(parseRedisUrl)
    Config(
      port = envOr("PORT", "3005").toInt,
      dbHost = envOr("DB_HOST", "localhost"),
      dbPort = envOr("DB_PORT", "5432").toInt,
      dbName = envOr("DB_NAME", "twitninf"),
      dbUser = envOr("DB_USER", "admin"),
      dbPassword = env("DB_PASSWORD").getOrElse(sys.error("DB_PASSWORD manquant")),
      redisHost = fromUrl.map(_.host).getOrElse(envOr("REDIS_HOST", "localhost")),
      redisPort = fromUrl.map(_.port).getOrElse(envOr("REDIS_PORT", "6379").toInt),
      redisPassword = fromUrl.flatMap(_.password).orElse(env("REDIS_PASSWORD")),
      internalSecret = env("INTERNAL_SECRET").getOrElse(sys.error("INTERNAL_SECRET manquant"))
    )
