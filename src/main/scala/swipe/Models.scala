package swipe

import io.circe.{Decoder, Encoder}
import java.time.Instant

/** Ligne brute issue de `Db.candidatePool` : un candidat avec tous les
  * signaux nécessaires au scoring, avant tout calcul de dimension.
  */
final case class CandidateRow(
  id: String,
  username: String,
  fullName: String,
  avatar: String,
  bio: Option[String],
  city: Option[String],
  verified: Boolean,
  subscriptionTier: String,
  followersCount: Int,
  followingCount: Int,
  algoVisibilityMultiplier: Option[Double],
  createdAt: Instant,
  lastActivity: Option[Instant],
  followsMeBack: Boolean,
  mutualFollowersCount: Int,
  /** Indice d'Adamic-Adar sur le graphe de suivi (voir Db.scala) — connexions
    * communes pondérées par l'inverse du degré de sortie de la connexion,
    * pas un simple compte brut. */
  adamicAdarScore: Double,
  recentModerationHits: Int,
  /** Likes/retweets/réponses RÉELS que j'ai adressés à son contenu (tables
    * tweet_likes/tweet_retweets/tweets), pas des événements de tracking. */
  engagementDirect: Double,
  /** Filtrage collaboratif : combien d'autres comptes ayant aimé les mêmes
    * tweets que moi suivent aussi ce candidat. */
  cfPeerCount: Int,
  /** Engagement moyen (likes+retweets) sur ses 50 derniers tweets — mesure
    * la qualité réelle du compte, pas seulement son nombre d'abonnés. */
  avgEngagementPerTweet: Double,
  /** Une conversation existe déjà entre nous — relation réelle, indépendante
    * du graphe de suivi. */
  hasConversation: Boolean,
  hashtags: Set[String]
)

final case class SelfProfile(bio: Option[String], city: Option[String], hashtags: Set[String])

/** Candidat scoré, tel que renvoyé au client (Node) et mis en cache dans Redis. */
final case class ScoredCandidate(
  id: String,
  username: String,
  fullName: String,
  avatar: String,
  bio: Option[String],
  city: Option[String],
  verified: Boolean,
  followersCount: Int,
  score: Double,
  reasons: List[String]
)

object ScoredCandidate:
  // Codecs manuels en snake_case : le client Node/mobile de ce service
  // parle snake_case partout ailleurs (voir rustRecommenderClient.js) —
  // pas de dérivation générique circe pour ne pas dépendre d'un module
  // supplémentaire (circe-generic-extras) juste pour un renommage de champs.
  given Encoder[ScoredCandidate] = Encoder.forProduct10(
    "id", "username", "full_name", "avatar", "bio", "city", "verified", "followers_count", "score", "reasons"
  )(c => (c.id, c.username, c.fullName, c.avatar, c.bio, c.city, c.verified, c.followersCount, c.score, c.reasons))

  given Decoder[ScoredCandidate] = Decoder.forProduct10(
    "id", "username", "full_name", "avatar", "bio", "city", "verified", "followers_count", "score", "reasons"
  )(ScoredCandidate.apply)

final case class CandidatesRequest(userId: String, limit: Int, forceRefresh: Boolean)
object CandidatesRequest:
  given Decoder[CandidatesRequest] = Decoder.instance { c =>
    for
      userId <- c.get[String]("user_id")
      limit  <- c.getOrElse[Int]("limit")(20)
      force  <- c.getOrElse[Boolean]("force_refresh")(false)
    yield CandidatesRequest(userId, limit, force)
  }

final case class CandidatesResponse(success: Boolean, data: List[ScoredCandidate], cached: Boolean)
object CandidatesResponse:
  given Encoder[CandidatesResponse] =
    Encoder.forProduct3("success", "data", "cached")(r => (r.success, r.data, r.cached))

final case class ActionRequest(userId: String, targetUserId: String, action: String)
object ActionRequest:
  given Decoder[ActionRequest] = Decoder.instance { c =>
    for
      userId <- c.get[String]("user_id")
      target <- c.get[String]("target_user_id")
      action <- c.get[String]("action")
    yield ActionRequest(userId, target, action)
  }

final case class InvalidateRequest(userId: String)
object InvalidateRequest:
  given Decoder[InvalidateRequest] = Decoder.instance(_.get[String]("user_id").map(InvalidateRequest.apply))

final case class SimpleResponse(success: Boolean, message: Option[String] = None)
object SimpleResponse:
  given Encoder[SimpleResponse] = Encoder.forProduct2("success", "message")(r => (r.success, r.message))

final case class HealthResponse(status: String, db: String, redis: String)
object HealthResponse:
  given Encoder[HealthResponse] = Encoder.forProduct3("status", "db", "redis")(h => (h.status, h.db, h.redis))

/** Poids des 5 dimensions du score final. Somme = 1.0 par défaut.
  * Overridable sans redéploiement via la clé Redis `swipe:algo:weights`
  * (même mécanique que `admin:algo:weights` côté rust-recommender).
  */
final case class AlgoWeights(
  d1Social: Double = 0.30,
  d2Interests: Double = 0.15,
  d3Behavior: Double = 0.20,
  d4Quality: Double = 0.20,
  d5Freshness: Double = 0.15
)
object AlgoWeights:
  given Decoder[AlgoWeights] = Decoder.instance { c =>
    for
      d1 <- c.getOrElse[Double]("d1_social")(0.30)
      d2 <- c.getOrElse[Double]("d2_interests")(0.15)
      d3 <- c.getOrElse[Double]("d3_behavior")(0.20)
      d4 <- c.getOrElse[Double]("d4_quality")(0.20)
      d5 <- c.getOrElse[Double]("d5_freshness")(0.15)
    yield AlgoWeights(d1, d2, d3, d4, d5)
  }
  val default: AlgoWeights = AlgoWeights()
