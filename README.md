# swipe-recommender

Microservice de classement pour la fonctionnalité **Swipe or Follow** de
twitninf : à partir d'un utilisateur, renvoie une file de comptes à
suggérer, classés par un modèle de dimensions pondérées (graphe social,
intérêts, comportement, qualité, fraîcheur), avec mise en cache Redis et
gestion d'un cooldown sur les profils passés ("pass").

Même famille d'architecture que
[twitninf-rust-recommender](https://github.com/) : microservice interne,
lecture seule sur la base Postgres de production, appelé depuis l'API Node
via un header `X-Service-Key` partagé. Contrairement au recommandeur Rust,
ce service **n'a pas besoin d'être compilé sur le VPS** : il produit un jar
portable (`sbt assembly`) exécutable avec n'importe quel JDK 17+, et le VPS
a déjà OpenJDK 21 installé.

## Stack

- Scala 3 / sbt
- [http4s](https://http4s.org/) (ember-server) pour le HTTP
- [circe](https://circe.github.io/circe/) pour le JSON (codecs manuels,
  snake_case, pas de dérivation générique)
- JDBC brut (`org.postgresql:postgresql`) pour la lecture Postgres — pas
  d'ORM, seulement des `SELECT`, jamais d'écriture
- [Jedis](https://github.com/redis/jedis) pour Redis (file de candidats en
  cache, cooldown des "pass")

## Configuration (variables d'environnement)

| Variable | Description | Défaut |
|---|---|---|
| `PORT` | Port d'écoute HTTP | `3003` |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | Connexion Postgres | `DB_PASSWORD` obligatoire |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Connexion Redis | `REDIS_PASSWORD` optionnel |
| `INTERNAL_SECRET` | Secret partagé attendu dans `X-Service-Key` | obligatoire |

Ce service réutilise volontairement les **mêmes** `DB_*`/`REDIS_*`/
`INTERNAL_SECRET` que `rust-recommender` sur le VPS — pas de nouveau secret
à générer ni à faire fuiter.

## Endpoints

- `GET /health` → `{status, db, redis}`
- `POST /swipe/candidates` `{user_id, limit?, force_refresh?}` → file de
  candidats classés (servie depuis le cache Redis sauf `force_refresh`)
- `POST /swipe/action` `{user_id, target_user_id, action: "pass"|"follow"}`
  → met à jour le cache (cooldown 21 jours sur un `pass`) ; **le follow réel
  reste géré côté API Node** (`POST /api/users/:id/follow`), ce service ne
  fait que retirer le candidat de la file mise en cache
- `POST /swipe/invalidate` `{user_id}` → vide la file en cache

Toutes les routes `/swipe/*` exigent l'en-tête `X-Service-Key`.

## Poids de l'algorithme

Les poids par défaut des 5 dimensions sont codés en dur
(`AlgoWeights.default` dans `Models.scala`) mais peuvent être surchargés
sans redéploiement via la clé Redis `swipe:algo:weights` (JSON avec les
clés `d1_social`, `d2_interests`, `d3_behavior`, `d4_quality`,
`d5_freshness`), même mécanique que `admin:algo:weights` côté
rust-recommender.

## Développement local

```bash
sbt test        # tests unitaires (ScoringSpec)
sbt run         # démarre le service (nécessite Postgres + Redis accessibles)
sbt assembly    # produit target/scala-3.3.4/swipe-recommender.jar
```

## Déploiement

Voir `deploy-vps.sh` (déploiement manuel depuis un poste avec `sbt`
installé) et `.github/workflows/deploy.yml` (déploiement via GitHub
Actions, `workflow_dispatch`, ne nécessite aucun outillage local — c'est le
chemin recommandé). `swipe-recommender.service.example` est le modèle
d'unité systemd à adapter sur le VPS.
