package swipe

import cats.effect.IO
import io.circe.parser.decode
import io.circe.syntax.*
import redis.clients.jedis.{Jedis, JedisPool, JedisPoolConfig}
import scala.jdk.CollectionConverters.*

/** Cache Redis : file de candidats classés par utilisateur (TTL 45 min) et
  * cooldown des profils "passés" (ZSET, 21 jours). Aucune écriture
  * Postgres — tout l'état mutable de ce service vit ici.
  */
final class Cache(cfg: Config):
  private val pool: JedisPool =
    val poolCfg = new JedisPoolConfig()
    poolCfg.setMaxTotal(16)
    new JedisPool(poolCfg, cfg.redisHost, cfg.redisPort, 2000, cfg.redisPassword.orNull)

  private def withJedis[A](f: Jedis => A): IO[A] =
    IO.blocking {
      val j = pool.getResource
      try f(j)
      finally j.close()
    }

  private def queueKey(userId: String) = s"swipe:queue:$userId"
  private def passedKey(userId: String) = s"swipe:passed:$userId"
  private val weightsKey = "swipe:algo:weights"

  private val queueTtlSeconds = 45 * 60
  private val passCooldownSeconds = 21L * 24 * 3600

  def ping: IO[Boolean] =
    withJedis(j => j.ping() == "PONG").handleError(_ => false)

  def getQueue(userId: String): IO[Option[List[ScoredCandidate]]] =
    withJedis { j =>
      Option(j.get(queueKey(userId))).flatMap(raw => decode[List[ScoredCandidate]](raw).toOption)
    }

  def setQueue(userId: String, candidates: List[ScoredCandidate]): IO[Unit] =
    withJedis { j =>
      j.setex(queueKey(userId), queueTtlSeconds, candidates.asJson.noSpaces)
      ()
    }

  def dropFromQueue(userId: String, targetId: String): IO[Unit] =
    getQueue(userId).flatMap {
      case Some(list) => setQueue(userId, list.filterNot(_.id == targetId))
      case None       => IO.unit
    }

  def invalidateQueue(userId: String): IO[Unit] =
    withJedis { j =>
      j.del(queueKey(userId))
      ()
    }

  def recordPass(userId: String, targetId: String): IO[Unit] =
    withJedis { j =>
      val now = System.currentTimeMillis() / 1000d
      j.zadd(passedKey(userId), now, targetId)
      j.expire(passedKey(userId), passCooldownSeconds)
      ()
    }.flatMap(_ => dropFromQueue(userId, targetId))

  def recentlyPassedIds(userId: String): IO[Set[String]] =
    withJedis { j =>
      val cutoff = (System.currentTimeMillis() / 1000d) - passCooldownSeconds
      j.zremrangeByScore(passedKey(userId), 0d, cutoff)
      j.zrangeByScore(passedKey(userId), cutoff, Double.MaxValue).asScala.toSet
    }

  def weights: IO[AlgoWeights] =
    withJedis(j => Option(j.get(weightsKey)))
      .map(_.flatMap(raw => decode[AlgoWeights](raw).toOption).getOrElse(AlgoWeights.default))
