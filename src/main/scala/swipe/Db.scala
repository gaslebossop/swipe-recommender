package swipe

import cats.effect.IO
import java.sql.{Connection, DriverManager, ResultSet}
import java.time.Instant

/** Accès Postgres en lecture seule — jamais une écriture. Le follow réel
  * reste géré par l'API Node (`UserFollow` Sequelize), qui porte déjà la
  * logique compte privé / hooks de stats / notifications ; ce service ne la
  * duplique pas.
  *
  * JDBC brut plutôt qu'un ORM/DSL fonctionnel : l'API `java.sql` est stable
  * depuis des décennies, ce qui compte ici vu qu'aucune compilation locale
  * n'a pu vérifier ce code avant le premier déploiement en CI.
  */
final class Db(cfg: Config):
  private val url = s"jdbc:postgresql://${cfg.dbHost}:${cfg.dbPort}/${cfg.dbName}"

  Class.forName("org.postgresql.Driver")

  private def withConnection[A](f: Connection => A): IO[A] =
    IO.blocking {
      val conn = DriverManager.getConnection(url, cfg.dbUser, cfg.dbPassword)
      try f(conn)
      finally conn.close()
    }

  def ping: IO[Boolean] =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        val rs = st.executeQuery("SELECT 1")
        try rs.next()
        finally rs.close()
      finally st.close()
    }.handleError(_ => false)

  def selfProfile(userId: String): IO[SelfProfile] =
    withConnection { conn =>
      val ps = conn.prepareStatement("SELECT bio, city FROM users WHERE id = ?::uuid")
      try
        ps.setString(1, userId)
        val rs = ps.executeQuery()
        try
          if rs.next() then SelfProfile(Option(rs.getString("bio")), Option(rs.getString("city")))
          else SelfProfile(None, None)
        finally rs.close()
      finally ps.close()
    }

  // Filtres durs déjà appliqués ici (hors base : suspendu/inactif/déjà en
  // relation) — le cooldown "pass" (Redis) est filtré séparément par Routes,
  // il ne concerne pas Postgres.
  private val candidateSql =
    """
      |SELECT u.id, u.username, u.full_name, u.avatar, u.bio, u.city, u.verified,
      |       u.subscription_tier,
      |       COALESCE((u.stats->>'followers')::int, 0) AS followers_count,
      |       COALESCE((u.stats->>'following')::int, 0) AS following_count,
      |       u.algorithmic_visibility_multiplier,
      |       u.created_at, u.last_activity,
      |       EXISTS(
      |         SELECT 1 FROM user_follows rf
      |         WHERE rf.follower_id = u.id AND rf.following_id = ?::uuid AND rf.status = 'active'
      |       ) AS follows_me_back,
      |       (
      |         SELECT COUNT(*) FROM user_follows mf1
      |         JOIN user_follows mf2 ON mf1.following_id = mf2.following_id
      |         WHERE mf1.follower_id = ?::uuid AND mf1.status = 'active'
      |           AND mf2.follower_id = u.id AND mf2.status = 'active'
      |       ) AS mutual_followers_count,
      |       (
      |         SELECT COUNT(*) FROM moderation_actions ma
      |         WHERE ma.target_type = 'user' AND ma.target_id = u.id
      |           AND ma.type IN ('ban', 'suspend', 'warn') AND ma.status = 'active'
      |       ) AS recent_moderation_hits,
      |       COALESCE((
      |         SELECT SUM(CASE ubd.action_type
      |                      WHEN 'profile_view' THEN 0.5
      |                      WHEN 'user_follow'   THEN 2.0
      |                      ELSE 0 END)
      |         FROM user_behavior_data ubd
      |         WHERE ubd.user_id = ?::uuid AND ubd.target_type = 'user' AND ubd.target_id = u.id::text
      |       ), 0)
      |       +
      |       COALESCE((
      |         SELECT SUM(CASE ubd2.action_type
      |                      WHEN 'tweet_like'    THEN 3.0
      |                      WHEN 'tweet_retweet' THEN 5.0
      |                      WHEN 'tweet_reply'   THEN 4.0
      |                      WHEN 'tweet_view'    THEN 1.0
      |                      ELSE 0 END)
      |         FROM user_behavior_data ubd2
      |         JOIN tweets t ON t.id::text = ubd2.target_id
      |         WHERE ubd2.user_id = ?::uuid AND ubd2.target_type = 'tweet' AND t.user_id = u.id
      |       ), 0) AS behavior_affinity_raw
      |FROM users u
      |WHERE u.id <> ?::uuid
      |  AND u.is_active = true
      |  AND u.is_suspended = false
      |  AND NOT EXISTS (
      |    SELECT 1 FROM user_follows f
      |    WHERE f.follower_id = ?::uuid AND f.following_id = u.id
      |      AND f.status IN ('active', 'pending', 'blocked', 'muted')
      |  )
      |ORDER BY u.last_activity DESC NULLS LAST
      |LIMIT ?
      |""".stripMargin

  def candidatePool(userId: String, limit: Int): IO[List[CandidateRow]] =
    withConnection { conn =>
      val ps = conn.prepareStatement(candidateSql)
      try
        // 6 occurrences de userId dans la requête, puis LIMIT.
        for i <- 1 to 6 do ps.setString(i, userId)
        ps.setInt(7, limit)
        val rs = ps.executeQuery()
        try
          val buf = scala.collection.mutable.ListBuffer.empty[CandidateRow]
          while rs.next() do buf += readCandidate(rs)
          buf.toList
        finally rs.close()
      finally ps.close()
    }

  private def readCandidate(rs: ResultSet): CandidateRow =
    def instantOpt(col: String): Option[Instant] =
      Option(rs.getTimestamp(col)).map(_.toInstant)

    val rawMult = rs.getDouble("algorithmic_visibility_multiplier")
    val algoVisibilityMultiplier = if rs.wasNull() then None else Some(rawMult)

    CandidateRow(
      id = rs.getString("id"),
      username = rs.getString("username"),
      fullName = rs.getString("full_name"),
      avatar = rs.getString("avatar"),
      bio = Option(rs.getString("bio")),
      city = Option(rs.getString("city")),
      verified = rs.getBoolean("verified"),
      subscriptionTier = rs.getString("subscription_tier"),
      followersCount = rs.getInt("followers_count"),
      followingCount = rs.getInt("following_count"),
      algoVisibilityMultiplier = algoVisibilityMultiplier,
      createdAt = instantOpt("created_at").getOrElse(Instant.EPOCH),
      lastActivity = instantOpt("last_activity"),
      followsMeBack = rs.getBoolean("follows_me_back"),
      mutualFollowersCount = rs.getInt("mutual_followers_count"),
      recentModerationHits = rs.getInt("recent_moderation_hits"),
      behaviorAffinityRaw = rs.getDouble("behavior_affinity_raw")
    )
