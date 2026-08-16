package swipe

import java.time.Instant
import scala.math.{log10, max, min}

/** Modèle de scoring — 5 dimensions pondérées + un rerank de diversité,
  * même famille architecturale que `rust-recommender/src/algorithm/scoring.rs`
  * mais pour du classement utilisateur→utilisateur.
  *
  * Volontairement déterministe, pas un modèle entraîné : la base réelle de
  * follows humains est trop petite pour entraîner quoi que ce soit
  * sérieusement (voir la mémoire twitninf-volumetrie-reelle). En revanche
  * chaque dimension s'appuie sur des techniques éprouvées de recommandation
  * de lien plutôt que sur des heuristiques ad hoc :
  *
  *  - D1 utilise l'indice d'Adamic-Adar (Adamic & Adar, 2003) sur le graphe
  *    de suivi — la même famille de signal que le "Who To Follow" historique
  *    de Twitter (SALSA sur un cercle de confiance) : une connexion commune
  *    qui suit peu de monde compte bien plus qu'une connexion commune qui
  *    suit des milliers de comptes.
  *  - D3 combine l'engagement RÉEL (tables tweet_likes/tweet_retweets, pas
  *    le tracking comportemental, sparse côté web) et un signal de
  *    filtrage collaboratif basé utilisateur : "les comptes qui aiment ce
  *    que vous aimez suivent aussi ce candidat".
  *
  * Les poids restent overridables via Redis (`Cache.weights`) pour un
  * tuning futur sans redéploiement.
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

  // D1 — graphe social : indice d'Adamic-Adar (connexions communes
  // pondérées par l'inverse de LEUR degré de sortie, pas un compte brut) +
  // réciprocité (le candidat me suit déjà) + relation déjà établie (une
  // conversation existe).
  private def d1SocialGraph(c: CandidateRow): (Double, List[String]) =
    val aaScore = min(1.0, c.adamicAdarScore / 2.5)
    val reciprocityBonus = if c.followsMeBack then 0.5 else 0.0
    val conversationBonus = if c.hasConversation then 0.3 else 0.0
    val score = min(1.0, aaScore * 0.6 + reciprocityBonus + conversationBonus)
    val reasons = List.newBuilder[String]
    if c.followsMeBack then reasons += "Vous suit déjà"
    if c.hasConversation then reasons += "Vous vous êtes déjà écrit"
    if c.mutualFollowersCount > 0 then
      reasons += s"${c.mutualFollowersCount} abonné${if c.mutualFollowersCount > 1 then "s" else ""} en commun"
    (score, reasons.result())

  // D2 — recouvrement d'intérêts : hashtags utilisés (signal fort, structuré)
  // en priorité, bio en appoint (beaucoup de comptes n'en ont pas), ville
  // identique en petit bonus.
  private def d2Interests(self: SelfProfile, c: CandidateRow): (Double, List[String]) =
    val hashtagScore = jaccard(self.hashtags, c.hashtags)
    val bioScore = jaccard(tokenize(self.bio.getOrElse("")), tokenize(c.bio.getOrElse("")))
    val sameCity = self.city.exists(sc => c.city.exists(_.equalsIgnoreCase(sc)))
    val cityBonus = if sameCity then 0.3 else 0.0
    val score = min(1.0, hashtagScore * 0.7 + bioScore * 0.2 + cityBonus)
    val reasons = List.newBuilder[String]
    if sameCity then reasons += c.city.fold("Même ville")(v => s"Aussi à $v")
    if hashtagScore > 0.1 then reasons += "Intérêts communs"
    else if bioScore > 0.15 then reasons += "Centres d'intérêt proches"
    (score, reasons.result())

  // D3 — affinité comportementale : engagement direct réel (j'ai aimé/repris/
  // répondu à son contenu) + filtrage collaboratif (des comptes qui aiment
  // ce que j'aime suivent ce candidat) — deux signaux distincts, combinés.
  private def d3Behavior(c: CandidateRow): (Double, List[String]) =
    val directScore = min(1.0, log10(1.0 + c.engagementDirect) / log10(21.0))
    val cfScore = min(1.0, log10(1.0 + c.cfPeerCount) / log10(11.0))
    val score = min(1.0, directScore * 0.7 + cfScore * 0.3)
    val reasons = List.newBuilder[String]
    if c.engagementDirect >= 3.0 then reasons += "Vous interagissez déjà avec ses tweets"
    if c.cfPeerCount >= 2 then reasons += "Apprécié par des comptes aux goûts similaires aux vôtres"
    (score, reasons.result())

  // D4 — qualité/popularité : autant l'ampleur (abonnés) que la RÉALITÉ de
  // l'engagement (moyenne likes+retweets/tweet) — un gros compte inactif ne
  // doit pas dominer un petit compte qui engage vraiment. Pénalisé par la
  // modération récente (ban/suspend/warn actifs uniquement, jamais
  // approve/reject qui sont des décisions de contenu — piège documenté :
  // neuralrank-recommender).
  private def d4Quality(c: CandidateRow): (Double, List[String]) =
    val popularity = min(1.0, log10(1.0 + c.followersCount) / log10(5001.0))
    val engagementRate = min(1.0, log10(1.0 + c.avgEngagementPerTweet) / log10(51.0))
    val verifiedBonus = if c.verified then 0.15 else 0.0
    val subBonus = c.subscriptionTier match
      case "pro"  => 0.06
      case "plus" => 0.03
      case _      => 0.0
    val visibilityMult = c.algoVisibilityMultiplier.getOrElse(1.0)
    val moderationPenalty = min(1.0, c.recentModerationHits * 0.4)
    val raw = (popularity * 0.4 + engagementRate * 0.4 + verifiedBonus + subBonus) * visibilityMult
    val score = max(0.0, min(1.0, raw) - moderationPenalty)
    val reasons = List.newBuilder[String]
    if c.verified then reasons += "Compte certifié"
    if c.avgEngagementPerTweet >= 5.0 then reasons += "Contenu qui engage"
    (score, reasons.result())

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
    * classés par score et pénalise ceux trop proches (recouvrement de
    * hashtags — signal structuré, plus fiable qu'un recouvrement de tokens
    * de bio) d'un candidat déjà retenu plus haut, pour éviter une file de
    * profils redondants. Approximation volontairement simple (une seule
    * passe, pas de re-tri glouton à chaque étape) — "léger" au sens du
    * plan, pas un MMR complet.
    */
  def diversify(scored: List[(CandidateRow, Double, List[String])], limit: Int): List[ScoredCandidate] =
    var remaining = scored.sortBy((_, s, _) => -s)
    val chosen = scala.collection.mutable.ListBuffer.empty[(CandidateRow, Double, List[String])]
    while remaining.nonEmpty && chosen.size < limit do
      val (c, s, r) = remaining.head
      remaining = remaining.tail
      val maxSim =
        if chosen.isEmpty then 0.0
        else chosen.map((cc, _, _) => jaccard(c.hashtags, cc.hashtags)).max
      chosen += ((c, s - maxSim * 0.15, r))
    chosen.toList.sortBy((_, s, _) => -s).map((c, s, r) => toScoredCandidate(c, s, r))
