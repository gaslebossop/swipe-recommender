package swipe

import cats.effect.IO
import org.http4s.{HttpRoutes, Request}
import org.http4s.dsl.Http4sDsl
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString
import java.time.Instant

/** Toutes les routes exposées par le service. `/health` est public (utilisé
  * par le monitoring), le reste exige `X-Service-Key` — même en-tête que
  * rust-recommender, même secret (`INTERNAL_SECRET`).
  */
object Routes extends Http4sDsl[IO]:
  private val defaultLimit = 20
  private val maxLimit = 50
  private val candidatePoolSize = 300
  private val queueSize = 100

  private def authorized(req: Request[IO], cfg: Config): Boolean =
    req.headers.get(CIString("X-Service-Key")).map(_.head.value).contains(cfg.internalSecret)

  private def computeQueue(db: Db, cache: Cache, userId: String): IO[List[ScoredCandidate]] =
    for
      self      <- db.selfProfile(userId)
      pool      <- db.candidatePool(userId, candidatePoolSize)
      passedIds <- cache.recentlyPassedIds(userId)
      weights   <- cache.weights
      now        = Instant.now()
      filtered   = pool.filterNot(c => passedIds.contains(c.id))
      scored     = filtered.map { c =>
                     val dims = Scoring.dimensions(self, c, now)
                     (c, Scoring.weightedScore(dims, weights), dims.reasons)
                   }
    yield Scoring.diversify(scored, limit = queueSize)

  def all(cfg: Config, db: Db, cache: Cache): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" =>
      for
        dbOk    <- db.ping
        redisOk <- cache.ping
        status   = if dbOk && redisOk then "ok" else "degraded"
        resp    <- Ok(HealthResponse(status, if dbOk then "ok" else "down", if redisOk then "ok" else "down"))
      yield resp

    case req @ POST -> Root / "swipe" / "candidates" =>
      if !authorized(req, cfg) then Forbidden(SimpleResponse(false, Some("unauthorized")))
      else
        for
          body      <- req.as[CandidatesRequest]
          limit      = math.max(1, math.min(maxLimit, if body.limit > 0 then body.limit else defaultLimit))
          cached    <- if body.forceRefresh then IO.pure(None) else cache.getQueue(body.userId)
          fromCache  = cached.exists(_.nonEmpty) && !body.forceRefresh
          queue     <- cached match
                         case Some(q) if q.nonEmpty => IO.pure(q)
                         case _ =>
                           computeQueue(db, cache, body.userId).flatTap(q => cache.setQueue(body.userId, q))
          resp      <- Ok(CandidatesResponse(true, queue.take(limit), fromCache))
        yield resp

    case req @ POST -> Root / "swipe" / "action" =>
      if !authorized(req, cfg) then Forbidden(SimpleResponse(false, Some("unauthorized")))
      else
        for
          body <- req.as[ActionRequest]
          _    <- body.action match
                    case "pass"   => cache.recordPass(body.userId, body.targetUserId)
                    case "follow" => cache.dropFromQueue(body.userId, body.targetUserId)
                    case _        => IO.unit
          resp <- Ok(SimpleResponse(true))
        yield resp

    case req @ POST -> Root / "swipe" / "invalidate" =>
      if !authorized(req, cfg) then Forbidden(SimpleResponse(false, Some("unauthorized")))
      else
        for
          body <- req.as[InvalidateRequest]
          _    <- cache.invalidateQueue(body.userId)
          resp <- Ok(SimpleResponse(true))
        yield resp
  }
