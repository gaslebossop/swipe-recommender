package swipe

import munit.FunSuite
import java.time.Instant

class ScoringSpec extends FunSuite:
  private def candidate(
    mutualFollowers: Int = 0,
    followsMeBack: Boolean = false,
    followers: Int = 100,
    verified: Boolean = false,
    moderationHits: Int = 0,
    behaviorAffinity: Double = 0.0,
    bio: Option[String] = None,
    city: Option[String] = None,
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
    recentModerationHits = moderationHits,
    behaviorAffinityRaw = behaviorAffinity
  )

  private val self = SelfProfile(bio = Some("passionné de tech et de café"), city = Some("Paris"))
  private val now = Instant.now()

  test("un candidat qui suit déjà l'utilisateur obtient un D1 nettement supérieur") {
    val plain = Scoring.dimensions(self, candidate(), now)
    val reciprocal = Scoring.dimensions(self, candidate(followsMeBack = true), now)
    assert(reciprocal.d1 > plain.d1)
  }

  test("des abonnés communs font monter le D1") {
    val none = Scoring.dimensions(self, candidate(mutualFollowers = 0), now)
    val some = Scoring.dimensions(self, candidate(mutualFollowers = 5), now)
    assert(some.d1 > none.d1)
  }

  test("une bio partagée fait monter le D2") {
    val unrelated = Scoring.dimensions(self, candidate(bio = Some("photographie animalière")), now)
    val related = Scoring.dimensions(self, candidate(bio = Some("tech et café tous les jours")), now)
    assert(related.d2 > unrelated.d2)
  }

  test("une ville identique fait monter le D2") {
    val elsewhere = Scoring.dimensions(self, candidate(city = Some("Lyon")), now)
    val same = Scoring.dimensions(self, candidate(city = Some("paris")), now)
    assert(same.d2 > elsewhere.d2)
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
      candidate(mutualFollowers = 50, followsMeBack = true, followers = 5_000_000, verified = true, behaviorAffinity = 999),
      now
    )
    List(d.d1, d.d2, d.d3, d.d4, d.d5).foreach { v =>
      assert(v >= 0.0 && v <= 1.0, s"dimension hors bornes: $v")
    }
  }

  test("le score pondéré par défaut reste dans [0, 1]") {
    val d = Scoring.dimensions(self, candidate(mutualFollowers = 3), now)
    val score = Scoring.weightedScore(d, AlgoWeights.default)
    assert(score >= 0.0 && score <= 1.0)
  }

  test("diversify retient les candidats les mieux notés dans la limite demandée") {
    val rows = (1 to 5).map(i => candidate().copy(id = s"c$i")).toList
    val scored = rows.zipWithIndex.map((c, i) => (c, i.toDouble / 10, List.empty[String]))
    val out = Scoring.diversify(scored, limit = 3)
    assertEquals(out.size, 3)
  }
