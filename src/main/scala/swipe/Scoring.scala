package swipe

import java.time.Instant
import scala.math.{log10, max, min}

/** Modèle de scoring — 5 dimensions pondérées + un rerank de diversité,
  * même famille architecturale que `rust-recommender/src/algorithm/scoring.rs`
  * mais pour du classement utilisateur→utilisateur.
  *
  * Volontairement déterministe, pas un modèle entraîné : la base réelle de
  * follows humains est trop petite pour entraîner quoi que ce soit
  * sérieusement (voir la mémoire twitninf-volumetrie-reelle). Les poids
  * restent overridables via Redis (`Cache.weights`) pour un tuning futur
  * sans redéploiement.
  */
object Scoring:
  private val stopwords: Set[String] = Set(
    "le", "la", "les", "de", "des", "du", "un", "une", "et", "en", "je", "tu", "il", "elle", "nous", "vous",
    "ils", "elles", "que", "qui", "pour", "dans", "sur", "avec", "pas", "ce", "cette", "ces", "mon", "ma",
    "mes", "ton", "ta", "tes", "son", "sa", "ses", "au", "aux", "est", "suis", "es", "sommes", "sont", "ne",
    "plus", "the", "and", "of", "is", "to", "in"
  )

  private def tokenize(text: String): Set[String] =
    text.toLowerCase
      .split("[^\\p{L}0-9#@]+")
      .iterator
      .map(_.trim)
      .filter(t => t.length >= 3 && !stopwords.contains(t))
      .toSet

  private def jaccard(a: Set[String], b: Set[String]): Double =
    if a.isEmpty || b.isEmpty then 0.0
    else
      val inter = (a intersect b).size.toDouble
      val union = (a union b).size.toDouble
      if union == 0.0 then 0.0 else inter / union

  // D1 — graphe social : réciprocité (le candidat me suit déjà, "quick win"
  // classique des suggestions "who to follow") + connexions communes.
  private def d1SocialGraph(c: CandidateRow): (Double, List[String]) =
    val mutual = c.mutualFollowersCount
    val mutualScore = min(1.0, mutual / 5.0)
    val reciprocityBonus = if c.followsMeBack then 0.6 else 0.0
    val score = min(1.0, mutualScore * 0.7 + reciprocityBonus)
    val reasons = List.newBuilder[String]
    if c.followsMeBack then reasons += "Vous suit déjà"
    if mutual > 0 then reasons += s"$mutual abonné${if mutual > 1 then "s" else ""} en commun"
    (score, reasons.result())

  // D2 — recouvrement d'intérêts (tokens de bio) + ville identique.
  private def d2Interests(self: SelfProfile, c: CandidateRow): (Double, List[String]) =
    val selfTokens = tokenize(self.bio.getOrElse(""))
    val candTokens = tokenize(c.bio.getOrElse(""))
    val bioScore = jaccard(selfTokens, candTokens)
    val sameCity = self.city.exists(sc => c.city.exists(_.equalsIgnoreCase(sc)))
    val cityBonus = if sameCity then 0.3 else 0.0
    val score = min(1.0, bioScore + cityBonus)
    val reasons = List.newBuilder[String]
    if sameCity then reasons += c.city.fold("Même ville")(v => s"Aussi à $v")
    if bioScore > 0.15 then reasons += "Centres d'intérêt proches"
    (score, reasons.result())

  // D3 — affinité comportementale : ai-je déjà vu/liké du contenu de ce
  // candidat (voir Db.candidatePool, mêmes poids que userSimilarityService.js).
  private def d3Behavior(c: CandidateRow): (Double, List[String]) =
    val score = min(1.0, log10(1.0 + c.behaviorAffinityRaw) / log10(21.0))
    val reasons = if c.behaviorAffinityRaw >= 3.0 then List("Vous interagissez déjà avec ses tweets") else Nil
    (score, reasons)

  // D4 — qualité/popularité, pénalisée par la modération récente. Ne compte
  // que ban/suspend/warn actifs — pas approve/reject, qui sont des décisions
  // de contenu et non des sanctions (piège documenté : neuralrank-recommender).
  private def d4Quality(c: CandidateRow): (Double, List[String]) =
    val popularity = min(1.0, log10(1.0 + c.followersCount) / log10(5001.0))
    val verifiedBonus = if c.verified then 0.15 else 0.0
    val subBonus = c.subscriptionTier match
      case "pro"  => 0.06
      case "plus" => 0.03
      case _      => 0.0
    val visibilityMult = c.algoVisibilityMultiplier.getOrElse(1.0)
    val moderationPenalty = min(1.0, c.recentModerationHits * 0.4)
    val raw = (popularity * 0.7 + verifiedBonus + subBonus) * visibilityMult
    val score = max(0.0, min(1.0, raw) - moderationPenalty)
    val reasons = if c.verified then List("Compte certifié") else Nil
    (score, reasons)

  // D5 — fraîcheur : pénalise les comptes dormants, léger boost cold-start
  // pour un compte récent (diversité, pas de biais 100% vers les gros comptes).
  private def d5Freshness(c: CandidateRow, now: Instant): Double =
    val daysSinceActivity = c.lastActivity
      .map(la => (now.getEpochSecond - la.getEpochSecond) / 86400.0)
      .getOrElse(9999.0)
    val activityScore = max(0.0, 1.0 - daysSinceActivity / 30.0)
    val ageDays = (now.getEpochSecond - c.createdAt.getEpochSecond) / 86400.0
    val coldStartBoost = if ageDays >= 0 && ageDays < 14 then 0.2 else 0.0
    min(1.0, activityScore * 0.8 + coldStartBoost)

  final case class Dimensions(d1: Double, d2: Double, d3: Double, d4: Double, d5: Double, reasons: List[String])

  def dimensions(self: SelfProfile, c: CandidateRow, now: Instant): Dimensions =
    val (s1, r1) = d1SocialGraph(c)
    val (s2, r2) = d2Interests(self, c)
    val (s3, r3) = d3Behavior(c)
    val (s4, r4) = d4Quality(c)
    val s5 = d5Freshness(c, now)
    Dimensions(s1, s2, s3, s4, s5, (r1 ++ r2 ++ r3 ++ r4).distinct.take(3))

  def weightedScore(d: Dimensions, w: AlgoWeights): Double =
    d.d1 * w.d1Social + d.d2 * w.d2Interests + d.d3 * w.d3Behavior + d.d4 * w.d4Quality + d.d5 * w.d5Freshness

  private def roundScore(s: Double): Double = math.round(max(0.0, s) * 1000) / 1000.0

  private def toScoredCandidate(c: CandidateRow, score: Double, reasons: List[String]): ScoredCandidate =
    ScoredCandidate(
      id = c.id,
      username = c.username,
      fullName = c.fullName,
      avatar = c.avatar,
      bio = c.bio,
      city = c.city,
      verified = c.verified,
      followersCount = c.followersCount,
      score = roundScore(score),
      reasons = if reasons.isEmpty then List("Suggéré pour vous") else reasons
    )

  /** D6 — diversité intra-lot : MMR léger. Parcourt les candidats déjà
    * classés par score et pénalise ceux trop proches (recouvrement de tokens
    * de bio) d'un candidat déjà retenu plus haut, pour éviter une file de
    * profils redondants. Approximation volontairement simple (une seule
    * passe, pas de re-tri glouton à chaque étape) — "léger" au sens du plan,
    * pas un MMR complet.
    */
  def diversify(scored: List[(CandidateRow, Double, List[String])], limit: Int): List[ScoredCandidate] =
    val tokensById = scored.map((c, _, _) => c.id -> tokenize(c.bio.getOrElse(""))).toMap
    var remaining = scored.sortBy((_, s, _) => -s)
    val chosen = scala.collection.mutable.ListBuffer.empty[(CandidateRow, Double, List[String])]
    while remaining.nonEmpty && chosen.size < limit do
      val (c, s, r) = remaining.head
      remaining = remaining.tail
      val maxSim =
        if chosen.isEmpty then 0.0
        else chosen.map((cc, _, _) => jaccard(tokensById(c.id), tokensById(cc.id))).max
      chosen += ((c, s - maxSim * 0.15, r))
    chosen.toList.sortBy((_, s, _) => -s).map((c, s, r) => toScoredCandidate(c, s, r))
