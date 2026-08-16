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

object Config:
  private def env(key: String): Option[String] = sys.env.get(key).filter(_.nonEmpty)
  private def envOr(key: String, default: String): String = env(key).getOrElse(default)

  def load(): Config = Config(
    port = envOr("PORT", "3003").toInt,
    dbHost = envOr("DB_HOST", "localhost"),
    dbPort = envOr("DB_PORT", "5432").toInt,
    dbName = envOr("DB_NAME", "twitninf"),
    dbUser = envOr("DB_USER", "admin"),
    dbPassword = env("DB_PASSWORD").getOrElse(sys.error("DB_PASSWORD manquant")),
    redisHost = envOr("REDIS_HOST", "localhost"),
    redisPort = envOr("REDIS_PORT", "6379").toInt,
    redisPassword = env("REDIS_PASSWORD"),
    internalSecret = env("INTERNAL_SECRET").getOrElse(sys.error("INTERNAL_SECRET manquant"))
  )
