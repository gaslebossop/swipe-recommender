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

  private def textArray(rs: ResultSet, col: String): Set[String] =
    val arr = rs.getArray(col)
    if arr == null then Set.empty
    else arr.getArray.asInstanceOf[Array[AnyRef]].iterator.map(_.toString).toSet

  private val hashtagsSql =
    """
      |SELECT DISTINCT tag
      |FROM tweets t
      |CROSS JOIN LATERAL jsonb_array_elements_text(t.hashtags) AS tag
      |WHERE t.user_id = ?::uuid
      |LIMIT 300
      |""".stripMargin

  def selfProfile(userId: String): IO[SelfProfile] =
    withConnection { conn =>
      val ps = conn.prepareStatement("SELECT bio, city FROM users WHERE id = ?::uuid")
      val (bio, city) =
        try
          ps.setString(1, userId)
          val rs = ps.executeQuery()
          try
            if rs.next() then (Option(rs.getString("bio")), Option(rs.getString("city")))
            else (None, None)
          finally rs.close()
        finally ps.close()

      val hashtags = scala.collection.mutable.Set.empty[String]
      val hps = conn.prepareStatement(hashtagsSql)
      try
        hps.setString(1, userId)
        val hrs = hps.executeQuery()
        try while hrs.next() do hashtags += hrs.getString("tag")
        finally hrs.close()
      finally hps.close()

      SelfProfile(bio, city, hashtags.toSet)
    }

  // Filtres durs déjà appliqués ici (hors base : suspendu/inactif/déjà en
  // relation) — le cooldown "pass" (Redis) est filtré séparément par Routes,
  // il ne concerne pas Postgres.
  //
  // Les signaux ci-dessous s'appuient sur les tables de vérité (tweet_likes,
  // tweet_retweets, conversation_participants) plutôt que sur
  // user_behavior_data : ce dernier dépend du client et est quasi vide côté
  // web (voir mémoire neuralrank-recommender — 51 comptes web sur 52
  // n'émettent aucun événement). Le graphe social utilise un indice
  // d'Adamic-Adar (connexions communes pondérées par l'inverse de leur
  // degré de sortie) plutôt qu'un simple compte brut — technique standard
  // de prédiction de lien (Adamic & Adar 2003), même famille que le "Who To
  // Follow" historique de Twitter.
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
      |       COALESCE((
      |         SELECT SUM(1.0 / LN(2 + COALESCE((common_u.stats->>'following')::numeric, 0)))
      |         FROM user_follows mf
      |         JOIN users common_u ON common_u.id = mf.following_id
      |         JOIN user_follows uf2 ON uf2.follower_id = common_u.id AND uf2.following_id = u.id AND uf2.status = 'active'
      |         WHERE mf.follower_id = ?::uuid AND mf.status = 'active'
      |       ), 0) AS adamic_adar_score,
      |       (
      |         SELECT COUNT(*) FROM moderation_actions ma
      |         WHERE ma.target_type = 'user' AND ma.target_id = u.id
      |           AND ma.type IN ('ban', 'suspend', 'warn') AND ma.status = 'active'
      |       ) AS recent_moderation_hits,
      |       COALESCE((
      |         SELECT COUNT(*) * 3.0 FROM tweet_likes tl JOIN tweets t1 ON t1.id = tl.tweet_id
      |         WHERE tl.user_id = ?::uuid AND t1.user_id = u.id
      |       ), 0)
      |       + COALESCE((
      |         SELECT COUNT(*) * 5.0 FROM tweet_retweets tr JOIN tweets t2 ON t2.id = tr.tweet_id
      |         WHERE tr.user_id = ?::uuid AND t2.user_id = u.id
      |       ), 0)
      |       + COALESCE((
      |         SELECT COUNT(*) * 4.0 FROM tweets rep JOIN tweets orig ON orig.id = rep.original_tweet_id
      |         WHERE rep.user_id = ?::uuid AND rep.tweet_type = 'reply' AND orig.user_id = u.id
      |       ), 0) AS engagement_direct,
      |       COALESCE((
      |         SELECT COUNT(DISTINCT peer.user_id)
      |         FROM tweet_likes mine
      |         JOIN tweet_likes peer ON peer.tweet_id = mine.tweet_id AND peer.user_id <> mine.user_id
      |         JOIN user_follows pf ON pf.follower_id = peer.user_id AND pf.following_id = u.id AND pf.status = 'active'
      |         WHERE mine.user_id = ?::uuid
      |       ), 0) AS cf_peer_count,
      |       COALESCE((
      |         SELECT AVG(
      |           (SELECT COUNT(*) FROM tweet_likes tl3 WHERE tl3.tweet_id = ct.id) +
      |           (SELECT COUNT(*) FROM tweet_retweets tr3 WHERE tr3.tweet_id = ct.id)
      |         )
      |         FROM (
      |           SELECT id FROM tweets WHERE user_id = u.id AND parent_tweet_id IS NULL
      |           ORDER BY created_at DESC LIMIT 50
      |         ) ct
      |       ), 0) AS avg_engagement_per_tweet,
      |       EXISTS(
      |         SELECT 1 FROM conversation_participants cp1
      |         JOIN conversation_participants cp2 ON cp2.conversation_id = cp1.conversation_id AND cp2.user_id = u.id
      |         WHERE cp1.user_id = ?::uuid
      |       ) AS has_conversation,
      |       (
      |         SELECT COALESCE(array_agg(DISTINCT tag), ARRAY[]::text[])
      |         FROM tweets ht
      |         CROSS JOIN LATERAL jsonb_array_elements_text(ht.hashtags) AS tag
      |         WHERE ht.user_id = u.id
      |       ) AS candidate_hashtags
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
        // 10 occurrences de userId dans la requête (voir l'ordre des `?`
        // ci-dessus), puis LIMIT.
        for i <- 1 to 10 do ps.setString(i, userId)
        ps.setInt(11, limit)
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
      adamicAdarScore = rs.getDouble("adamic_adar_score"),
      recentModerationHits = rs.getInt("recent_moderation_hits"),
      engagementDirect = rs.getDouble("engagement_direct"),
      cfPeerCount = rs.getInt("cf_peer_count"),
      avgEngagementPerTweet = rs.getDouble("avg_engagement_per_tweet"),
      hasConversation = rs.getBoolean("has_conversation"),
      hashtags = textArray(rs, "candidate_hashtags")
    )
