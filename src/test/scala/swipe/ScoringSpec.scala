package swipe

import munit.FunSuite
import java.time.Instant

class ScoringSpec extends FunSuite:
  private def candidate(
    mutualFollowers: Int = 0,
    adamicAdar: Double = 0.0,
    followsMeBack: Boolean = false,
    hasConversation: Boolean = false,
    followers: Int = 100,
    verified: Boolean = false,
    moderationHits: Int = 0,
    engagementDirect: Double = 0.0,
    cfPeerCount: Int = 0,
    avgEngagementPerTweet: Double = 0.0,
    bio: Option[String] = None,
    city: Option[String] = None,
    hashtags: Set[String] = Set.empty,
    lastActivity: Option[Instant] = Some(Instant.now())
  ): CandidateRow = CandidateRow(
    id = "candidate-1",
    username = "cand",
    fullName = "Candidate One",
    avatar = "https://example.test/a.png",
    bio = bio,
    city = city,
    verified = verified,
    subscriptionTier = "free",
    followersCount = followers,
    followingCount = 10,
    algoVisibilityMultiplier = None,
    createdAt = Instant.now().minusSeconds(3600 * 24 * 200),
    lastActivity = lastActivity,
    followsMeBack = followsMeBack,
    mutualFollowersCount = mutualFollowers,
    adamicAdarScore = adamicAdar,
    recentModerationHits = moderationHits,
    engagementDirect = engagementDirect,
    cfPeerCount = cfPeerCount,
    avgEngagementPerTweet = avgEngagementPerTweet,
    hasConversation = hasConversation,
    hashtags = hashtags
  )

  private val self = SelfProfile(bio = Some("passionné de tech et de café"), city = Some("Paris"), hashtags = Set("#scala", "#twitninf"))
  private val now = Instant.now()

  test("un candidat qui suit déjà l'utilisateur obtient un D1 nettement supérieur") {
    val plain = Scoring.dimensions(self, candidate(), now)
    val reciprocal = Scoring.dimensions(self, candidate(followsMeBack = true), now)
    assert(reciprocal.d1 > plain.d1)
  }

  test("une conversation existante fait monter le D1") {
    val none = Scoring.dimensions(self, candidate(), now)
    val withConvo = Scoring.dimensions(self, candidate(hasConversation = true), now)
    assert(withConvo.d1 > none.d1)
  }

  test("un indice d'Adamic-Adar plus élevé fait monter le D1") {
    val weak = Scoring.dimensions(self, candidate(adamicAdar = 0.1), now)
    val strong = Scoring.dimensions(self, candidate(adamicAdar = 1.5), now)
    assert(strong.d1 > weak.d1)
  }

  test("des hashtags partagés font monter le D2 plus qu'un simple recouvrement de bio") {
    val unrelated = Scoring.dimensions(self, candidate(bio = Some("photographie animalière"), hashtags = Set("#photo")), now)
    val sharedTags = Scoring.dimensions(self, candidate(bio = Some("photographie animalière"), hashtags = Set("#scala", "#twitninf")), now)
    assert(sharedTags.d2 > unrelated.d2)
  }

  test("une ville identique fait monter le D2") {
    val elsewhere = Scoring.dimensions(self, candidate(city = Some("Lyon")), now)
    val same = Scoring.dimensions(self, candidate(city = Some("paris")), now)
    assert(same.d2 > elsewhere.d2)
  }

  test("l'engagement direct réel fait monter le D3") {
    val none = Scoring.dimensions(self, candidate(engagementDirect = 0.0), now)
    val engaged = Scoring.dimensions(self, candidate(engagementDirect = 15.0), now)
    assert(engaged.d3 > none.d3)
  }

  test("le filtrage collaboratif (pairs aux goûts similaires) fait monter le D3") {
    val none = Scoring.dimensions(self, candidate(cfPeerCount = 0), now)
    val withPeers = Scoring.dimensions(self, candidate(cfPeerCount = 8), now)
    assert(withPeers.d3 > none.d3)
  }

  test("un taux d'engagement réel plus élevé fait monter le D4, à popularité égale") {
    val deadAccount = Scoring.dimensions(self, candidate(followers = 500, avgEngagementPerTweet = 0.0), now)
    val engagingAccount = Scoring.dimensions(self, candidate(followers = 500, avgEngagementPerTweet = 20.0), now)
    assert(engagingAccount.d4 > deadAccount.d4)
  }

  test("une pénalité de modération récente fait baisser le D4") {
    val clean = Scoring.dimensions(self, candidate(followers = 500), now)
    val flagged = Scoring.dimensions(self, candidate(followers = 500, moderationHits = 2), now)
    assert(flagged.d4 < clean.d4)
  }

  test("un compte inactif depuis longtemps obtient un D5 plus faible qu'un compte actif") {
    val active = Scoring.dimensions(self, candidate(lastActivity = Some(now)), now)
    val dormant = Scoring.dimensions(self, candidate(lastActivity = Some(now.minusSeconds(3600 * 24 * 90))), now)
    assert(active.d5 > dormant.d5)
  }

  test("toutes les dimensions restent dans [0, 1] même aux extrêmes") {
    val d = Scoring.dimensions(
      self,
      candidate(
        mutualFollowers = 50, adamicAdar = 999.0, followsMeBack = true, hasConversation = true,
        followers = 5_000_000, verified = true, engagementDirect = 999.0, cfPeerCount = 999,
        avgEngagementPerTweet = 99999.0, hashtags = self.hashtags
      ),
      now
    )
    List(d.d1, d.d2, d.d3, d.d4, d.d5).foreach { v =>
      assert(v >= 0.0 && v <= 1.0, s"dimension hors bornes: $v")
    }
  }

  test("le score pondéré par défaut reste dans [0, 1]") {
    val d = Scoring.dimensions(self, candidate(adamicAdar = 0.3), now)
    val score = Scoring.weightedScore(d, AlgoWeights.default)
    assert(score >= 0.0 && score <= 1.0)
  }

  test("diversify retient les candidats les mieux notés dans la limite demandée") {
    val rows = (1 to 5).map(i => candidate().copy(id = s"c$i")).toList
    val scored = rows.zipWithIndex.map((c, i) => (c, i.toDouble / 10, List.empty[String]))
    val out = Scoring.diversify(scored, limit = 3)
    assertEquals(out.size, 3)
  }
