#!/usr/bin/env bash
# Deploy tekstiks-proxy to Google Cloud Run.
#
# Prerequisites (one-time, by a human):
#   1. Install the gcloud CLI:  https://cloud.google.com/sdk/docs/install
#   2. gcloud auth login
#   3. A billing account with a card: https://console.cloud.google.com/billing
#
# Then:  ./deploy.sh            (scales to zero: free, ~1-2 s boot after idle)
#        MIN_INSTANCES=1 ./deploy.sh   (always warm: no boot, ~$4-5/month)
#
# Safe to re-run: it reuses the project and keeps the existing token.

set -euo pipefail

SERVICE=tekstiks-proxy
REGION=${REGION:-europe-north1}          # Finland: closest Cloud Run region to tekstiks.ee
MIN_INSTANCES=${MIN_INSTANCES:-0}
HERE=$(cd "$(dirname "$0")" && pwd)
STATE="$HERE/.deploy-project"            # remembers the project id between runs (gitignored)

die() { echo "error: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

command -v gcloud >/dev/null || die "gcloud CLI not found. Install: https://cloud.google.com/sdk/docs/install"
ACCOUNT=$(gcloud config get-value account 2>/dev/null || true)
[ -n "$ACCOUNT" ] || die "not logged in. Run: gcloud auth login"
echo "Google account: $ACCOUNT"

# --- project ---------------------------------------------------------------
PROJECT=${PROJECT:-$( [ -f "$STATE" ] && cat "$STATE" || true )}
if [ -z "$PROJECT" ]; then
  PROJECT="tekstiks-proxy-$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 6)"
fi
if ! gcloud projects describe "$PROJECT" >/dev/null 2>&1; then
  step "Creating project $PROJECT"
  gcloud projects create "$PROJECT" --name="tekstiks proxy"
fi
echo "$PROJECT" > "$STATE"

# --- billing ---------------------------------------------------------------
# Cloud Run refuses to deploy without billing, even when usage stays free.
if [ "$(gcloud billing projects describe "$PROJECT" --format='value(billingEnabled)')" != "True" ]; then
  BILLING=${BILLING_ACCOUNT:-$(gcloud billing accounts list --filter='open=true' --format='value(name)' --limit=1)}
  [ -n "$BILLING" ] || die "no open billing account. Create one (needs a card) at https://console.cloud.google.com/billing then re-run."
  step "Linking billing account $BILLING"
  gcloud billing projects link "$PROJECT" --billing-account="$BILLING"
fi

# --- APIs ------------------------------------------------------------------
step "Enabling Cloud Run, Cloud Build and Artifact Registry"
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  --project="$PROJECT"

# --- token -----------------------------------------------------------------
# Keep the token across re-deploys so the iOS Shortcut does not need editing.
TOKEN=${PROXY_TOKEN:-$(gcloud run services describe "$SERVICE" --project="$PROJECT" --region="$REGION" \
  --format='value(spec.template.spec.containers[0].env[0].value)' 2>/dev/null || true)}
[ -n "$TOKEN" ] || TOKEN=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)

# --- deploy ----------------------------------------------------------------
# --max-instances 1: one personal user needs one instance, and it caps the bill
# if the URL and token ever leak. --cpu-boost shortens cold starts.
step "Building and deploying (first run takes a few minutes)"
gcloud run deploy "$SERVICE" \
  --project="$PROJECT" --region="$REGION" \
  --source="$HERE" \
  --allow-unauthenticated \
  --set-env-vars="PROXY_TOKEN=$TOKEN" \
  --cpu=1 --memory=512Mi --timeout=300 \
  --min-instances="$MIN_INSTANCES" --max-instances=1 \
  --cpu-boost \
  --quiet

URL=$(gcloud run services describe "$SERVICE" --project="$PROJECT" --region="$REGION" --format='value(status.url)')

# --- smoke test ------------------------------------------------------------
# Synthesises an Estonian sentence with TartuNLP's public TTS and sends it
# through the proxy. WAV goes straight in, so no local ffmpeg is needed.
step "Testing"
SAMPLE=$(mktemp)
trap 'rm -f "$SAMPLE"' EXIT
if curl -fsS -m 60 -X POST https://api.tartunlp.ai/text-to-speech/v2 \
     -H 'Content-Type: application/json' \
     -d '{"text":"Tere! See on proovisõnum.","speaker":"mari"}' -o "$SAMPLE"; then
  RESULT=$(curl -fsS -m 120 --data-binary @"$SAMPLE" -H "Authorization: Bearer $TOKEN" "$URL/transcribe") \
    || die "proxy test failed; see: gcloud run services logs read $SERVICE --project=$PROJECT --region=$REGION"
  echo "Sent \"Tere! See on proovisõnum.\" -> got \"$RESULT\""
else
  echo "(skipped: could not reach the TTS service for a sample; try the Shortcut directly)"
fi

cat <<EOF

Done.
  Project:  $PROJECT
  URL:      $URL/transcribe
  Token:    $TOKEN
  Warm:     min-instances=$MIN_INSTANCES

iOS Shortcut "Get Contents of URL":
  URL      $URL/transcribe
  Method   POST
  Header   Authorization = Bearer $TOKEN
  Body     File -> Recorded Audio
EOF
