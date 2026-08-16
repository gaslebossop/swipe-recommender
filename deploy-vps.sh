#!/usr/bin/env bash
#
# Déploiement de swipe-recommender sur le VPS.
#
# Contrairement à rust-recommender, ce service produit un jar JVM portable :
# pas besoin de compiler SUR le VPS (il a déjà OpenJDK 21). Ce script compile
# en LOCAL (nécessite `sbt` installé sur ce poste) puis envoie juste le jar.
# Si `sbt` n'est pas disponible en local, utiliser plutôt
# `.github/workflows/deploy.yml` (workflow_dispatch) : il fait le même travail
# depuis un runner GitHub Actions, sans rien exiger du poste local.
#
# Usage :
#   ./deploy-vps.sh              # build + envoi + redémarrage + contrôles
#   ./deploy-vps.sh --check      # contrôles seuls, ne touche à rien
#   ./deploy-vps.sh --bootstrap  # (première fois) crée l'unité systemd,
#                                 # en réutilisant DB_*/REDIS_*/INTERNAL_SECRET
#                                 # déjà présents sur l'unité rust-recommender
#
set -euo pipefail

VPS_HOST="${VPS_HOST:-debian@51.210.11.74}"
VPS_KEY="${VPS_KEY:-C:\\Users\\nouno\\OneDrive\\Bureau\\Documents\\privatessh}"
REMOTE_DIR="${REMOTE_DIR:-/home/debian/swipe-recommender}"
SERVICE="${SERVICE:-swipe-recommender}"
HEALTH_URL="http://127.0.0.1:3003/health"

ssh_vps() { ssh -i "$VPS_KEY" -o StrictHostKeyChecking=accept-new "$VPS_HOST" "$@"; }
scp_vps() { scp -i "$VPS_KEY" -o StrictHostKeyChecking=accept-new "$@"; }

log() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

check_health() {
  log "Contrôle de santé"
  ssh_vps "ss -tlnp 2>/dev/null | grep -q ':3003' || { echo 'rien n écoute sur 3003'; exit 1; }"

  local health
  health="$(ssh_vps "curl -s --max-time 5 '$HEALTH_URL'")" || die "/health injoignable"
  echo "$health"
  case "$health" in
    *'"db":"ok"'*) ;;
    *) die "base injoignable depuis le service" ;;
  esac
  case "$health" in
    *'"redis":"ok"'*) ;;
    *) die "redis injoignable depuis le service" ;;
  esac

  local restarts
  restarts="$(ssh_vps "systemctl show '$SERVICE' -p NRestarts --value")"
  log "NRestarts = ${restarts}"
  [ "$restarts" = "0" ] || printf '\033[1;33m⚠ le service a redémarré %s fois — vérifier les journaux\033[0m\n' "$restarts"
}

if [ "${1:-}" = "--check" ]; then
  check_health
  exit 0
fi

cd "$(dirname "$0")"

if [ "${1:-}" = "--bootstrap" ]; then
  # Récupère les Environment= déjà en place sur rust-recommender (DB_*,
  # REDIS_*, INTERNAL_SECRET) plutôt que d'en faire fuiter de nouveaux : ce
  # dépôt est public, et ces valeurs ne doivent JAMAIS y apparaître.
  log "Bootstrap de l'unité systemd $SERVICE (réutilise les identifiants de rust-recommender)"
  ssh_vps "systemctl show rust-recommender -p Environment --value" > /tmp/swipe-env.$$ \
    || die "impossible de lire l'environnement de rust-recommender"
  ENV_LINES="$(tr ' ' '\n' < /tmp/swipe-env.$$ | grep -E '^(DB_|REDIS_|INTERNAL_SECRET)' | sed 's/^/Environment=/')"
  rm -f /tmp/swipe-env.$$
  [ -n "$ENV_LINES" ] || die "aucune variable DB_*/REDIS_*/INTERNAL_SECRET trouvée sur rust-recommender"

  UNIT_CONTENT="$(cat <<EOF
[Unit]
Description=twitninf swipe-recommender
After=network.target postgresql.service redis-server.service

[Service]
Type=simple
WorkingDirectory=$REMOTE_DIR
ExecStart=/usr/bin/java -Xmx512m -jar $REMOTE_DIR/swipe-recommender.jar
$ENV_LINES
Environment=PORT=3003
Restart=always
RestartSec=5
User=debian

[Install]
WantedBy=multi-user.target
EOF
)"
  printf '%s\n' "$UNIT_CONTENT" | ssh_vps "sudo tee /etc/systemd/system/$SERVICE.service > /dev/null"
  ssh_vps "sudo systemctl daemon-reload && sudo systemctl enable $SERVICE"
  log "Unité créée — relancer ./deploy-vps.sh (sans --bootstrap) pour envoyer le jar et démarrer"
  exit 0
fi

log "Tests locaux"
sbt -batch test

log "Empaquetage (sbt assembly)"
sbt -batch assembly

JAR_PATH="$(find target -name 'swipe-recommender.jar' | head -1)"
[ -n "$JAR_PATH" ] || die "jar introuvable après sbt assembly"

log "Envoi du jar vers $VPS_HOST:$REMOTE_DIR"
ssh_vps "mkdir -p '$REMOTE_DIR'"
scp_vps "$JAR_PATH" "$VPS_HOST:$REMOTE_DIR/swipe-recommender.jar"

log "Redémarrage de $SERVICE"
ssh_vps "sudo systemctl restart '$SERVICE'"

sleep 5
check_health

log "Déploiement terminé"
