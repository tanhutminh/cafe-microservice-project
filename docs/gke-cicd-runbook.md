# GKE & CI/CD Runbook

🇬🇧 English is expanded by default below —
🇻🇳 nhấn vào phần "Tiếng Việt" bên dưới để mở nội dung tiếng Việt.

<details open>
<summary><strong>🇬🇧 English</strong></summary>

This is a step-by-step runbook for provisioning the GKE infrastructure that backs this
project's Kubernetes deployment: the cluster itself, the CNPG (Postgres) and Strimzi (Kafka)
operators, Secret Manager-backed secrets via the Secrets Store CSI Driver, and the Helm charts
that deploy the 6 Spring Boot services.

**Scope**: GKE cluster foundation through a successful deploy of `charts/cafe` with
`scripts/deploy.sh` (Steps 1-8), plus the CI pipeline that builds and pushes the real container
images those pods run (Step 9). Pausing and resuming the cluster between sessions is a manual
procedure (its own section, after Step 9); automating it is separate, not-yet-implemented work — see
"Not covered here" at the end. Restoring Postgres from its backups, including a rehearsal, has its
own section right after the pausing and resuming one.

Links to repo files point at `master` on GitHub.

All `gcloud` commands assume the default project is set (see Prerequisites). The commands below
are written as plain bash. On Git Bash for Windows, `gcloud` needs `CLOUDSDK_PYTHON` set first
(see Prerequisites).

## Architecture at a glance

- GKE Standard, **zonal** cluster (not regional — the GKE free-tier control-plane fee waiver
  only covers one zonal cluster per billing account), single `cafe` namespace.
- Two node pools: `stateful-pool` (on-demand, tainted `workload=stateful:NoSchedule`, hosts
  Postgres + Kafka) and `stateless-pool` (Spot, autoscaling min 0, hosts everything else).
- **CloudNativePG** (Postgres operator), the **Barman Cloud Plugin** (backups to GCS) and
  **Strimzi** (Kafka operator) all run their operator pods on `stateless-pool` — none of the
  three Helm installs sets a toleration for the `workload=stateful` taint. Only the workloads
  they manage are pinned to `stateful-pool` via node affinity/tolerations: the Postgres instance
  pod (plus the `plugin-barman-cloud` sidecar injected into it) and the Kafka broker.
- **Secrets Store CSI Driver** (GCP provider) syncs Google Secret Manager (GSM) secrets into
  the cluster as native Kubernetes `Secret` objects, consumed by each service's Deployment.
- **Workload Identity Federation** — every pod authenticates to GCP as its own dedicated Google
  Service Account (GSA), no static service-account keys anywhere.
- **Helm**: `charts/cafe-service` (one reusable chart) + `charts/cafe` (umbrella chart with 6
  aliased dependencies: gateway, auth-service, menu-service, order-service, inventory-service,
  report-service).
- **GitHub Actions** (`.github/workflows/backend-ci.yml`, Step 9) builds and pushes each
  service's image to **Artifact Registry**, authenticating to GCP via a separate **Workload
  Identity Federation** setup for GitHub — no static key there either.
- Postgres/Kafka manifests live under `k8s/data-layer/`, applied with plain `kubectl apply`
  — **not** part of any Helm release. This is deliberate: a `helm uninstall`/rollback of the
  app layer must never be able to cascade-delete the database.

## Prerequisites

- `gcloud`, `kubectl`, `helm`, `cmctl` (cert-manager's CLI, installed per cert-manager's
  documentation) and `openssl` installed; `gcloud` authenticated. `helm` must be Helm 4.1.1 or
  newer, the floor `scripts/deploy.sh` enforces: it waits with the `--wait=watcher` status
  checks, which Helm 3 doesn't have, and earlier Helm 4 releases wait out the full timeout on a
  failed Deployment instead of reporting it as soon as the rest have settled.
- `gitleaks` (only needed if you ever move the dev JWT keypair, or another `.gitleaksignore`-listed
  credential, to a different file/line and must regenerate fingerprints — see `.gitleaksignore`
  below; CI itself runs it via `gitleaks/gitleaks-action`, no local install needed for the pipeline).
- `docker` (only needed to run the pinned shellcheck lint locally, Step 9; CI runs the same image
  itself).
- `gke-gcloud-auth-plugin` on your `PATH` (check with `gke-gcloud-auth-plugin --version`).
  `kubectl`, `helm` and `cmctl` all need it to talk to a GKE cluster. Install it with
  `gcloud components install gke-gcloud-auth-plugin` (standalone SDK / Windows installer) or, with
  a package manager, the `google-cloud-cli-gke-gcloud-auth-plugin` package. `clusters create`
  (Step 1) writes the kubeconfig entry itself; to resume from a new shell or machine, run
  `gcloud container clusters get-credentials cafe-cluster --zone=us-central1-a`.
- A bash 4.3+ shell (Git Bash on Windows works; macOS's built-in bash 3.2 doesn't) — the
  commands use bash features such as `${var//-/_}` and brace expansion, and `scripts/deploy.sh`
  uses a nameref (`local -n`). The scripts are tested on bash 5.x.
- On Git Bash for Windows only: `CLOUDSDK_PYTHON` pointing at the Cloud SDK's bundled Python.
  Git Bash runs the SDK's POSIX `gcloud` launcher, which only looks for a bundled Python under a
  Unix-only path, then falls back to `python3`/`python` on `PATH`; when those are only the
  Microsoft Store aliases, `gcloud` fails to start (exit code 49, "Python was not found").
  `scripts/deploy.sh` runs the same POSIX `gcloud` launcher, so it needs this too, and stops with
  a hint when `gcloud` can't start. Add the variable to `~/.bashrc`, then open a new Git Bash
  window (or run `source ~/.bashrc`):

  ```bash
  echo 'export CLOUDSDK_PYTHON="$LOCALAPPDATA/Google/Cloud SDK/google-cloud-sdk/platform/bundledpython/python.exe"' >> ~/.bashrc
  ```

  That is the Cloud SDK's default per-user install path; if yours lives elsewhere,
  `gcloud.cmd info --format='value(basic.python_location)'` prints the right one.
- `git` and `sha256sum` on your `PATH` — `scripts/deploy.sh` checks the repo state with git, and
  `scripts/image-tag.sh` hashes with `sha256sum` (Git Bash ships both; macOS before 15 (Sequoia)
  lacks `sha256sum`).
- A GCP project with billing enabled. This guide's project runs on a **Free Trial** billing
  account, which ends on **2026-12-08** or once its $300 (~7,875,000₫) credit is spent, whichever
  comes first (Console → Billing → Overview, "Free credit", shows the days and credit left). When
  it ends, every resource is stopped; unless the billing account is upgraded within the following
  30-day grace period, everything in the project is permanently deleted: the cluster with its
  persistent disks (Postgres's and Kafka's data), the GCS backup bucket, Secret Manager, Artifact
  Registry and the IAM and Workload Identity setup. Upgrading during the grace period means you
  *might* be able to recover them; Google doesn't promise it. Before the end date, either upgrade
  the billing account to a paid one (unused credit is kept until its own expiry) or copy the whole
  backup bucket out of the project — to a bucket in a project on a different, paid billing account,
  or with `gcloud storage rsync -r gs://cafe-microservices-cafe-pg-backups <dir>`. Copy every
  prefix, since a recovery moves the backups under a new one, and base backups and WAL together,
  since one is useless without the other; restoring from either copy is covered at the end of
  "Recovering after losing the Postgres cluster". The bucket holds only Postgres's data; see the
  next bullet for Kafka's.
- Kafka's data has no backup: it only carries saga messages in transit, and losing it loses those
  not yet consumed. order-service's saga reconciliation re-sends the command of an order left
  waiting on a reserve or commit reply, but only 3 times, a minute or so apart, and re-sends made
  while Kafka is down count too. After that it puts the order back to OPEN or CONFIRMED; stock
  inventory-service had already reserved or deducted for it stays so, as does stock a lost
  release-stock command should have freed, until corrected by hand.
- Run every command from the repo root — paths like `k8s/data-layer/` and `charts/cafe` are
  relative to it.
- Decide your project ID, cluster name/zone, and Postgres backup bucket name up front. They are also
  baked into IAM bindings and repo files: `scripts/deploy.sh`'s `gcp_project`, `cluster_zone` and
  `cluster_name` (the kube-context and `get-credentials` hint it uses); the Artifact Registry path,
  which `deploy.test.sh` keeps in sync across `scripts/deploy.sh`'s `image_ref`,
  `charts/cafe/values.yaml`'s `global.imageRegistry` and `.github/workflows/backend-ci.yml`'s
  `IMAGE`; that workflow's `service_account`, its `workload_identity_provider` (which holds the
  project number; Step 9's GCP setup prints the full name) and its Docker login `registry:` host
  (the host part of the Artifact Registry path, which `deploy.test.sh` doesn't check);
  `charts/cafe/values.yaml`'s `global.gcpProjectId` (which renders each `SecretProviderClass`'s
  `resourceName:` paths and each ServiceAccount's `iam.gke.io/gcp-service-account` annotation),
  `k8s/data-layer/postgres-cluster.yaml`'s `serviceAccountTemplate` annotation, and
  `k8s/data-layer/postgres-backup.yaml`'s `destinationPath`. This guide uses the actual values from
  this repo (`cafe-microservices` / `cafe-cluster` / `us-central1-a` /
  `gs://cafe-microservices-cafe-pg-backups`) as examples; substitute your own.

Set the default project and enable the APIs this guide uses (on a fresh project the first
`gcloud` call would otherwise prompt or fail):

```bash
gcloud config set project cafe-microservices
gcloud services enable compute.googleapis.com container.googleapis.com secretmanager.googleapis.com storage.googleapis.com iam.googleapis.com iamcredentials.googleapis.com artifactregistry.googleapis.com sts.googleapis.com cloudresourcemanager.googleapis.com
```

Step 9 also needs a GitHub repository with Actions enabled and admin access to it (to configure
branch protection) — no extra CLI tooling beyond `gcloud` (and `docker`, only for the optional
local lint), though the GitHub CLI (`gh`) is a convenient way to trigger the first manual run.

### Budget alert

A Cloud Billing budget emails the billing account's administrators and users when spend crosses
a threshold. It only alerts — it never caps or stops spending — but its low thresholds are the
cheap guard against a cluster left running between sessions. Creating one needs the Billing Account
Administrator or Billing Account Costs Manager role. Its APIs are called through the project
`gcloud config` points at, so they are enabled there. Run both blocks in the same shell:

```bash
gcloud services enable cloudbilling.googleapis.com billingbudgets.googleapis.com
billing_account="$(gcloud billing projects describe cafe-microservices --format='value(billingAccountName)')"
billing_account="${billing_account#billingAccounts/}"
gcloud billing accounts describe "$billing_account" --format='value(currencyCode)'
```

The last command prints the account's currency; `--budget-amount` must use it. With the default
credit treatment a budget counts spend *after* credits, which on a Free Trial stays near 0 until
the credit runs out, so the budget would never fire. Instead the budget subtracts only the credit
types listed in `--filter-credit-types`: free-tier credits, which should include GKE's
control-plane credit (checked once at the end of this section), and sustained use discounts (E2 and
Spot nodes get none, so this changes nothing here; it is listed so the budget still measures only
the trial credit if another machine type is used). `PROMOTION` credits, the Free Trial's $300
(~7,875,000₫), stay counted as spend, so the budget measures how fast the trial credit is used.

Spend is counted from the 1st of each month (Pacific Time) and shows up in billing hours to about a
day after the usage, so each alert means "this month's spend so far has crossed this amount". The
thresholds are fractions of the $100 (~2,625,000₫) budget and give two signals:

- $10, $20 and $40 (~262,500₫, ~525,000₫ and ~1,050,000₫): a cluster left running. With both pools
  up, the cluster costs roughly $2.2–2.8 (~58,000–74,000₫) a day, so round-the-clock running alone
  reaches them after about 4, 7–9 and 14–18 days (estimated from node and disk list prices, not
  from the bill). A paused cluster's disks, bucket, registry and secrets cost about $1 (~26,000₫) a
  month, so pausing alone never crosses them, but a month's sessions do: if an alert arrives
  sooner than the hours you've run the cluster would explain, check that both pools are at 0 nodes
  (the last command under "Pausing").
- $50, $90 and $100 (~1,312,500₫, ~2,362,500₫ and ~2,625,000₫), and the forecasted $100: the
  trial's pace. $100 a month is about the pace that uses up $300 (~7,875,000₫) in the trial's 90
  days, so the forecasted alert means "on pace to run out early":

```bash
budgets="$(gcloud billing budgets list --billing-account="${billing_account:?run the block above in this shell first}" --filter='displayName="cafe-microservices monthly"' --format='value(name)')" &&
  { [[ -z $budgets ]] || { echo "a budget named cafe-microservices monthly already exists: $budgets (see the notes after this block)" >&2; false; }; } &&
  gcloud billing budgets create --billing-account="$billing_account" \
    --display-name="cafe-microservices monthly" \
    --budget-amount=100USD --calendar-period=month \
    --filter-projects=projects/cafe-microservices \
    --credit-types-treatment=include-specified-credits \
    --filter-credit-types=FREE_TIER,SUSTAINED_USAGE_DISCOUNT \
    --threshold-rule=percent=0.1 --threshold-rule=percent=0.2 --threshold-rule=percent=0.4 \
    --threshold-rule=percent=0.5 --threshold-rule=percent=0.9 --threshold-rule=percent=1.0 \
    --threshold-rule=percent=1.0,basis=forecasted-spend
```

If the account's currency isn't USD, replace `100USD` with the equivalent amount in that currency.
If the block stops because a budget with this name already exists, keep that one — a second would
send every alert twice — and change it with `gcloud billing budgets update` instead. Check once, in
Console → Billing → Reports (filtered to this project, credits shown), that the Kubernetes Engine
credit is listed as a free-tier credit. If it shows under another credit type, add that type to the
budget; otherwise the budget counts the cluster's ~$73 (~1,900,000₫) monthly control-plane fee as
spend. The new list replaces the budget's whole list, so it repeats the two types above, and
`<name>` is the `billingAccounts/…/budgets/…` name that
`gcloud billing budgets list --billing-account="$billing_account" --format='value(name)'` prints:
`gcloud billing budgets update <name> --credit-types-treatment=include-specified-credits --filter-credit-types=FREE_TIER,SUSTAINED_USAGE_DISCOUNT,<type>`.

---

## Step 1 — GKE cluster and node pools

```bash
# The default pool is temporary (deleted below), so its disk settings don't affect the final cluster
gcloud container clusters create cafe-cluster \
  --zone=us-central1-a \
  --machine-type=e2-medium \
  --disk-type=pd-standard --disk-size=20 \
  --num-nodes=1 \
  --workload-pool=cafe-microservices.svc.id.goog \
  --workload-metadata=GKE_METADATA

kubectl create namespace cafe
```

Enable Workload Identity **at cluster creation time** if at all possible — enabling it later on
an existing cluster (`gcloud container clusters update --workload-pool=...`) still works, but
each node pool then additionally needs `--workload-metadata=GKE_METADATA` applied after the
fact, which takes effect immediately for the workloads already running on that pool and can
disrupt them.

Create the two node pools, then delete the default pool that `clusters create` made, so it
doesn't linger as a third, unused pool:

```bash
# Stateful: Postgres + Kafka. No autoscaling — fixed size; to pause it at 0 nodes, hibernate
# Postgres first (see "Pausing and resuming the cluster between sessions").
gcloud container node-pools create stateful-pool \
  --cluster=cafe-cluster --zone=us-central1-a \
  --machine-type=e2-medium --disk-type=pd-balanced --disk-size=100 \
  --num-nodes=1 \
  --node-taints=workload=stateful:NoSchedule \
  --workload-metadata=GKE_METADATA

# Stateless: everything else, on Spot. Autoscaling removes idle nodes but not the last 2-3
# (system-pods appendix); pausing to 0 nodes is manual (see "Pausing and resuming the cluster
# between sessions").
gcloud container node-pools create stateless-pool \
  --cluster=cafe-cluster --zone=us-central1-a \
  --machine-type=e2-medium --spot --disk-type=pd-standard --disk-size=50 \
  --num-nodes=1 --enable-autoscaling --min-nodes=0 --max-nodes=6 \
  --workload-metadata=GKE_METADATA

# The default pool is no longer needed once the two pools above exist.
gcloud container node-pools delete default-pool --cluster=cafe-cluster --zone=us-central1-a --quiet
```

`pd-standard` over `pd-balanced`/SSD for `stateless-pool`: cheaper, and on a Free Trial project
SSD-family regional quota is easy to exhaust with no way to request an increase — `pd-standard`
draws from a much larger, separate quota bucket.

---

## Step 2 — GCS bucket, service accounts, Workload Identity bindings

```bash
# Postgres backup bucket
gcloud storage buckets create gs://cafe-microservices-cafe-pg-backups --location=us-central1

# Backup-writing GSA for the Postgres pod itself
gcloud iam service-accounts create cafe-postgres-backup
gcloud storage buckets add-iam-policy-binding gs://cafe-microservices-cafe-pg-backups \
  --member="serviceAccount:cafe-postgres-backup@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/storage.objectAdmin

# Barman also reads bucket metadata (storage.buckets.get), which objectAdmin doesn't include
gcloud storage buckets add-iam-policy-binding gs://cafe-microservices-cafe-pg-backups \
  --member="serviceAccount:cafe-postgres-backup@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/storage.legacyBucketReader

# Read-only GSA for restoring from those backups (see "Restoring Postgres from a backup"): a
# restore only reads the bucket, so the cluster doing it can't overwrite or delete the backups
gcloud iam service-accounts create cafe-postgres-restore
for role in roles/storage.objectViewer roles/storage.legacyBucketReader; do
  gcloud storage buckets add-iam-policy-binding gs://cafe-microservices-cafe-pg-backups \
    --member="serviceAccount:cafe-postgres-restore@cafe-microservices.iam.gserviceaccount.com" \
    --role="$role"
done

# One GSM-reader GSA per service (5 app services + gateway):
for svc in auth-service menu-service order-service inventory-service report-service gateway; do
  gcloud iam service-accounts create "${svc}-gsm-reader"
done
```

Bind each GSA to its matching Kubernetes ServiceAccount (KSA) via Workload Identity. The KSA
doesn't need to exist yet — `charts/cafe-service`'s [serviceaccount.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe-service/templates/serviceaccount.yaml) template will create
it later with a matching name and the `iam.gke.io/gcp-service-account` annotation:

```bash
for svc in auth-service menu-service order-service inventory-service report-service gateway; do
  gcloud iam service-accounts add-iam-policy-binding \
    "${svc}-gsm-reader@cafe-microservices.iam.gserviceaccount.com" \
    --role=roles/iam.workloadIdentityUser \
    --member="serviceAccount:cafe-microservices.svc.id.goog[cafe/${svc}]"
done

# The Postgres backup GSA binds to `cafe-postgres`, the KSA CNPG creates itself (named after the Cluster)
gcloud iam service-accounts add-iam-policy-binding \
  "cafe-postgres-backup@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/iam.workloadIdentityUser \
  --member="serviceAccount:cafe-microservices.svc.id.goog[cafe/cafe-postgres]"

# The restore GSA binds to `cafe-postgres-restore`, the KSA of the scratch Cluster a rehearsal
# restores into; the binding stays, so a rehearsal never waits on IAM propagation
gcloud iam service-accounts add-iam-policy-binding \
  "cafe-postgres-restore@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/iam.workloadIdentityUser \
  --member="serviceAccount:cafe-microservices.svc.id.goog[cafe/cafe-postgres-restore]"
```

The Postgres pod's own backup GSA is annotated differently — via the CNPG `Cluster`'s
`serviceAccountTemplate` (see [k8s/data-layer/postgres-cluster.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/data-layer/postgres-cluster.yaml)),
not a Helm-managed ServiceAccount, since it's the data layer, not an app service. That template
only sets the annotation, so the binding above for `cafe-postgres-backup` → `cafe/cafe-postgres`
is still required — without it the pod cannot authenticate to the backup bucket.

IAM changes can take several minutes to propagate — a pod's CSI mount failing with
`PermissionDenied: iam.serviceAccounts.getAccessToken denied` shortly after a fresh binding is
usually just propagation delay, not a config error. It self-resolves via kubelet's automatic
mount retries.

---

## Step 3 — Cluster infrastructure (operators)

Install in this order — cert-manager must be Ready before the Barman Cloud Plugin, whose
install manifest creates `cert-manager.io/Certificate` resources cert-manager's webhook must be
able to admit immediately.

```bash
# cert-manager, pinned to v1.21.2 - newer releases may exist,
# see github.com/cert-manager/cert-manager/releases
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.21.2/cert-manager.yaml
cmctl check api --wait=2m   # confirm Ready before continuing

# CloudNativePG operator
helm repo add cnpg https://cloudnative-pg.github.io/charts
helm repo update cnpg
helm upgrade --install cnpg cnpg/cloudnative-pg --version 0.29.0 -n cnpg-system --create-namespace

# Barman Cloud Plugin (backups) - same repo as CNPG
helm upgrade --install plugin-barman-cloud cnpg/plugin-barman-cloud --version 0.8.0 -n cnpg-system

# Secrets Store CSI Driver + GCP provider
helm repo add secrets-store-csi-driver https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
helm repo update secrets-store-csi-driver
helm upgrade --install csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver \
  --version 1.6.1 -n kube-system --set syncSecret.enabled=true
# GCP provider, pinned to v1.17.0 rather than the main branch - newer releases may exist,
# see github.com/GoogleCloudPlatform/secrets-store-csi-driver-provider-gcp/releases
kubectl apply -f https://raw.githubusercontent.com/GoogleCloudPlatform/secrets-store-csi-driver-provider-gcp/v1.17.0/deploy/provider-gcp-plugin.yaml

# Strimzi (Kafka operator)
helm repo add strimzi https://strimzi.io/charts/
helm repo update strimzi
helm upgrade --install strimzi strimzi/strimzi-kafka-operator --version 1.2.0 \
  -n strimzi-system --create-namespace -f k8s/operators/strimzi-values.yaml
```

Two non-obvious flags above, both load-bearing (the install silently "succeeds" without them,
then nothing works downstream):

- **`--set syncSecret.enabled=true`** on the CSI driver: this feature is **off by default** in
  the upstream chart. Without it, `SecretProviderClass.spec.secretObjects` (which is how this
  project turns a CSI-mounted secret into a real `Secret` object that a Deployment's
  `secretKeyRef` can reference) silently does nothing — no error, the mounted files under
  `/mnt/secrets-store` are there, but no `Secret` ever appears.
- **`-f`** [k8s/operators/strimzi-values.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/operators/strimzi-values.yaml), which sets `watchNamespaces: [cafe]`: the chart
  defaults to `watchAnyNamespace: false` / `watchNamespaces: []`, meaning the operator only
  reconciles resources in its own `strimzi-system` namespace and silently ignores any
  `Kafka`/`KafkaNodePool` applied to `cafe` — no events, no error, just nothing happening.

Verify cert-manager, the CNPG operator, Barman Cloud Plugin, the Secrets Store CSI Driver + GCP
provider, and Strimzi are all ready before moving on — each command returns as soon as its
target is ready, or fails after the timeout:

```bash
for ns in cert-manager cnpg-system strimzi-system; do
  kubectl wait --for=condition=Available deployment --all -n "$ns" --timeout=300s
done
kubectl rollout status daemonset/csi-secrets-store-secrets-store-csi-driver -n kube-system --timeout=300s
kubectl rollout status daemonset/csi-secrets-store-provider-gcp -n kube-system --timeout=300s
```

---

## Step 4 — Google Secret Manager secrets

Create one secret per credential, then bind read access scoped to that single secret (not
project-wide) to the matching GSA from Step 2:

| Secret name | Consumer |
|---|---|
| `{service}-db-username` / `{service}-db-password` (× auth/menu/order/inventory/report) | that service's Postgres role |
| `auth-service-jwt-private-key` | auth-service (signs JWTs) |
| `gateway-jwt-public-key` | gateway (verifies JWTs) — the public half of the *same* keypair |

```bash
for svc in auth-service menu-service order-service inventory-service report-service; do
  # the username must equal the CNPG role name (managed.roles in k8s/data-layer/postgres-cluster.yaml)
  printf '%s' "${svc//-/_}" | gcloud secrets create "${svc}-db-username" --data-file=-
  openssl rand -base64 24 | tr -d '\r\n' | gcloud secrets create "${svc}-db-password" --data-file=-
  for name in db-username db-password; do
    gcloud secrets add-iam-policy-binding "${svc}-${name}" \
      --member="serviceAccount:${svc}-gsm-reader@cafe-microservices.iam.gserviceaccount.com" \
      --role=roles/secretmanager.secretAccessor
  done
done

# The JWT keypair is generated in a throwaway directory so the private key never lands in the repo
pushd "$(mktemp -d)"
openssl genrsa 2048 | openssl pkcs8 -topk8 -nocrypt > jwt-private.pem
openssl rsa -in jwt-private.pem -pubout > jwt-public.pem
gcloud secrets create auth-service-jwt-private-key --data-file=jwt-private.pem
gcloud secrets create gateway-jwt-public-key --data-file=jwt-public.pem
rm jwt-private.pem jwt-public.pem
d=$PWD
popd
rmdir "$d"

gcloud secrets add-iam-policy-binding auth-service-jwt-private-key \
  --member="serviceAccount:auth-service-gsm-reader@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/secretmanager.secretAccessor
gcloud secrets add-iam-policy-binding gateway-jwt-public-key \
  --member="serviceAccount:gateway-gsm-reader@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/secretmanager.secretAccessor

# Every secret must show at least 1 version (0 means it was created empty)
for s in {auth,menu,order,inventory,report}-service-db-{username,password} auth-service-jwt-private-key gateway-jwt-public-key; do
  echo "$s: $(gcloud secrets versions list "$s" --format='value(name)' | wc -l) version(s)"
done
```

Each `gcloud secrets create` fails if the secret already exists, so run this block once on a
fresh project. The username secrets hold the CNPG role names (`auth_service`, `menu_service`, …)
because Step 6's `managed.roles` create roles with exactly those names — a mismatch leaves the
service's `wait-for-db` initContainer timing out.

Generate a **fresh** JWT keypair here — don't reuse the dev keypair committed for local
development (in `.env.example`, and in auth-service's and gateway's
`application-local.yml.example`).

A secret that shows 0 versions needs one added with
`gcloud secrets versions add <name> --data-file=-`, because re-running `create` fails with
"already exists".

---

## Step 5 — Application configuration (already in the repo; reference only)

These values are left blank in each service's `application.yml`:

- `spring.datasource.username` and `spring.datasource.password` in the 5 DB-backed services;
- `app.jwt.private-key` in `auth-service`;
- `app.jwt.public-key` in `gateway`.

They are sourced from environment variables instead (`SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`, `APP_JWT_PRIVATE_KEY`, `APP_JWT_PUBLIC_KEY`), populated by
`charts/cafe-service/templates/deployment.yaml`'s `secretKeyRef` in the real deployment, or by
`docker-compose.yml` / each service's `application-local.yml` for local dev.

See [auth-service/application.yml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/backend/auth-service/src/main/resources/application.yml)
and [gateway/application.yml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/backend/gateway/src/main/resources/application.yml) for the
exact pattern.

---

## Step 6 — Data-layer manifests (`k8s/data-layer/`)

Four files, applied directly with `kubectl` — never wrapped into a Helm chart:

- [postgres-storageclass.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/data-layer/postgres-storageclass.yaml) — a dedicated
  `Retain`-policy StorageClass for Postgres's PVC (GKE's built-in classes are all `Delete`;
  Postgres is this app's source of truth, so its disk must survive even a mistaken PVC/Cluster
  deletion). Getting data back from such a kept disk is a manual last resort; the supported way
  back is the object-store backups (see "Restoring Postgres from a backup").
- [postgres-cluster.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/data-layer/postgres-cluster.yaml) — the CNPG `Cluster` and its databases and roles:
  - the `Cluster` bootstraps `auth_db` at creation (a `Cluster` can only bootstrap one database);
  - 4 `Database` CRs create the other services' databases;
  - 5 `managed.roles` entries are reconciled against each service's `{service}-db-credentials`
    Secret, synced from Step 4's `{service}-db-username`/`-db-password` GSM secrets via the CSI
    driver's `secretObjects` mapping (enabled in Step 3; defined in Step 7's
    `secretproviderclass.yaml`);
  - `imageName` pins the Postgres image (18.4, `standard` flavor, the one upstream recommends
    with the Barman Cloud Plugin) by an immutable timestamped tag, so neither upgrading the CNPG
    operator nor a CVE rebuild of upstream's rolling tag can silently change what runs. With
    `instances: 1`, changing it restarts the single instance (a short outage);
  - tip: the storage field is `spec.storage.storageClass` (CNPG's own CRD field), not
    `storageClassName` (the plain-PVC name) — an easy mix-up, verify with
    `kubectl explain cluster.spec.storage`.
- [postgres-backup.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/data-layer/postgres-backup.yaml) — the Barman Cloud Plugin's
  `ObjectStore` (points at the GCS bucket, auths via the Cluster's own Workload Identity, no
  separate credentials Secret; its 10-day `retentionPolicy` — "Pausing" says when it runs) and a
  nightly (00:00 UTC) `ScheduledBackup`.
- [kafka-cluster.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/data-layer/kafka-cluster.yaml) — a single-broker KRaft
  `KafkaNodePool` + `Kafka`, pinned to `stateful-pool` via node affinity/toleration. Uses GKE's
  built-in `standard` StorageClass (`Delete` reclaim) deliberately, unlike Postgres — Kafka here
  only carries saga messages in transit, not source-of-truth data. Like Postgres, the Kafka
  version (4.3.1) is pinned, so upgrading the Strimzi operator cannot silently change it.

```bash
kubectl apply -f k8s/data-layer/postgres-storageclass.yaml
kubectl apply -f k8s/data-layer/
```

Apply the StorageClass first so the CNPG `Cluster`'s PVC finds its class from the start, then
apply the rest of the directory.

One thing stays unresolved until Step 8: the `{service}-db-credentials` Secrets only exist once
a service pod mounts its CSI volume, so the CNPG `Cluster`'s `managed.roles` can't fully
reconcile before then (CNPG reports it and keeps retrying). Once the pods start in Step 8, each
service's `wait-for-db` initContainer (Step 7) waits up to 600s for its role to become usable.

---

## Step 7 — Helm charts

### `charts/cafe-service` — one reusable chart, instantiated 6 times

Key values (full schema: [values.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe-service/values.yaml)):

- `appName` — drives the Deployment/Service/ServiceAccount/ConfigMap names *and* the pod label
  selector; the `SecretProviderClass` is named separately, via `secretProviderClassName`.
- `db.enabled` / `kafka.enabled` / `jwt.privateKey.enabled` / `jwt.publicKey.enabled` — gate
  which env vars, ConfigMap keys, `SecretProviderClass` entries and the `wait-for-db`
  initContainer get rendered for this particular service instance; `db.enabled` also picks the
  rollout `strategy.type`.
- `image.tag` defaults to the deliberately invalid `unset` — CI (Step 9) never pushes a
  `latest` tag, only content-hash ones, so a deploy that forgets to override it fails loudly
  instead of silently trying to pull a tag that doesn't exist. Step 8 sets it per service via
  `--set-string <alias>.image.tag=<content-hash-tag>`.

Templates ([deployment.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe-service/templates/deployment.yaml)):

- `strategy.type` is `Recreate` for DB-backed services (`RollingUpdate` otherwise) — avoids an
  old pod and a post-Flyway-migration new pod running side by side; an acceptable trade for a
  project that isn't targeting zero-downtime deploys.
- A `wait-for-db` initContainer (DB-backed services only) runs
  [wait-for-db.sh](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe-service/files/wait-for-db.sh),
  which the template inlines with `.Files.Get` (the render fails if the file is missing). It
  retries a `psql` connection for up to 600s, reading host, user, database and password from
  libpq's own `PGHOST`/`PGUSER`/`PGDATABASE`/`PGPASSWORD`, with `PGCONNECT_TIMEOUT` capping
  each attempt's connection at 5s, and times the window with `date +%s` — **not** `$SECONDS`,
  which BusyBox `ash` (the `postgres:16-alpine` image's shell) silently expands to empty, turning
  the timeout check into dead code. It logs psql's error the first time and whenever it changes,
  and the last one when it times out: `kubectl logs <pod> -n cafe -c wait-for-db`. Its test
  suite, `wait-for-db.test.sh`, sits next to it; the chart's `.helmignore` keeps `*.test.sh` out
  of the packaged chart.
- `progressDeadlineSeconds: 1200` on the DB-backed Deployments, so a pod waiting in `wait-for-db`
  doesn't fail a legitimate first rollout (see Step 8 for the arithmetic).
- `startupProbe` (30 × 10s budget) gates when `readinessProbe`/`livenessProbe` even start being
  checked — more robust than a fixed `initialDelaySeconds` guess under JVM + Flyway boot time on
  a Spot node.
- Two separate pod volumes: `config` (a `configMap`) and `secrets-store` (a `csi` volume) — they
  cannot be nested under one `projected` volume, since `csi` isn't a valid `projected` source.
  The CSI volume **must** actually be mounted (not just declared) — an unmounted CSI volume
  never triggers the driver's `secretObjects` sync, so the derived `Secret` never gets created.

[secretproviderclass.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe-service/templates/secretproviderclass.yaml) lists the
GSM secrets this service instance needs (conditionally, per the `db.enabled` / `jwt.*.enabled`
flags) and maps them into `secretObjects` — the bridge from "files mounted under
`/mnt/secrets-store`" to "a real K8s `Secret` other resources can reference via `secretKeyRef`".

### `charts/cafe` — umbrella chart

[Chart.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe/Chart.yaml) declares `cafe-service` as 6 *aliased* Helm
dependencies (the standard pattern for many near-identical service instances sharing one
chart). [values.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe/values.yaml) sets `global.gcpProjectId` and
`global.imageRegistry` (the Artifact Registry host+path every service's image is pulled from,
prefixed onto `image.repository` when rendering each Deployment) once, plus one block per alias
with that service's real port/db/kafka/jwt values and its own `image.repository`.

- `global.tracing.export.zipkin.enabled` (`false`) — rendered into every service's ConfigMap as
  Spring's `management.tracing.export.zipkin.enabled`. Only the Zipkin exporter is off:
  sampling, trace-context propagation and trace IDs in logs are unaffected. Not the global
  `management.tracing.export.enabled`, which would also turn off propagation and log
  correlation. The reusable `cafe-service` chart defaults it to `true`, Spring's own default.

```bash
# `build`, not `update`: packages the file:// subchart against the committed Chart.lock without
# rewriting it, and fails if Chart.yaml's dependencies no longer match that lock;
# --skip-refresh: a file:// dependency needs none of the Helm repositories added above
helm dependency build --skip-refresh charts/cafe
# render and lint locally before touching the real cluster (rendering is the real check);
# lint should report 0 failed - an "icon is recommended" INFO and a "templates/ directory does
# not exist" warning are normal for this umbrella chart
helm lint charts/cafe
helm template charts/cafe > /dev/null
```

---

## Step 8 — Deploy for real

This step needs images to deploy: do Step 9's GCP setup and let one CI run build them first.

Each service's image tag is a content hash of its own source, `common-lib` and the parent pom
(see Step 9), so — unlike a single shared release tag — one `$TAG` does not fit all six.
[scripts/deploy.sh](https://github.com/tanhutminh/cafe-microservice-project/blob/master/scripts/deploy.sh)
deploys the images for the checked-out commit. Before any `helm upgrade` it aborts if:

- bash is older than 4.3, or `gcloud`, `helm`, `kubectl`, `gke-gcloud-auth-plugin`, `git` or
  `sha256sum` isn't on `PATH` (it names every missing one).
- `backend/`, `charts/` or `scripts/image-tag.sh` has uncommitted changes (whatever
  `status.showUntrackedFiles` says), files git status is told to skip (assume-unchanged or
  skip-worktree, listed with git's own `h`/`s`/`S` tag; a sparse checkout sets `S` too), or
  `charts/` holds git-ignored files (listed with a `!! ` prefix). A deploy must correspond to one
  commit: the images come from committed `backend/` code, so local backend edits would silently
  not be deployed, while the chart and `image-tag.sh` are used as-is from the working tree, so
  edits to them would be deployed without being recorded in any commit. Helm packages every file
  in a chart directory not excluded by its `.helmignore`, git-ignored or not;
  `charts/cafe/charts/*.tgz` is exempt from every one of these checks, since the deploy
  regenerates it. `scripts/deploy.sh` itself is exempt too, so edits to the script can be tried
  before committing; release content belongs in the chart, not in the script's `helm` flags.
- the `HEAD` commit can't be read, or isn't on `master`: neither the commit `origin/master` points
  at, as last fetched, nor one of its ancestors. `HEAD` is read once, so this check, every image tag
  and the release's description all name the same commit. CI builds images only from `master`, and
  the chart is deployed from the working tree, which the check above requires to match `HEAD`, so a
  branch commit would put chart changes no merge has recorded on the cluster. Merge it through a PR
  and deploy from `master`, or run `git fetch` if it already is; a missing `origin/master` ref fails
  the check too. Uncommitted edits to `scripts/deploy.sh` can still be tried from `master`, since
  the check above exempts it. These repo-state checks come before the environment ones below, and
  inherited git variables (`GIT_DIR` and the like) are cleared first, so they always read this
  checkout.
- the `helm` on `PATH` is older than 4.1.1 (see Prerequisites), or can't report its version.
- the `gcloud` on `PATH` fails to start (on Git Bash for Windows, set `CLOUDSDK_PYTHON` — see
  Prerequisites).
- the `gke_cafe-microservices_us-central1-a_cafe-cluster` kube-context doesn't exist (run the
  `get-credentials` command from Prerequisites). Every call that talks to the cluster names that
  context explicitly, so a deploy never lands on whatever cluster the current context points at.
- a service's tag can't be computed at `HEAD` (for example, its `backend/<service>` directory
  isn't in the commit).
- any service's image is missing or not accessible in Artifact Registry (it names that image). A
  tag with no matching image would otherwise just produce a silent `ImagePullBackOff` later. If
  gcloud's own error above the message isn't a not-found error (authentication, permission or
  network), fix that first. Otherwise: tags are computed from `HEAD`'s committed `backend/`
  content, and CI builds images only from `master` (Step 9). So either `HEAD` is a `master`
  commit no CI run built (e.g. one inside a merged branch) — deploy a commit CI built, such as
  `master`'s tip or a merge commit — or `master`'s `backend-ci` run for it is still running or
  failed (wait, or fix it); if `master` has that content but no run built it, a
  `workflow_dispatch` run of `backend-ci` on `master` builds it. The check runs with your own
  gcloud credentials; the cluster's nodes pull with their own service account, granted
  `roles/artifactregistry.reader` in Step 9's GCP setup.

Otherwise it runs `helm dependency build --skip-refresh` (so the packaged `cafe-service` subchart
always matches `charts/cafe-service`), then `helm upgrade --install` with
`-n cafe --wait=watcher --timeout 22m`, recording the checked-out commit as the release's
description (`helm history` shows it for every deployed revision; a failed revision shows Helm's
failure message instead, and a rollback revision reads "Rollback to N"), and lists the pods and
Secrets in the `cafe` namespace:

```bash
git checkout master && git pull
bash scripts/deploy.sh
```

The script only returns once all 6 app Deployments are ready — the DB-backed pods pass through
`Init:0/1` while their `wait-for-db` initContainer waits (Step 6/7) — or fails, listing the pods
so you can see which one is stuck (see Troubleshooting below). The chart sets each DB-backed
Deployment's `progressDeadlineSeconds` to 1200 (the gateway, with no `wait-for-db`, keeps the
600s default): a pod waiting in `wait-for-db` makes no rollout progress, and the slowest
legitimate first rollout takes about 930s (a 600s `wait-for-db` window plus up to 8s for its last
attempt, a 10s restart back-off if the database comes up just after that window, up to 300s of
startup probe and a 10s readiness period), plus the image pulls and, when the autoscaler has to
add a `stateless-pool` node, that node's startup including its CSI driver pod — 1200s leaves
about 4.5 minutes for those. With Helm 4.1.1 or newer, `--wait=watcher` marks a Deployment past
its deadline as Failed ("Progress deadline exceeded") and returns that error once every other
resource has settled; the 22-minute timeout sits 2 minutes above the deadline, so the deadline,
not a bare timeout, ends a stalled rollout. After a successful run, the
`{service}-db-credentials` and `*-jwt-key` Secrets should appear, and `cafe-postgres-1` and the
Kafka pod should be `Running` too.

While it waits the script prints nothing: a pod stuck in, say, `ImagePullBackOff` counts as still
in progress until its Deployment's deadline (600s for the gateway, 1200s for the DB-backed
services), never beyond the 22-minute timeout. Watch it from another shell with
`kubectl get pods -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -w`.

`scripts/deploy.sh` always passes `-n cafe` to its `helm upgrade --install` call — necessary because
nothing in the chart itself sets a namespace (every template uses `{{ .Release.Namespace }}`), so a
bare `helm upgrade --install cafe charts/cafe` without `-n cafe` would silently deploy everything,
including each Deployment's own ServiceAccount, into `default` instead.

### Troubleshooting deploys

Symptoms you may hit when deploying — most of them on the first real deploy — with their root
causes:

1. **CSI mount fails with `driver name secrets-store.csi.k8s.io not found`** on a very new node
   — usually just the CSI DaemonSet not finished starting on that node yet. Check node age
   before assuming a real problem.
2. **`add-iam-policy-binding` fails with `Identity Pool does not exist`** — Workload Identity
   was never actually enabled on the cluster (Step 1). Fix at the cluster level
   (`--workload-pool=...`), then each node pool also needs `--workload-metadata=GKE_METADATA`
   (takes effect immediately for workloads already running on that pool).
3. **CSI mount `PermissionDenied: iam.serviceAccounts.getAccessToken denied` right after a fresh
   IAM binding** (Step 2) — propagation delay, self-resolves in a few minutes via kubelet retry.
4. **CSI mount succeeds (`SecretProviderClassPodStatus` shows `mounted: true`) but the derived
   `Secret` never appears** — `syncSecret.enabled` wasn't set on the CSI driver Helm release
   (Step 3). Check `kubectl auth can-i list secrets --as=system:serviceaccount:kube-system:secrets-store-csi-driver -A`.
5. **Postgres reports `ContinuousArchiving=False` and the backup bucket stays empty** — read the
   condition with `kubectl get cluster cafe-postgres -n cafe -o jsonpath='{.status.conditions}'`
   and the real error with `kubectl logs cafe-postgres-1 -n cafe -c plugin-barman-cloud`. A
   `403 ... does not have storage.buckets.get access` means the `roles/storage.legacyBucketReader`
   bucket binding from Step 2 is missing (`objectAdmin` alone doesn't include that permission).
   If it fails before ever reaching the bucket, the likely gap is the Workload Identity binding
   for `cafe-postgres-backup` → `cafe/cafe-postgres`, also from Step 2.
6. **The CNPG `Cluster` reports that a role's password Secret is missing** — expected until
   Step 8: the `{service}-db-credentials` Secrets only exist once service pods mount the CSI
   volume (see Step 6). It resolves by itself once the pods run.
7. **An application pod sits in `ImagePullBackOff`** — Step 8's guard should have caught a
   missing image before this. `kubectl describe pod <pod> -n cafe` shows the `image:` the pod is
   trying to pull and, in its events, why the pull failed:
   - **not found** — the reference doesn't match what `gcloud artifacts docker images describe`
     reports for that tag. `deploy.sh` refuses to run with uncommitted changes under `backend/`,
     `charts/` or `scripts/image-tag.sh`, so a mismatch usually means the image was deployed some
     other way (e.g. a manual `helm upgrade` with a hand-computed tag) — redeploy through
     `deploy.sh`, whose check confirms each image exists first.
   - **403 / denied** — the image exists (Step 8's check, which uses your own credentials,
     passed), but the nodes can't read it: check the nodes' service account has
     `roles/artifactregistry.reader` on the repository (Step 9's GCP setup), and that the node
     pools' access scopes include `devstorage.read_only` or `cloud-platform`.
8. **A rerun fails with `another operation (install/upgrade/rollback) is in progress`** — Helm
   refuses because the release's last revision is still `pending-*`. Either another deploy or
   rollback on this release is still running, or an earlier one was cut off before Helm could
   record the outcome (terminal or SSH session closed, process killed, cluster connection lost
   for good, or the cluster unreachable just when Helm tried to record it). A Ctrl+C during
   `deploy.sh`'s `helm upgrade --install` is normally handled: Helm records the revision
   `failed`; if it still shows `pending-upgrade`, Helm didn't get the signal — treat it as cut
   off (below). Check the release's history with
   `helm history cafe -n cafe --kube-context gke_cafe-microservices_us-central1-a_cafe-cluster`.
   A live operation can't stay pending much past its 22-minute timeout, so if the pending
   revision's UPDATED time is within that plus a few minutes (about 25 minutes), another deploy
   may still be running — wait and check again. Once it's older, the operation was cut off:
   - **`pending-upgrade`** — roll back to the last `deployed` revision (if none is `deployed`,
     uninstall as for `pending-install`):
     `helm rollback cafe <revision> -n cafe --kube-context gke_cafe-microservices_us-central1-a_cafe-cluster --wait=watcher --timeout 22m`,
     then rerun.
   - **`pending-rollback`** (`helm rollback` doesn't handle Ctrl+C, so an interrupted one stays
     pending) — rerun that rollback, to the revision its "Rollback to N" description names, with
     the same command. Not to the last `deployed` revision: that may be the bad one it was
     rolling away from.
   - **`pending-install`** (the very first install never finished) — remove it:
     `helm uninstall cafe -n cafe --kube-context gke_cafe-microservices_us-central1-a_cafe-cluster`,
     then rerun. Postgres and Kafka live outside the release (Step 6) and are untouched; the
     synced Secrets come back once the pods mount the CSI volume again.
   - **`deployed` or `failed`** — the other operation has finished since; just rerun `deploy.sh`.
9. **The new revision itself is bad** (the upgrade failed, or it succeeded but the services
   misbehave) — the usual fix is to commit a fix and redeploy. To get back to a working state
   first, prefer checking out the last good `master` commit (`helm history`'s description names
   the commit of each deployed revision; for a "Rollback to N" revision, read revision N's
   description, repeating if N is itself a rollback) and rerunning `deploy.sh`: its images already
   exist. Otherwise roll back to an explicit revision — after a failed upgrade, the one still
   `deployed`; after a successful but bad one, the most recent `superseded` — with
   `helm rollback cafe <revision> -n cafe --kube-context gke_cafe-microservices_us-central1-a_cafe-cluster --wait=watcher --timeout 22m`
   (a bare `helm rollback` targets the previous revision even if it failed; if the very first
   install never succeeded there is nothing to roll back to). Either way only images and
   manifests go back, not the database schema: Flyway's default `*:future` ignore lets the older
   image start past migrations it doesn't know, but Hibernate's `ddl-auto: validate` fails its
   startup if a column or table it maps was dropped, renamed or retyped, and a new `NOT NULL`
   column without a default breaks its inserts at runtime. Kafka events the newer version already
   published may also fail to deserialize in the older consumers and land in the DLQ. Nothing
   replays records in `<topic>.dlq`; they stay for diagnosis. Orders stuck waiting on a reserve
   or commit reply are re-sent their command by order-service's saga reconciliation job and,
   after its retry limit, put back to OPEN or CONFIRMED. A dead-lettered release-stock command is
   not retried, so that stock stays reserved until corrected by hand. If it was the reply that
   was dead-lettered, inventory-service already applied the command: the order goes back to OPEN
   or CONFIRMED while the stock stays reserved or deducted — correct it by hand too. Item 8
   covers a release left `pending-*`.
10. **A DB-backed pod stays in `Init:0/1`** — its `wait-for-db` initContainer can't connect to the
    database yet. Read why with `kubectl logs <pod> -n cafe -c wait-for-db`: it logs psql's error
    whenever it changes (a connection timeout or refused connection means Postgres isn't up or
    reachable; on the first deploy an authentication error is expected for a while, until CNPG
    creates the role from the newly synced Secret — see item 6). After 600s it exits with the last
    error and restarts with a fresh window; the Deployment's 1200s `progressDeadlineSeconds` then
    fails the rollout if it never gets through.

---

## Step 9 — CI pipeline

Builds and pushes each service's image to Artifact Registry on every push to `master` on which
the `test` job runs (see "What the workflow does" below), or via a manual `workflow_dispatch` on
`master` — only after that same run's lint/test/coverage checks and secret scan pass.
Everything below is already implemented in
[backend-ci.yml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/.github/workflows/backend-ci.yml)
and [image-tag.sh](https://github.com/tanhutminh/cafe-microservice-project/blob/master/scripts/image-tag.sh)
— this section documents the GCP-side setup those files assume, and how the pieces fit together.

### GCP setup

```bash
PROJECT_ID=cafe-microservices
REGION=us-central1
AR_REPO=cafe-images
CI_SA=github-actions-ci
WIF_POOL=github-actions-pool
WIF_PROVIDER=github-actions-provider
GH_REPO=tanhutminh/cafe-microservice-project

# The registry the workflow pushes to. --immutable-tags: a pushed content-hash tag can never be
# moved to a different image, so what deploy.sh checked is what the nodes pull. It also means a
# tagged image can't be deleted or untagged - turn immutability off first if you ever must.
gcloud artifacts repositories create "$AR_REPO" \
  --repository-format=docker --location="$REGION" --project="$PROJECT_ID" \
  --description="Backend service images" --immutable-tags

# A repository created before without the flag: turn it on, then confirm (prints True)
gcloud artifacts repositories update "$AR_REPO" \
  --location="$REGION" --project="$PROJECT_ID" --immutable-tags
gcloud artifacts repositories describe "$AR_REPO" \
  --location="$REGION" --project="$PROJECT_ID" --format='value(dockerConfig.immutableTags)'

# Nodes need to pull from it. A node pool with no --service-account set at creation uses the
# Compute Engine default SA - `gcloud container node-pools describe ... --format="value(config.serviceAccount)"`
# then literally prints "default", not the real email; the real principal is always
# <project-number>-compute@developer.gserviceaccount.com.
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format="value(projectNumber)")
gcloud artifacts repositories add-iam-policy-binding "$AR_REPO" \
  --location="$REGION" --project="$PROJECT_ID" \
  --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role=roles/artifactregistry.reader

# A dedicated GSA for CI to push as - never a static key, see Workload Identity Federation below
gcloud iam service-accounts create "$CI_SA" --project="$PROJECT_ID" \
  --display-name="GitHub Actions CI (backend image build+push)"
gcloud artifacts repositories add-iam-policy-binding "$AR_REPO" \
  --location="$REGION" --project="$PROJECT_ID" \
  --member="serviceAccount:${CI_SA}@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role=roles/artifactregistry.writer

# Workload Identity Federation for GitHub Actions - a separate trust setup from the per-pod one
# in Step 2 (that one lets a K8s pod act as a GSA; this one lets a GitHub Actions run act as one,
# with no per-pod-equivalent component). The attribute-condition restricts it to this exact repo
# AND to runs whose ref is master (here, a push or a workflow_dispatch on master; a pull_request
# run's ref is refs/pull/<n>/merge), not just anyone who learns the provider's resource name.
gcloud iam workload-identity-pools create "$WIF_POOL" \
  --project="$PROJECT_ID" --location=global --display-name="GitHub Actions"
gcloud iam workload-identity-pools providers create-oidc "$WIF_PROVIDER" \
  --project="$PROJECT_ID" --location=global --workload-identity-pool="$WIF_POOL" \
  --display-name="GitHub Actions OIDC" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref" \
  --attribute-condition="assertion.repository=='${GH_REPO}' && assertion.ref=='refs/heads/master'" \
  --issuer-uri="https://token.actions.githubusercontent.com"
gcloud iam service-accounts add-iam-policy-binding \
  "${CI_SA}@${PROJECT_ID}.iam.gserviceaccount.com" --project="$PROJECT_ID" \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${WIF_POOL}/attribute.repository/${GH_REPO}"

# The workflow file needs this exact resource name in its workload_identity_provider field
gcloud iam workload-identity-pools providers describe "$WIF_PROVIDER" \
  --project="$PROJECT_ID" --location=global --workload-identity-pool="$WIF_POOL" \
  --format="value(name)"
```

No GitHub Secret is needed for any of this — Workload Identity Federation exchanges GitHub's own
OIDC token for a short-lived GCP access token at request time, so there is no static credential
to store or leak in the first place.

### What the workflow does

Five jobs, all in [backend-ci.yml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/.github/workflows/backend-ci.yml):

- **`changes`** — [dorny/paths-filter](https://github.com/dorny/paths-filter) computes five
  separate outputs, so the other jobs can skip when they're not relevant (this is the one place
  that lists the paths; the bullets below refer to the outputs by name):
  - `backend` — `backend/**`;
  - `scripts` — `scripts/**` or the root `.gitignore`, whose patterns `deploy.sh`'s repo-state
    check and `deploy.test.sh`'s cases rely on;
  - `charts` — `charts/**`;
  - `k8s` — `k8s/**`;
  - `workflow` — the workflow file itself. Every job and step gated on the other outputs also
    runs when it is true, so a PR editing any step exercises that step before merging (and
    `deploy.test.sh`, which reads the workflow file, reruns).

  On a PR the filter compares against the base branch; on a push to `master`, against the branch
  tip before that push (so a multi-commit push is judged as a whole). Deliberately has **no path
  filter on the workflow's own trigger** (`on.push`/`on.pull_request`) — that would make the
  whole workflow, not just a job, never run for an unrelated PR (e.g. frontend-only), and once
  `changes`/`gitleaks`/`test`/`validate-manifests` are required status checks (see Branch
  protection below), a PR with no check run for them is blocked from merging forever, not just
  correctly skipped.
- **`gitleaks`** — secret scan (see `.gitleaksignore` below). Runs unconditionally on every run of
  the workflow (push to `master`, PR or `workflow_dispatch`), with no path filter — a secret can
  land in any file type (a pasted credential in a doc, a stray key in a YAML manifest), not just
  backend Java source, so it isn't gated behind `changes` the way `test`/`validate-manifests`
  are.
- **`test`** — only runs when the `backend`, `scripts`, `charts`, `k8s` or `workflow` output is true
  (or on `workflow_dispatch`); `charts` and `k8s` because `deploy.test.sh` checks `deploy.sh`
  against files under `charts/` and `k8s/data-layer/`, and `charts` also because this job lints and
  tests the chart's own `wait-for-db` initContainer script. It runs two independent groups of
  checks; each runs even when the other failed, so one failure never hides the other's result:
  - the script checks: `shellcheck` over every script in `scripts/` and in the charts' `files/`,
    then the three test suites, `scripts/image-tag.test.sh`,
    `charts/cafe-service/files/wait-for-db.test.sh` and `scripts/deploy.test.sh`. shellcheck runs
    from an image pinned by digest; the same command lints locally:

    ```bash
    docker run --rm -v "$PWD:/mnt:ro" -w /mnt koalaman/shellcheck:v0.11.0@sha256:61862eba1fcf09a484ebcc6feea46f1782532571a34ed51fedf90dd25f925a8d -x scripts/*.sh charts/*/files/*.sh
    ```

    (on Git Bash, prefix it with `MSYS_NO_PATHCONV=1`). `wait-for-db.test.sh` runs the real
    `wait-for-db.sh` under `sh` with fake `psql`, `date` and `sleep` executables: what it logs
    and when, the 600s boundary, that `psql` gets no connection flags and that the clock is only
    read as `date +%s`. Each run of the script is capped at 10s by `timeout`, so a loop that never
    ends fails its case instead of hanging CI. It also checks that the Deployment template gives
    the initContainer `PGHOST`, `PGUSER`, `PGPASSWORD` and `PGDATABASE` (the user and password
    from the credentials Secret's `username`/`password` keys, the host and database from the
    chart's `db.host`/`db.name`) and a positive `PGCONNECT_TIMEOUT`.
    `deploy.test.sh` unit-tests `deploy.sh`'s functions (image-reference and `--set-string`
    assembly, tags computed at the git ref it is given, the bash-version, tool, Helm-version and
    gcloud-startup checks, and that `main` runs every check in the documented order, before building
    the chart), then runs the whole script in a throwaway git repo with fake `kubectl`, `helm`,
    `gcloud` and `gke-gcloud-auth-plugin` executables that record every call, argument by argument.
    Git there is isolated from your own git config and environment, and guarded so it can only act
    on that throwaway repo. Each guard (uncommitted, git-hidden or git-ignored files, an unreadable
    `HEAD` commit, `HEAD` not on `origin/master`, Helm version, a `gcloud` that fails to start,
    kube-context, a tag that can't be computed, missing image) must abort before any later call; a
    fully successful run must make exactly the expected calls in order, with nothing on stderr and
    nothing on stdout but the chart build's, the upgrade's and the pod and Secret listings' output;
    and a failed upgrade must still point to the runbook. The real tools are never invoked (see Step
    8 for what `deploy.sh` does). `deploy.test.sh` also fails if the registry path or the list of 6
    services drifts between `deploy.sh`, `charts/cafe` and this workflow, all of which repeat them;
    if `k8s/data-layer/` names no namespace, or any other than the one `deploy.sh` deploys into
    (`cafe`); if the chart's DB-backed `progressDeadlineSeconds` no longer covers `wait-for-db`'s
    window (read from `wait-for-db.sh`, which the template must inline exactly once) plus the
    startup probe plus 2 minutes; or if `deploy.sh`'s Helm timeout doesn't exceed the largest
    deadline in effect (counting the gateway's 600s default) by at least 2 minutes.
  - the Maven checks, when the `backend` or `workflow` output is true, on every push to
    `master` on which `test` runs, or on `workflow_dispatch`, each needing the previous one to
    pass: `spotless:check` (format, meaningful only on a `pull_request` run — see the Spotless
    note below), the full `mvn test` reactor, and `mvn jacoco:check` against the five
    modules that opt into a coverage floor (each module's own `pom.xml` sets
    `jacoco.line.coverage.minimum` — a no-regression ratchet: it matches that module's own
    current coverage, or the parent's 70% default for a module already at or above it, and only
    ever moves up as coverage improves).

  A PR that changes neither backend code nor the workflow file therefore skips the roughly
  two-minute Maven run, while `test` stays a single required check either way. On `master` the Maven
  checks run whenever `test` does, because that is where `build-and-push` runs: every image it
  pushes comes from a run that tested the backend tree it was built from, even when the push itself
  only touched scripts, chart files, manifests under `k8s/` or the root `.gitignore` (which holds
  ignore patterns for the whole repo, so an edit made for another part of it, e.g. the frontend,
  costs that Maven run too; `build-and-push` then finds every image already present).
- **`validate-manifests`** — guards against a CNPG/Strimzi/Barman resource or a StorageClass ever
  being added under `charts/*/templates/` (that data layer stays outside any Helm release, see
  "Architecture at a glance"), then `helm dependency build --skip-refresh` (`build` rather than
  `update`, so an out-of-sync `Chart.lock` fails the check instead of being silently regenerated in
  the runner), a check that no test suite (`*.test.sh`) leaked into the packaged `cafe-service`
  subchart (its `.helmignore` keeps them out), and `helm lint`/`helm template` (one render per
  service, each checking that the service's ConfigMap turns Zipkin span export off), with Helm
  pinned to v4.3.0 for reproducible results (these run whenever the `charts`, `k8s` or `workflow`
  output is true, or on `workflow_dispatch`), then — only when the `k8s` or `workflow` output is
  true, or on `workflow_dispatch` — `kubeconform` against `k8s/data-layer/*.yaml`. kubeconform
  bundles no schemas; it fetches the built-in kinds' from
  [yannh/kubernetes-json-schema](https://github.com/yannh/kubernetes-json-schema) and the
  CNPG/Strimzi/Barman ones from the community
  [CRDs-catalog](https://github.com/datreeio/CRDs-catalog), both pinned to a commit. Nothing updates
  those pins automatically: refresh them with `git ls-remote <repo> HEAD`, and always refresh the
  CRDs-catalog one when the CNPG, Strimzi or barman-cloud version in `k8s/` changes, or the
  manifests are checked against old CRD schemas.
- **`build-and-push`** — needs both `test` and `gitleaks` to succeed, and only runs on a push (or
  manual `workflow_dispatch`) to `master`, never on a PR. Its image build only packages
  (`-DskipTests`): the tests run once, in `test`. For each of the 6 services: compute its tag with
  `scripts/image-tag.sh <service>` (a content hash of that service's own directory, `common-lib` and
  the parent pom — the exact inputs its `Dockerfile` copies; see the script's own header comment for
  what that deliberately excludes and for the `salt` constant — bump it to force every service's tag
  to change when nothing in those hashed inputs did, e.g. after a base-image security update), check
  whether Artifact Registry already has an image at that tag (`docker manifest inspect`), and only
  build+push if not. This makes the job idempotent: a `workflow_dispatch` run on `master`, or any
  later push to `master` on which `test` runs, builds whatever is missing once that run's own Maven
  checks pass, regardless of what did or didn't get rebuilt on any prior run — including content
  left unbuilt because an earlier run's `test` job failed, which a plain "did this commit touch this
  service" check would otherwise permanently miss. A push on which `test` doesn't run (say, docs
  only) builds nothing, so after a failed run, trigger `workflow_dispatch` on `master` if the images
  are needed before the next backend change. The repository's immutable tags (GCP setup above)
  refuse any push that would move an existing tag, so if `docker manifest inspect` fails transiently
  for an image that does exist, the rebuilt image (with a different digest) is refused and the job
  fails — rerun the failed job. Two runs of this job never work on the same service at once (a
  per-service `concurrency` group), so two `master` runs close together don't both rebuild an
  unchanged service and have the second push refused. With `queue: max`, runs of this job for one
  service wait in the group rather than replacing each other, so whichever runs second checks only
  after the first has finished, and skips the build if the first pushed the image.

Every job sets `timeout-minutes` (5 to 20 minutes, against GitHub's 360-minute default), so a hung
image pull or push fails the check instead of holding a required status pending for hours; the
script steps in `test` have their own limit too: 2 minutes, or 5 for `deploy.test.sh`, which
runs `deploy.sh` end to end dozens of times.

**Why `test`'s Spotless check only means something on a `pull_request` run**: the project's
`ratchetFrom: origin/master` setting only checks files that differ from `origin/master` — on a
`push` to `master` itself, that diff is empty (the branch is being compared to itself), so the
step trivially passes without checking anything. It does real work on a PR, where the PR branch
genuinely differs from `origin/master`. This is why branch protection (below) requires a PR for
every change — a direct push to `master` would bypass Spotless entirely, not just skip a
redundant re-check.

**`.gitleaksignore`** carries the fingerprints of the project's own deliberately-committed dev
JWT keypair (see Step 5) — without it, a `workflow_dispatch` run (the only trigger that scans
full history rather than just the pushed commits) would fail on a secret the project has already
decided to keep public. Regenerate its fingerprints with `gitleaks detect --report-format json`
if that keypair, or any other already-accepted dev credential, is ever moved to a different file
or line.

### Branch protection

GitHub Settings → Branches → add a rule for `master`:

- **Require a pull request before merging** — see the Spotless note above for why this matters,
  not just as general good practice.
- **Require status checks to pass before merging** → add `changes`, `gitleaks`, `test` and
  `validate-manifests` (they only appear once each has run at least once — merge the PR that adds
  this workflow first, or trigger one `workflow_dispatch` run, before configuring this).
  `changes` is required too because `test` and `validate-manifests` need it: if it fails, both
  are skipped, and GitHub reports a skipped job as a success that satisfies a required check.
  **Do not** add `build-and-push` — it never runs on a PR at all, so a PR would show it as
  "Expected — Waiting for status to be reported" forever, with no way to satisfy it.
- **Do not allow bypassing the above settings** — without this, anyone with admin access
  (including the repository owner) can still push straight to `master`, which is exactly the
  path the first bullet exists to close.

### First run and verifying it worked

The very first run has nothing to compare against yet (no images exist), and `build-and-push`'s
own idempotency check only helps once something is already in the registry — trigger one
manually once the workflow file and branch protection are both in place:

```bash
# GitHub UI: Actions -> backend-ci -> Run workflow, branch = master
# or, with the GitHub CLI:
gh workflow run backend-ci.yml --ref master
```

Confirm all 6 images landed:

```bash
gcloud artifacts docker images list \
  us-central1-docker.pkg.dev/cafe-microservices/cafe-images \
  --include-tags --project=cafe-microservices
```

From here on, an ordinary push to `master` that touches `backend/**` only rebuilds the services
whose content actually changed (or all 6, if `common-lib`/the parent `pom.xml` changed) — see
Step 8 to deploy it (`scripts/deploy.sh`).

## Pausing and resuming the cluster between sessions

Nothing here needs to run around the clock, so between work sessions both node pools can go down to
0 nodes. What still bills then: the Postgres and Kafka PVCs' persistent disks, the GCS backup
bucket, Artifact Registry storage and the Secret Manager secret versions. The zonal cluster's
control-plane fee stays offset by the free-tier credit (see the Zonal cluster row in the GCP ↔ AWS
appendix).

`stateless-pool`'s autoscaler can't get there by itself: GKE's system pods and the Step 3 operators
(cert-manager, the CNPG operator with its Barman Cloud Plugin, and Strimzi) always need somewhere to
run, so it settles at 2-3 nodes (see "GKE system pods added automatically per node"). Pausing
therefore turns its autoscaling off and resizes it by hand — GKE's own guidance is not to mix the
cluster autoscaler and manual resizes on one node pool.

### Pausing

Stop the services first, so no Postgres or Kafka client is left; then suspend the nightly backup
schedule and back Postgres up, so the bucket holds everything written before the pause and no
scheduled backup is left running when Postgres shuts down; then hibernate Postgres, so
CloudNativePG shuts it down cleanly and keeps its PVC; then remove the nodes, `stateful-pool`
before `stateless-pool` — the reverse of Resuming's order. Run these blocks, and a rehearsal's, in
one shell: later blocks read variables (`$backup`, `$counts_dir`, `$counts_query`) that earlier
ones set.

Stop the services and wait until their pods are gone (`kubectl scale` returns before they have
shut down):

```bash
kubectl scale deployment gateway auth-service menu-service order-service inventory-service report-service --replicas=0 -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
kubectl wait --for=delete pod -l 'app in (gateway,auth-service,menu-service,order-service,inventory-service,report-service)' -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=120s
```

To rehearse a restore during this pause, record the row counts now (step 1 of "Rehearsing a
restore"), before the backup.

Suspend the nightly schedule, then back Postgres up with an on-demand `Backup` and wait for it to
finish:

```bash
backup="cafe-postgres-on-demand-$(date -u +%Y%m%d-%H%M%S)"
kubectl patch scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --type=merge -p '{"spec":{"suspend":true}}' &&
  kubectl create -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -f - <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata:
  name: ${backup}
  labels: {app.kubernetes.io/managed-by: kubectl}
spec:
  cluster: {name: cafe-postgres}
  method: plugin
  pluginConfiguration: {name: barman-cloud.cloudnative-pg.io}
EOF
phase=""
for _ in $(seq 90); do
  phase=$(kubectl get backup.postgresql.cnpg.io "$backup" -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.status.phase}') || { phase="kubectl error"; break; }
  case "$phase" in ""|pending|started) sleep 10 ;; *) break ;; esac
done
echo "$backup: ${phase:-no phase yet}"
```

The loop waits up to 15 minutes and prints the final phase. A backup like this one goes from
`pending` to `started` to `completed` or `failed`; it stays `pending` while another backup of the
cluster is still running (such as a nightly one that started before the suspension, or the
catch-up one a resume can start), and those 15 minutes include that wait. CNPG runs a cluster's
backups one at a time, oldest first, and the suspension stops new ones from being created, so once
this backup completes, none is left running for the hibernation to cut off. Only `completed` lets
the next block run; on anything else the loop prints:

- `pending` or `started`: the backup is still going. Rerun the loop from its `phase=""` line,
  which keeps the same `$backup`.
- `no phase yet`: the CNPG operator hasn't picked the backup up. Check that the pods in
  `cnpg-system` are running, then rerun the loop the same way.
- `kubectl error`: kubectl printed why above. If the `Backup` doesn't exist (the schedule patch or
  `kubectl create` failed), start again from the backup block; otherwise rerun the loop from its
  `phase=""` line.
- `failed` or anything else: the reason is in
  `kubectl get backup.postgresql.cnpg.io "$backup" -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.status.error}'`.
  The cluster keeps running, with the nightly schedule suspended, so fix the cause and start again
  from the backup block — or, to give the pause up, see "Troubleshooting resume", item 5.

Then hibernate Postgres:

```bash
[[ $(kubectl get backup.postgresql.cnpg.io "${backup:?run the backup block in this shell first}" -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.status.phase}') == completed ]] || { echo "the backup is not completed (see the backup block's phases)" >&2; false; } &&
  kubectl annotate cluster cafe-postgres cnpg.io/hibernation=on --overwrite -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster &&
  kubectl wait cluster/cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=condition=cnpg.io/hibernation --timeout=5m
```

If this block stops at its backup check, go back to the backup block (see what its final phase
means above). If Postgres didn't finish hibernating within 5 minutes, this shows how far it got:

```bash
kubectl get cluster cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.status.conditions[?(@.type=="cnpg.io/hibernation")].reason}{"\n"}'
```

- `Hibernated`: it has finished in the meantime.
- `DeletingPods` or `WaitingPodsDeletion`: Postgres is still shutting down.
- `WaitingForHealthy`: CNPG doesn't start hibernating until the Cluster is healthy
  (`kubectl get cluster cafe-postgres` shows its status), and then starts by itself.
- An empty line: the CNPG operator hasn't acted on the annotation yet — check that the pods in
  `cnpg-system` (the operator and the Barman Cloud Plugin) are running.

Then rerun the hibernation block from its `kubectl wait` line.

To rehearse a restore, do it now — Postgres is down, which frees the room the scratch Cluster
needs on `stateful-pool` — and come back for the last block (see "Rehearsing a restore").

Finally remove the nodes. The lines before the resizes check again that the nightly schedule is
suspended, Postgres hibernated and no scratch Cluster is left:

```bash
[[ $(kubectl get scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.suspend}') == true ]] || { echo "the nightly schedule is not suspended (see the notes after this block)" >&2; false; } &&
  kubectl wait cluster/cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=condition=cnpg.io/hibernation --timeout=10s &&
  kubectl wait --for=delete cluster.postgresql.cnpg.io/cafe-postgres-restore -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=10s &&
  kubectl wait --for=delete pod -l cnpg.io/cluster=cafe-postgres-restore -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=2m &&
  gcloud container clusters resize cafe-cluster --node-pool=stateful-pool --num-nodes=0 --zone=us-central1-a --quiet &&
  gcloud container node-pools update stateless-pool --cluster=cafe-cluster --zone=us-central1-a --no-enable-autoscaling &&
  gcloud container clusters resize cafe-cluster --node-pool=stateless-pool --num-nodes=0 --zone=us-central1-a --quiet
gcloud compute instances list --filter="name~^gke-cafe-cluster-"
```

The last command should list no instances. If one of the checks fails, nothing has been resized and
both pools keep running. If a later command fails, fix the cause and rerun the whole block: the
checks still pass, and resizing a pool that is already at 0 nodes changes nothing. If the nightly
schedule isn't suspended, suspend it with
`kubectl patch scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --type=merge -p '{"spec":{"suspend":true}}'`.
If Postgres isn't hibernated, finish the hibernation block first (see the notes after it). If a
scratch `cafe-postgres-restore` is still there, run step 4 of "Rehearsing a restore", then rerun
this block.

Don't resize `stateful-pool` without hibernating first: the Postgres instance's PodDisruptionBudget
blocks the node drain for up to an hour, after which Postgres is killed without a clean shutdown.
Kafka needs nothing special — its single broker pod is evicted when `stateful-pool`'s node is
drained, Strimzi recreates it at once, and the new pod stays `Pending` until `stateful-pool` has a
node again.

The nightly `ScheduledBackup` (`cafe-postgres-daily-backup`, 00:00 UTC) stays suspended from the
backup block until Resuming lifts it once Postgres is ready: hibernating would cut off a nightly
backup still running and mark it failed, and CNPG can't back up a hibernated cluster anyway. The
backup taken before hibernating covers the pause. If a 00:00 UTC slot passed during the pause,
CNPG creates at most one catch-up backup as soon as the schedule is back, named after the first
00:00 UTC slot it missed, and it succeeds because Postgres is up — so each session normally starts
with a second base backup, which costs little. Retention (`retentionPolicy: 10d` in
`postgres-backup.yaml`) runs every 30 minutes while Postgres runs and never deletes the newest
completed backup, however long a pause lasts. The `Backup` objects of this cluster's completed
backups are deleted together with their data once retention removes it; failed ones stay until
you delete them
(`kubectl get backup.postgresql.cnpg.io -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster`
lists them).

### Resuming

In reverse: `stateless-pool` first, so the operators and GKE's system pods have somewhere to run —
the CNPG operator must be up to wake Postgres — then `stateful-pool`, then Postgres, then the
services, inventory-service first: order-service's saga reconciliation re-sends at once the
commands of orders left mid-saga by the pause and gives up after a few minutes (see
Prerequisites), so inventory-service should already be consuming them:

```bash
gcloud container clusters resize cafe-cluster --node-pool=stateless-pool --num-nodes=1 --zone=us-central1-a --quiet
gcloud container node-pools update stateless-pool --cluster=cafe-cluster --zone=us-central1-a --enable-autoscaling --min-nodes=0 --max-nodes=6
gcloud container clusters resize cafe-cluster --node-pool=stateful-pool --num-nodes=1 --zone=us-central1-a --quiet
for ns in cert-manager cnpg-system strimzi-system; do
  kubectl wait --for=condition=Available deployment --all -n "$ns" --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=300s
done
kubectl annotate cluster cafe-postgres cnpg.io/hibernation=off --overwrite -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
kubectl get cluster cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.metadata.annotations.cnpg\.io/hibernation}{"\n"}'
kubectl wait cluster/cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=jsonpath='{.status.readyInstances}'=1 --timeout=10m &&
  kubectl patch scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --type=merge -p '{"spec":{"suspend":false}}'
kubectl get scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.suspend}{"\n"}'
kubectl wait pod/cafe-kafka-cafe-kafka-pool-0 -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=condition=Ready --timeout=10m
kubectl scale deployment inventory-service --replicas=1 -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
kubectl wait --for=condition=Available deployment/inventory-service -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=22m &&
  kubectl scale deployment gateway auth-service menu-service order-service report-service --replicas=1 -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster &&
  kubectl wait --for=condition=Available deployment --all -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=22m
```

The annotation check must print `off`, and the schedule check `false`; the schedule is lifted only
once Postgres is ready, so the catch-up backup it may start finds Postgres up. A failed resize or
a timed-out wait doesn't stop the lines after it — except that the other services are scaled up
and waited for only once inventory-service is available. If a wait times out, fix the cause (see
"Troubleshooting resume"), then rerun the block from that wait; every line from the first wait on
is safe to repeat. For a failed resize, see item 3 there. Kafka's wait reads the broker pod rather
than the `Kafka` resource, whose `Ready` condition can still be `True` from before the pause.
`--replicas=1` matches the chart's `replicas` value, and the 22-minute timeout (as in
`scripts/deploy.sh`) outlasts the DB-backed Deployments' 1200s `progressDeadlineSeconds`, which
their slowest legitimate start is sized to fit; `kubectl wait` doesn't stop at that deadline, so a
stalled service shows up as one of these waits timing out.

`bash scripts/deploy.sh` (Step 8) brings the services back too, but it also deploys the checked-out
commit, which may differ from what was running, and it doesn't wake Postgres or lift the
schedule's suspension itself. It starts all six services at once, so to keep inventory-service
ahead of order-service, resume with the whole block above first and run the script afterwards.
Until the services run, the CNPG `Cluster` may report missing role password Secrets — see
"Troubleshooting deploys", item 6.

### Troubleshooting resume

1. **`kubectl get cluster cafe-postgres` shows
   `Cluster cannot proceed to reconciliation due to an error while interacting with plugins`, and no
   `cafe-postgres-1` pod appears** — first check that the hibernation annotation really reads `off`
   (the check under Resuming): while Postgres is still hibernated, the status can keep showing an
   older error, so this message on its own says little.
2. **The operator wait stalls, and the CNPG operator logs
   `name resolver error: produced zero addresses`** — a Spot `stateless-pool` node was reclaimed,
   taking the CNPG operator, the Barman Cloud Plugin and cert-manager with it: `kubectl get nodes`
   shows the node `NotReady`, its pods show `Completed`, and
   `kubectl get endpointslices -n cnpg-system` lists no addresses. It heals by itself once
   replacement pods are scheduled on a Ready node; rerun the wait. (`kubectl get endpoints` is
   deprecated and can lag behind; read the EndpointSlices.)
3. **A resize fails with `ZONE_RESOURCE_POOL_EXHAUSTED`** — the zone is temporarily out of
   `e2-medium` capacity. The lines after it still run, and the waits time out (Ctrl+C stops them).
   Wait a while, then rerun the Resuming block from the `stateful-pool` resize. If the
   `stateless-pool` resize was the one that failed, don't repeat it: the autoscaling enabled by the
   next line adds a node for the `Pending` pods once capacity returns, and a manual resize would
   remove any node it has added beyond the first.
4. **A pod's CSI volume fails to mount on a fresh node** — see "Troubleshooting deploys", item 1.
5. **A pause was given up partway** (services stopped; the schedule perhaps suspended and Postgres
   hibernated; nodes still up) — bring everything back with the Resuming lines from the hibernation
   annotation on, including the schedule patch; nothing else lifts the schedule's suspension. If
   Postgres never hibernated, those lines change nothing but the schedule and the services. If a
   restore rehearsal was left behind too
   (`kubectl get cluster.postgresql.cnpg.io cafe-postgres-restore -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster`
   finds it), first delete it with step 4 of "Rehearsing a restore": its 250m CPU request leaves
   `stateful-pool` no room for `cafe-postgres-1`, which then stays `Pending`.

---

## Restoring Postgres from a backup

The backups in `gs://cafe-microservices-cafe-pg-backups` (base backups plus continuous WAL, see
Step 6 and "Pausing") are the supported way to get Postgres back. A restore always builds a *new*
CNPG `Cluster` from the bucket — `bootstrap.recovery` instead of `initdb` — and never touches the
backups it reads. How much data can be lost — what a *recovery point objective* (RPO) sets a limit
for: while Postgres runs, at most the WAL not yet archived, which CNPG closes and archives at least
every 5 minutes (`archive_timeout`); across a pause, nothing, since the pause starts with a
completed backup. How long a restore takes — what a *recovery time objective* (RTO) sets a limit
for — is what the rehearsal below measures.

### Rehearsing a restore

An occasional manual check that the backups really restore, done during a pause: once Postgres is
hibernated, `stateful-pool` has room for the scratch Cluster, which needs the same 250m CPU request
as the real one. Repeat it after changing the Postgres major version, the Barman Cloud Plugin, the
bucket or its IAM. It restores into a separate `cafe-postgres-restore` Cluster that reads the
bucket as the read-only `cafe-postgres-restore` GSA (Step 2) and archives nothing, then deletes it.

1. During "Pausing", after the service pods are gone and before the backup, record every table's
   exact row count:

   ```bash
   counts_dir="$(mktemp -d)"
   counts_query="select table_schema||'.'||table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) c from %I.%I', table_schema, table_name), false, true, '')))[1]::text from information_schema.tables where table_type = 'BASE TABLE' and table_schema not in ('pg_catalog', 'information_schema') order by 1"
   for db in auth_db menu_db order_db inventory_db report_db; do
     echo "== $db"
     kubectl exec cafe-postgres-1 -c postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -- psql -d "$db" -Atc "$counts_query"
   done > "$counts_dir/before.txt"
   ```

2. Continue "Pausing" through the hibernation block, then create the scratch Cluster. It takes its
   image, resources and disk size from the live `cafe-postgres` and mirrors its placement, but uses
   GKE's `standard` StorageClass (`Delete` reclaim, so deleting it leaves no disk behind), has no
   `plugins` block (so it neither archives WAL nor applies retention to the bucket), and restores
   `auth_db` owned by `auth_service` as the bootstrap database (otherwise CNPG creates an extra
   `app` database and role). `serverName` names the backups to read — without it, the plugin looks
   under the new cluster's own name; the block reads it from the live `cafe-postgres` plugin entry
   (the Cluster name when unset, the plugin's default):

   ```bash
   restore_start=$(date +%s)
   server_name=$(kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.plugins[?(@.name=="barman-cloud.cloudnative-pg.io")].parameters.serverName}') &&
     image=$(kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.imageName}') &&
     resources=$(kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.resources}') &&
     storage_size=$(kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.storage.size}') &&
     kubectl apply -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -f - <<EOF &&
   apiVersion: postgresql.cnpg.io/v1
   kind: Cluster
   metadata:
     name: cafe-postgres-restore
     labels: {app.kubernetes.io/managed-by: kubectl}
   spec:
     instances: 1
     imageName: ${image:?cafe-postgres has no spec.imageName}
     resources: ${resources:?cafe-postgres has no spec.resources}
     enableSuperuserAccess: false
     storage: {size: ${storage_size:?cafe-postgres has no spec.storage.size}, storageClass: standard}
     affinity:
       tolerations: [{key: "workload", operator: "Equal", value: "stateful", effect: "NoSchedule"}]
       nodeSelector: {cloud.google.com/gke-nodepool: stateful-pool}
     serviceAccountTemplate:
       metadata:
         annotations: {iam.gke.io/gcp-service-account: "cafe-postgres-restore@cafe-microservices.iam.gserviceaccount.com"}
     bootstrap:
       recovery: {source: origin, database: auth_db, owner: auth_service}
     externalClusters:
       - name: origin
         plugin:
           name: barman-cloud.cloudnative-pg.io
           parameters: {barmanObjectName: cafe-postgres-backup-store, serverName: ${server_name:-cafe-postgres}}
   EOF
     kubectl wait cluster.postgresql.cnpg.io/cafe-postgres-restore -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=condition=Ready --timeout=15m &&
     echo "restore took $(( $(date +%s) - restore_start ))s"
   ```

   CNPG first runs a `cafe-postgres-restore-1-full-recovery` Job, then starts the instance, which
   restarts once by itself (the plugin's sidecar is only needed during recovery); the wait covers
   that restart. The time it prints is the measured restore time. If step 3 still can't reach the
   pod, rerun just the `kubectl wait` line, without its trailing `&&`, then step 3 — not its
   `echo`, which would add the time since. If the wait times out instead, rerun the block's last
   two lines together, right away: the time they print still counts from the block's start, so a
   delay before the rerun adds to it.

3. Count the restored tables the same way and compare:

   ```bash
   [[ -f ${counts_dir:?run step 1 in this shell first}/before.txt && -n ${counts_query:?run step 1 in this shell first} ]] &&
     for db in auth_db menu_db order_db inventory_db report_db; do
       echo "== $db"
       kubectl exec cafe-postgres-restore-1 -c postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -- psql -d "$db" -Atc "$counts_query"
     done > "$counts_dir/restored.txt" &&
     diff "$counts_dir/before.txt" "$counts_dir/restored.txt" && echo "restore matches"
   ```

4. Whatever the outcome of steps 2–3, delete the scratch Cluster and wait until it and its pod are
   gone — its PodDisruptionBudget would otherwise block the `stateful-pool` drain — then finish
   "Pausing" with its last block:

   ```bash
   kubectl delete cluster.postgresql.cnpg.io cafe-postgres-restore --ignore-not-found -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
   kubectl wait --for=delete pod -l cnpg.io/cluster=cafe-postgres-restore -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=5m
   ```

### Recovering after losing the Postgres cluster

When the `cafe-postgres` Cluster and its data are gone, recreate it under the same name, so its
`-rw` Service, its KSA's Workload Identity binding, the `Database` CRs and the `ScheduledBackup`
keep working. Do this only in a real recovery.

First stop the services with Pausing's first block, so none of them writes to the new Cluster
before step 3 has backed it up; if they are already stopped, it changes nothing. If the cluster is
paused, then run Resuming's lines through the operator waits (the `for ns …` loop) — not the
hibernation lines, since the new Cluster isn't hibernated — and delete any leftover
`cafe-postgres-restore` (see "Troubleshooting resume", item 5). After step 4, finish with
Resuming's last five lines (the Kafka wait, inventory-service's scale and wait, then the other
services' scale and wait).

1. Check that nothing of the old cluster is left:

   ```bash
   kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
   kubectl get pvc -l cnpg.io/cluster=cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
   ```

   The first must fail with `NotFound`. If it finds the Cluster, its disk may still hold the data:
   an instance that crash-loops or a full disk is a problem to fix, not a loss, so stop. Only once
   you've decided the data is lost (its PVC is gone, or the disk can't be read) delete the
   Cluster — its PVC goes with it, since the Cluster owns it, while the PV's `Retain` policy keeps
   the disk (see step 5) — then rerun the checks above:

   ```bash
   kubectl delete cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
   kubectl wait --for=delete pvc -l cnpg.io/cluster=cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=5m
   ```

   The second check must print `No resources found in cafe namespace.` A leftover PVC means the old
   disk still exists and may hold newer data than the bucket; rebuilding from it isn't covered
   here. The new Cluster would adopt such a PVC and start on that old disk instead of restoring, so
   to restore from the bucket, delete it first (the `Retain` policy keeps the disk, see step 5):
   `kubectl delete pvc <name> -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster`.

2. In `k8s/data-layer/postgres-cluster.yaml`, replace `initdb: {database: auth_db, owner:
   auth_service}` with `recovery: {source: origin, database: auth_db, owner: auth_service}` and add
   the same `externalClusters` entry as the rehearsal above, whose `serverName` is the plugin
   `serverName` the file had so far (`cafe-postgres` if it had none). Then set the plugin's
   `serverName` in `parameters` to a new name with no prefix in the bucket yet — the first
   `cafe-postgres-rN` (`cafe-postgres-r1`, `-r2`, …) that
   `gcloud storage ls gs://cafe-microservices-cafe-pg-backups/` doesn't list. WAL archiving fails
   if the prefix already holds WAL (which any prefix with backups does). Apply it, then wait for
   its instance and for its first WAL archive:

   ```bash
   kubectl apply -f k8s/data-layer/postgres-cluster.yaml --context gke_cafe-microservices_us-central1-a_cafe-cluster
   kubectl wait cluster.postgresql.cnpg.io/cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=jsonpath='{.status.readyInstances}'=1 --timeout=15m &&
     kubectl wait cluster.postgresql.cnpg.io/cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=condition=ContinuousArchiving --timeout=10m
   ```

   Unlike the rehearsal's, this instance doesn't restart once recovered: the archiving plugin keeps
   its sidecar. If the archiving wait times out, the archive error is in
   `kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.status.conditions[?(@.type=="ContinuousArchiving")].message}{"\n"}'`.
3. Take a first base backup under the new name right away, rather than waiting for the nightly
   schedule: run the backup block from "Pausing" (what its final phase means is explained there),
   and continue only on `completed`. A nightly backup created while the Cluster was gone may run
   first; the loop waits for it like for a catch-up one. Then lift the schedule's suspension, which
   the backup block sets and applying `k8s/data-layer/` never resets, so always run it:

   ```bash
   kubectl patch scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --type=merge -p '{"spec":{"suspend":false}}'
   kubectl get scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.suspend}{"\n"}'
   ```

   The check must print `false`.
4. In `postgres-cluster.yaml`, put `initdb: {database: auth_db, owner: auth_service}` back and
   remove the `externalClusters` entry; keep the new `serverName` in the plugin's `parameters`.
   `bootstrap` is only read when a Cluster is created, so applying this changes nothing for the
   running cluster, and the file stays a recipe for a fresh environment. Apply it, then commit the
   change on a branch and merge it through a PR, so the next `kubectl apply -f k8s/data-layer/`
   from `master` doesn't reset the archive name to the old one:

   ```bash
   kubectl apply -f k8s/data-layer/postgres-cluster.yaml --context gke_cafe-microservices_us-central1-a_cafe-cluster
   ```

5. From then on the backups live under the new name: a rehearsal reads it by itself, and a later
   recovery reads it in `externalClusters` and archives under the next unused name. Nothing prunes
   the old prefix in the bucket. Once you trust the recovered cluster and 10 days (the retention
   window) have passed since the recovery, delete it with
   `gcloud storage rm --recursive gs://cafe-microservices-cafe-pg-backups/<old-serverName>/`
   (`<old-serverName>` is the `serverName` step 2 put in `externalClusters`), together with the
   old cluster's `Backup` objects — retention only manages the current Cluster's, so those keep
   showing `completed` after their data is gone. Deleting a `Backup` object never touches the
   bucket. List them (completed backups taken by any other Cluster UID), review the list, then
   delete them:

   ```bash
   cluster_uid=$(kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.metadata.uid}') &&
     old_backups=$(kubectl get backup.postgresql.cnpg.io -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{range .items[?(@.spec.cluster.name=="cafe-postgres")]}{.metadata.name}{" "}{.status.pluginMetadata.clusterUID}{"\n"}{end}' | awk -v uid="$cluster_uid" '$2 != "" && $2 != uid {print $1}') &&
     printf '%s\n' "$old_backups"
   ```

   ```bash
   [[ -n ${old_backups?run the block above in this shell first} ]] &&
     kubectl delete backup.postgresql.cnpg.io $old_backups -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
   ```

   The old PersistentVolume (reclaim policy `Retain`) stays `Released` and its disk keeps billing
   until you delete both. This lists the PVs with their claim, StorageClass and disk; the old one
   is `Released`, with class `cafe-postgres-retain`:

   ```bash
   kubectl get pv --context gke_cafe-microservices_us-central1-a_cafe-cluster -o custom-columns=NAME:.metadata.name,STATUS:.status.phase,CLAIM:.spec.claimRef.name,CLASS:.spec.storageClassName,DISK:.spec.csi.volumeHandle
   ```

   Delete that PV with
   `kubectl delete pv <name> --context gke_cafe-microservices_us-central1-a_cafe-cluster`, then its
   disk, named by the last segment of its `DISK` path, with
   `gcloud compute disks delete <disk> --zone=us-central1-a` (it asks you to confirm).

The managed roles and `Database` CRs reconcile against the restored roles and databases as usual;
the service password Secrets come back from Secret Manager once the services run.

Kafka isn't restored with Postgres: all five databases go back to the same point, but Kafka keeps
the messages and the consumers' committed offsets from after it:

- Outbox rows still unsent at that point are sent, some a second time, and no duplicate is applied
  twice (inventory-service's inbox checks the correlation ID, order-service's saga its current
  step and correlation ID).
- A message consumed after that point isn't delivered again, though its effect was lost. That
  includes what services still running while Postgres was gone took from Kafka: each message was
  retried for a few minutes at most, then moved to `<topic>.dlq`, which nothing replays (see
  "Troubleshooting deploys", item 9). Nothing new reached Kafka meanwhile, since every saga
  message is sent from a database, so noticing the loss late doesn't add to it.
- An order left waiting on a reserve or commit reply has its command re-sent by order-service's
  saga reconciliation job, up to 3 times a minute or so apart, starting as soon as order-service
  runs; inventory-service answers a command it already applied with the reply it stored.
  Resuming's last five lines start inventory-service first so these re-sends are answered; if all
  3 go unanswered, the order goes back to OPEN or CONFIRMED.
- Stock left to correct by hand: what inventory-service reserves or deducts for such an order when
  it does consume its commands, and stock reserved for no order — through a lost release-stock
  command, or a command for a lost order that inventory-service hadn't consumed yet.

To recover into a new environment from a copy of the bucket (see Prerequisites), set it up with
this guide, putting the bucket that holds the backups into `postgres-backup.yaml`'s
`destinationPath` (see Prerequisites' list of names baked into repo files). A copy in another
paid project's bucket can be used as it is, skipping Step 2's `buckets create`; a local copy goes
into the new bucket with `gcloud storage rsync -r <dir> gs://<new-bucket>`, after which
`gcloud storage ls gs://<new-bucket>/` must list the `<serverName>/` prefixes at the bucket's root.
Before Step 6's `kubectl apply -f k8s/data-layer/`, make step 2's edit to `postgres-cluster.yaml`,
so the new Cluster restores from the bucket instead of running `initdb`. After that apply, run step
2's waits, step 3 and step 4, then carry on from Step 7; this section's intro doesn't apply, since
no service runs yet and Step 8 starts them. Kafka there starts empty, so every message sent but not
yet consumed at the point Postgres goes back to is lost, whether or not the old Kafka delivered it
later; otherwise the bullets above apply. Step 8's `deploy.sh` starts all six services at once, so
if inventory-service comes up a few minutes after order-service, order-service's saga
reconciliation can give up on orders restored mid-saga before inventory-service answers; then check
for stock to correct by hand, as the last bullet above describes.

---

## Appendix: GKE system pods added automatically per node

Every node in this cluster — which has Workload Identity enabled (Step 1) and uses GKE's default
(non-Dataplane V2) datapath — runs a fixed set of mandatory system pods the moment it joins the
cluster. `e2-medium` (2 vCPU / 2000m nominal) has only 940m allocatable regardless of workload,
due to the fixed 1060 mCPU GKE reserves on shared-core E2 machine types (`e2-micro`/`e2-small`/
`e2-medium`) rather than its usual per-core percentage formula; the system pods below then
consume a further chunk of that already-reduced 940m budget before any application, operator,
or CNPG/Strimzi pod ever gets scheduled. This combination is why this project's `stateless-pool`
empirically settles at 2-3 `e2-medium` nodes even with every application Deployment scaled to
zero, rather than dropping to its configured autoscaling minimum of 0: these pods always need
somewhere to run.

**Per-node DaemonSets** (one pod on every node, `kube-system` unless noted):

| Pod | Approx. CPU request (`e2-medium`) | Purpose |
|---|---|---|
| `kube-proxy` | 100m | Implements Service networking (iptables/IPVS rules). |
| `netd` | 8m | GKE's per-node Pod-networking agent (generates the CNI spec from the node's PodCIDR and manages packet redirection). |
| `node-local-dns` | 30m | Per-node DNS cache, reduces load on `kube-dns`. |
| `konnectivity-agent` | 15m | Tunnels API-server-to-node traffic (replaces the old SSH tunnel). |
| `gke-metadata-server` | 100m | Serves Workload Identity metadata to pods on that node. |
| `gke-metrics-agent` | 21m | Forwards node/pod metrics to Cloud Monitoring. |
| `fluentbit-gke` | 105m | Forwards container logs to Cloud Logging. |
| `pdcsi-node` | 15m | Persistent Disk CSI driver's per-node mount component. |
| `collector` (`gmp-system`) | 5m | Google Managed Prometheus's per-node metrics scraper (2 containers, `prometheus`+`config-reloader`); on by default in a new GKE Standard cluster, left enabled here. |

**Cluster-wide singletons** (1-2 replicas total, landing on whichever node has room;
`kube-system` unless noted):

| Pod | Approx. CPU request (`e2-medium`) | Purpose |
|---|---|---|
| `kube-dns` | 270m | Cluster DNS resolution — the single largest system-pod consumer measured on this project's nodes. |
| `kube-dns-autoscaler` | 20m | Scales `kube-dns`'s replica count with cluster size. |
| `konnectivity-agent-autoscaler` | 10m | Scales `konnectivity-agent`'s replica count with cluster size. |
| `event-exporter-gke` | 3m | Forwards Kubernetes events to Cloud Logging. |
| `l7-default-backend` | 10m | Default backend for GKE-provisioned Ingress load balancers. |
| `metrics-server` | 44m | Serves the Kubernetes Metrics API (`kubectl top`, Horizontal Pod Autoscaler). |
| `gmp-operator` (`gmp-system`) | 1m | Manages Google Managed Prometheus's `collector` DaemonSet and its CRDs. |

---

## Appendix: GCP ↔ AWS terminology

For anyone whose cloud background is AWS rather than GCP — the concepts in this guide, mapped
to their closest AWS equivalent. These are the closest analogues, not exact 1:1 matches; see
the Notes column for where the mapping breaks down.

| GCP (used in this guide) | AWS equivalent | Notes |
|---|---|---|
| GKE (Google Kubernetes Engine) | EKS (Elastic Kubernetes Service) | Managed Kubernetes control plane. |
| GKE Standard mode | EKS with self-managed/managed node groups | GKE Autopilot's closer AWS analogue is EKS with Fargate profiles, not used in this guide. |
| Zone (`us-central1-a`) / region (`us-central1`) | Availability Zone / Region | A zone is one failure domain inside a region, like an AWS AZ. The names work differently, though: AWS maps physical AZs to names randomly per account, so `us-east-1a` can be a different physical AZ in another account (AZ IDs such as `use1-az1` are the stable identity); GCP documents no such per-project remapping of zone names. `--zone=` pins the cluster and node pools here; `--location=` sets the GCS bucket's region. |
| Zonal cluster | — (EKS has no zonal/regional tier) | EKS's control plane is always multi-AZ within a region, and billed at ~$0.10/hr (~2,625₫/hr) for a standard-support Kubernetes version, with no free-tier waiver — unlike GKE, which waives this fee for one zonal cluster per billing account (a real cost-design factor, see "Architecture at a glance"). |
| Node pool | Managed node group | A set of worker nodes sharing one config (machine/instance type, disk, taints). |
| Node autoscaling (`--enable-autoscaling`, min 0) | Cluster Autoscaler / Karpenter | Adds or removes nodes based on pending pods. GKE's autoscaler is built in and configured per node pool; on EKS you typically install Cluster Autoscaler or Karpenter yourself. `--no-enable-autoscaling` turns it off per pool (needed before resizing that pool by hand). |
| Node pool resize (`gcloud container clusters resize --node-pool --num-nodes`) | Managed node group desired size (`eksctl scale nodegroup` / `aws eks update-nodegroup-config --scaling-config`) | Sets a node pool's node count by hand, down to 0 to pause between sessions (see "Pausing and resuming the cluster between sessions"). GKE's guidance is not to mix it with the cluster autoscaler on the same pool, so autoscaling is turned off first. EKS managed node groups can also be scaled to 0; there too, a running Cluster Autoscaler would fight a manual size. |
| Compute Engine (GKE nodes are Compute Engine VMs) | Amazon EC2 | GCP's VM service. Every GKE Standard node is a Compute Engine VM, so the node-level rows below (machine type, Spot VM, the default service account, the metadata server — covered in the `--workload-metadata` row — and access scopes) are Compute Engine concepts, as their EKS counterparts are EC2 ones. Its API (`compute.googleapis.com`) is enabled alongside GKE's (see Prerequisites). |
| Compute Engine machine type (`e2-medium`) | AWS EC2 instance type (e.g. `t3.medium`) | Different per-cloud naming/sizing scheme; `t3.medium` matches `e2-medium`'s shape closely — both 2 vCPU/4GB, both burstable/cost-optimized. |
| GKE node allocatable reservation (1060 mCPU on shared-core E2) | EKS `kube-reserved` (node bootstrap defaults) | Both carve a fixed slice off each node for system components. GKE publishes one tiered CPU formula for all machine types (6% of the first core, 1% of the next core, 0.5% of the next 2 cores, 0.25% of anything above 4 cores) and overrides it with a flat 1060 mCPU on shared-core E2 types; EKS's optimized AMI applies that same tiered CPU formula at node bootstrap, with no shared-core exception. Only CPU lines up — each side computes its memory reservation differently. See "GKE system pods added automatically per node". |
| Spot VM | EC2 Spot Instance | Same mechanism: spare capacity at a discount, reclaimable with short notice. |
| Zone resource stock-out (`ZONE_RESOURCE_POOL_EXHAUSTED`) | EC2 insufficient capacity (`InsufficientInstanceCapacity`) | The zone temporarily has no spare capacity for the requested machine type, so creating a VM — here, a node pool resize or an autoscaler scale-up — fails with `ZONE_RESOURCE_POOL_EXHAUSTED` (or `…_WITH_DETAILS`). It isn't a quota error (those read `QUOTA_EXCEEDED`), so waiting and retrying is the fix. EC2 returns `InsufficientInstanceCapacity` in the same situation. |
| Persistent Disk (`pd-standard`/`pd-balanced`/`pd-ssd`) | EBS (`gp2`/`gp3`/`io1`/`io2`/`st1`/`sc1`) | Network-attached block storage tiers; `pd-standard` ≈ `st1`/`sc1` (HDD), `pd-balanced` ≈ `gp3`, `pd-ssd` sits roughly between `gp3` and `io1`/`io2` (no exact match); `pd-extreme` (not used here) is the closest analogue of the provisioned-IOPS `io1`/`io2`. A `Retain`-policy PV's disk outlives the PV and is deleted by hand with `gcloud compute disks delete`, like an EBS volume with `aws ec2 delete-volume`. |
| PD CSI driver (`pdcsi-node`) + built-in StorageClasses (`standard-rwo` default, `standard`) | EBS CSI driver (EKS add-on) + StorageClasses (legacy in-tree `gp2`, not default on new clusters since EKS 1.30; a `gp3` class — the EBS CSI add-on's optional default, or one you create) | Provisions PersistentVolumes from block storage (the Persistent Disk row covers the disk tiers). `standard-rwo` (`pd-balanced`) is CSI-native and binds `WaitForFirstConsumer`; `standard` (`pd-standard`) is the counterpart of EKS's legacy `gp2` class by provisioner, not disk type: both keep an in-tree provisioner name (`kubernetes.io/gce-pd`, `kubernetes.io/aws-ebs`) that CSI migration routes to the CSI driver. Unlike `gp2`, which binds `WaitForFirstConsumer`, `standard` binds `Immediate`. Kafka's volumes and the restore rehearsal use `standard`; all built-in classes reclaim `Delete`. GKE ships the driver preinstalled; on EKS it is an add-on that needs its own IAM setup. |
| Workload Identity Federation | IAM Roles for Service Accounts (IRSA) / EKS Pod Identity | Both let a pod assume a cloud IAM identity with no static key. IRSA wires this through an OIDC provider registered against the cluster; EKS Pod Identity (newer) simplifies the same idea. The workload pool (`<project>.svc.id.goog`, used in `serviceAccount:<pool>[ns/ksa]` members) is the trust anchor, like the IAM OIDC provider in IRSA; Pod Identity has no counterpart. GCP creates the pool automatically, once per project. |
| Workload Identity Federation **for external identities** (GitHub Actions OIDC, Step 9) | IAM OIDC identity provider + `AssumeRoleWithWebIdentity` | Same underlying mechanism as the row above, but the caller is a GitHub Actions run authenticated via its own OIDC token, not a Kubernetes pod — no per-pod/per-node component involved, just a workload identity pool + provider + one IAM binding. AWS's IAM OIDC identity provider plays the same trust-anchor role as the pool. |
| Security Token Service (`sts.googleapis.com`) + IAM Service Account Credentials API (`iamcredentials.googleapis.com`) | AWS STS (`sts:AssumeRoleWithWebIdentity`) | The APIs that actually perform the OIDC-token-for-access-token exchange behind both Workload Identity Federation rows above — a one-time per-project enablement (see Prerequisites). |
| `docker login` with username `oauth2accesstoken` and a Workload-Identity-issued access token as the password (Step 9) | `aws ecr get-login-password` | Both turn a short-lived cloud credential into what the Docker CLI needs to push; GCP reuses Docker's generic username/password login instead of a dedicated helper command. |
| `gke-metadata-server` | EKS Pod Identity Agent | The per-node pod that serves Workload Identity credentials to pods. Closest analogue only: IRSA needs no such per-node pod. |
| `--workload-metadata=GKE_METADATA` (node pool) | — (no equivalent) | Per-node-pool switch replacing the raw Compute Engine metadata server with the Workload Identity one; without it, pods on that pool fall back to the node's own service account. Turning it on for an existing pool takes effect immediately for workloads already running there, which stops them using the node's service account and can disrupt them. EKS needs no node-level toggle — IRSA/Pod Identity work per pod. |
| Google Service Account (GSA) | IAM Role | The cloud-side identity a KSA is bound to. |
| Compute Engine default service account (`<project-number>-compute@developer.gserviceaccount.com`) | EKS node IAM role (attached to the node group's EC2 instances via an instance profile) | The identity GKE nodes run as when a node pool is created without `--service-account`, as both pools here are (`node-pools describe` prints just `default`); Step 9 grants it `roles/artifactregistry.reader` so nodes can pull images, as an EKS node role gets `AmazonEC2ContainerRegistryPullOnly`. GCP creates it automatically with the Compute Engine API and grants it the broad project-wide Editor role unless the `iam.automaticIamGrantsForDefaultServiceAccounts` organization policy is enforced (the default for organizations created on or after May 3, 2024); AWS creates no default, so an EKS managed node group needs a node role you (or `eksctl`) create. Google recommends a dedicated least-privilege node service account (`roles/container.defaultNodeServiceAccount`, plus registry read access) instead; this guide keeps the default. On a `GKE_METADATA` pool, ordinary pods get their Workload Identity instead (see the `--workload-metadata` row), but GKE's logging and monitoring agents and any `hostNetwork: true` pod still use this account. |
| IAM role bindings (`roles/storage.objectAdmin`, `roles/secretmanager.secretAccessor`, `roles/iam.workloadIdentityUser`, …) | IAM policies (identity/resource-based) + trust policies | A GCP role is a permission set granted to a principal on a resource; an AWS *role* is an assumable identity (see the GSA row). Roughly: `roles/storage.objectAdmin` ≈ a managed policy, bucket- and secret-level bindings ≈ resource-based policies, and the `roles/iam.workloadIdentityUser` binding plays the part of a role's trust policy. |
| KSA annotation `iam.gke.io/gcp-service-account` | KSA annotation `eks.amazonaws.com/role-arn` | Same binding mechanism, different annotation key. |
| Google Secret Manager | AWS Secrets Manager | Managed secret storage with IAM-scoped access and versioning. |
| Secrets Store CSI Driver + **GCP provider** | Secrets Store CSI Driver + **AWS provider** | Same upstream Kubernetes SIGs driver (`secrets-store-csi-driver`); only the cloud-provider plugin differs. |
| Google Cloud Storage (GCS) bucket | S3 bucket | Object storage — here, where CNPG's Barman Cloud Plugin archives Postgres WAL/backups (the plugin supports S3 natively too). Restoring from it (CNPG `bootstrap.recovery`, see "Restoring Postgres from a backup") works like an RDS point-in-time restore: both always build a new instance from the backups instead of overwriting the existing one. Copying a bucket to another bucket or a local directory, or back (`gcloud storage rsync -r`), corresponds to `aws s3 sync`, which recurses by default; `gcloud storage ls` and `gcloud storage rm --recursive` correspond to `aws s3 ls` and `aws s3 rm --recursive`. |
| Artifact Registry | Elastic Container Registry (ECR) | Container image registry holding the 6 service images Step 9's CI pipeline builds and pushes. |
| Artifact Registry immutable tags (`--immutable-tags`) | ECR tag immutability (`imageTagMutability: IMMUTABLE`) | Both refuse a push that would move an existing tag to a different image. Artifact Registry goes further: a tagged image can't be deleted or untagged (by hand or by a cleanup policy) while the setting is on, whereas ECR still lets you delete images and expire them with lifecycle policies. |
| Access scopes (node pool / VM: `cloud-platform`, `devstorage.read_only`) | — (no direct equivalent) | Legacy per-VM OAuth scopes that cap what the attached service account can do on top of its IAM roles; pulling from Artifact Registry needs `devstorage.read_only` or `cloud-platform` (the latter defers entirely to IAM). The closest AWS cap is an IAM permissions boundary, set on the role rather than per instance. |
| Google Managed Prometheus (GMP) | Amazon Managed Service for Prometheus (AMP) | Managed Prometheus-compatible metrics collection, enabled by default on a new GKE Standard cluster. Its `gmp-operator` and per-node `collector` pods run in `gmp-system`. |
| Cloud Monitoring / Cloud Logging | Amazon CloudWatch (metrics / Logs) | The managed metric and log stores that `gke-metrics-agent`, `fluentbit-gke` and `event-exporter-gke` write to. On EKS, sending node/pod metrics and container logs to CloudWatch is opt-in (Container Insights / the CloudWatch Observability add-on). |
| GCP project | AWS account | The resource-isolation, IAM and API-enablement boundary; billing rolls up to a separate billing account (next row). |
| Billing account | AWS Organizations management (payer) account | The payment instrument projects attach to, separate from the projects themselves: credits, quota and free-tier allowances are counted per billing account, not per project — GKE's free tier is a monthly credit per billing account that only offsets zonal/Autopilot cluster fees (see the Zonal cluster row). AWS has no equivalent split below the account; consolidated billing instead rolls several accounts up under one payer account. |
| Free Trial ($300 credit for 90 days) | AWS Free plan (accounts created since 2025-07-15; legacy 12-month Free Tier for older ones) | Both are credit-based trials with a hard end. GCP's ends at 90 days or when the $300 (~7,875,000₫) is spent; resources are then stopped and, without an upgrade within 30 days, permanently deleted (see Prerequisites). AWS's Free plan gives $100–200 (~2,625,000–5,250,000₫) of credits for up to 6 months; when it ends, the account is closed and AWS keeps its content for 90 days: upgrading to a paid plan within them restores access, otherwise the account and its resources are permanently deleted. |
| Cloud Billing budget (`gcloud billing budgets create`) | AWS Budgets | Spend thresholds that send alerts (email by default); this guide's budget never stops spending (see "Budget alert" under Prerequisites). GCP's spend-cap budgets pause new usage of a few request-based services (such as Cloud Run and the Gemini API) but never running VMs or disks, so they can't stop a cluster; AWS Budgets can run Budget Actions automatically (e.g. apply an IAM policy, stop EC2 instances). Otherwise GCP needs a programmatic budget notification (to a Pub/Sub topic) plus your own code. |
| Billing account IAM roles (`roles/billing.admin`, `roles/billing.costsManager`) | IAM policies with AWS Budgets actions (`budgets:ModifyBudget`, `budgets:ViewBudget`) | Needed to create a budget (see "Budget alert"). GCP grants these on the billing account, a resource separate from the project (see the Billing account row). AWS has no separate billing resource: they are ordinary IAM permissions in the account that owns the budget; a budget in the management (payer) account can track the whole organization's costs. |
| Pub/Sub | Amazon SNS (fan-out) / Amazon SQS (queue) | Not used by this guide; it is the channel programmatic budget notifications arrive on. An AWS budget can likewise notify an SNS topic. |
| Cloud Billing Reports (Console → Billing → Reports) | AWS Cost Explorer | Interactive spend breakdowns by project/account, service and SKU, with credits shown separately; where this guide checks which credit type GKE's fee offset falls under (see "Budget alert"). |
| Billing credit types (`PROMOTION`, `FREE_TIER`, `SUSTAINED_USAGE_DISCOUNT`) | AWS Budgets "Charge type" filter (Credit, Refund, …) | Decide which credits a budget subtracts. GCP picks individual credit types, which is how this guide's budget subtracts the free-tier credit but not the trial's `PROMOTION` credit; an AWS budget includes or excludes credits as one Credit charge type. Sustained use discounts have no AWS counterpart: Savings Plans and Reserved Instances need a commitment. |
| Organization policy (`iam.automaticIamGrantsForDefaultServiceAccounts`) | AWS Organizations policies (SCPs, declarative policies) | Constraints set on an organization, folder or project that restrict what configuration the projects below it may use. This one stops GCP from automatically granting the default service accounts the Editor role, and is enforced by default for organizations created on or after May 3, 2024 (see the Compute Engine default service account row); Google now recommends the stricter `iam.managed.preventPrivilegedBasicRolesForDefaultServiceAccounts`, which also blocks granting them Editor or Owner later. A project with no organization — like this guide's — has no organization policies, so its default compute service account keeps the automatic Editor grant. On AWS, SCPs cap the permissions accounts may use and declarative policies enforce service configuration; neither has an equivalent of this constraint, since AWS creates no default role to grant. |
| Google Cloud console (Console → Billing) | AWS Management Console (Billing and Cost Management console) | The web UI. This guide uses it only for what `gcloud` doesn't show: the Free Trial's remaining credit and days (Billing → Overview) and the one-time credit-type check in Billing Reports (see the Cloud Billing Reports row). Its Billing pages show one billing account at a time, filterable by project; AWS's Billing and Cost Management console shows the signed-in account's costs, or, from an organization's management account, all its member accounts' costs. |
| `gcloud` CLI | `aws` CLI + `eksctl` | GCP bundles cluster operations into `gcloud container clusters`; EKS-specific operations on AWS typically need `eksctl` (or Terraform) alongside the base `aws` CLI. Installed as the Google Cloud CLI, one of the tools Google groups under the name Google Cloud SDK (with the client libraries) — hence the `Cloud SDK` install directory and the `CLOUDSDK_*` environment variables such as `CLOUDSDK_PYTHON` (see Prerequisites); `gke-gcloud-auth-plugin` is one of its optional components. It bundles its own Python on Windows and x86_64 Linux; on macOS its installer installs one if needed. On AWS, an "SDK" is a per-language client library; the `aws` CLI is a separate install. |
| `gcloud services enable` (API enablement) | — (no per-service enablement) | GCP requires enabling each service's API per project; AWS services are generally usable without a separate enablement step (some features, such as opt-in Regions, still need opting in). |
| `gke-gcloud-auth-plugin` | `aws eks get-token` (via the `aws` CLI) | kubectl exec-credential plugin that turns cloud credentials into cluster auth tokens. `gcloud container clusters get-credentials` corresponds to `aws eks update-kubeconfig`. |
| `netd` + GKE's default (non-Dataplane V2) datapath | `aws-node` (Amazon VPC CNI plugin) | Each cloud's own per-node networking DaemonSet. `netd` sets up the node's Pod networking — it generates the CNI spec for the PTP plugin from the node's PodCIDR and manages packet redirection on the node; GKE runs it when Workload Identity Federation for GKE (enabled here), intranode visibility or dual-stack is on. `aws-node` does more — it also hands pods real VPC IPs from ENIs. Note that nothing on this cluster enforces NetworkPolicy: on a non-Dataplane V2 cluster that needs `--enable-network-policy`, which installs Calico (`calico-node`) and is off by default. GKE's Dataplane V2 (eBPF/Cilium, not used here) is the closer analogue of running Cilium on EKS. |
| `konnectivity-agent`, `konnectivity-agent-autoscaler` | — (no EKS equivalent) | Tunnels control-plane-to-node traffic (`kubectl exec`/`logs`, webhook calls), needed because GKE's control plane runs in a Google-managed project. EKS places control-plane ENIs directly in your VPC instead, so no such pods exist there. |
| `node-local-dns` (NodeLocal DNSCache), `kube-dns-autoscaler` | — (self-managed on EKS) | Upstream Kubernetes add-ons that GKE installs and manages for you; on EKS you deploy and size them yourself. |
| `kube-dns`, `kube-proxy`, `metrics-server` | CoreDNS, `kube-proxy`, metrics-server (EKS add-ons) | The remaining system pods GKE preinstalls and versions for you. GKE's default cluster DNS is `kube-dns`, not CoreDNS. EKS installs CoreDNS and `kube-proxy` by default too, but as add-ons you version yourself; `metrics-server` is not installed by default on an EKS cluster (recent `eksctl` versions add it as an EKS add-on; otherwise it is an EKS community add-on you add yourself), while GKE ships and auto-resizes it. |
| GKE Ingress load balancer (`l7-default-backend`) | AWS Load Balancer Controller (ALB) | Provisions an HTTP(S) load balancer from an Ingress. GKE runs the controller for you; on EKS you install it yourself. `l7-default-backend` (the 404 backend) has no pod-level equivalent on ALB. |
| `BackendConfig` (GKE CRD) | AWS Load Balancer Controller annotations (e.g. `alb.ingress.kubernetes.io/healthcheck-path`) | Per-Service load-balancer settings (health check, timeouts, Cloud CDN, Cloud Armor, IAP, …), attached to a Service with the `cloud.google.com/backend-config` annotation. With the AWS controller, the ALB's equivalents (health check, WAF, OIDC/Cognito authentication, …) are annotations on the Ingress or on the Service itself, a Service's taking priority; there is no per-Service settings resource, and CDN is a separate service (CloudFront). |

**Note**: this project's GCP account is on a Free Trial, which blocks all quota increase
requests (AWS's equivalent, account-level Service Quotas, allows requesting increases via a
support case). The quota-exhaustion workaround used in this guide (e.g. `pd-standard` over
`pd-balanced` for `stateless-pool`, see Step 1) is specific to that Free Trial limitation, not a
general GCP-vs-AWS difference.

---

## Not covered here (separate, future work)

- Automating "Pausing and resuming the cluster between sessions" (a CD/teardown workflow or a
  scheduled job).
- Verifying the backups automatically: "Rehearsing a restore" is an occasional manual check, not
  a scheduled job that restores and compares on its own.
- Recovering Postgres straight from the `Retain`-policy disk a deleted `Cluster` leaves behind:
  CNPG reuses a leftover PVC that still carries its labels and annotations, but rebuilding one by
  hand around a `Released` disk isn't documented here. Recover from the bucket instead.
- Backing up Kafka's data — it only carries saga messages in transit (see Prerequisites for what
  losing them leaves to correct by hand).
- An Artifact Registry cleanup policy — content-hash tags never collide or get overwritten, so
  the registry only grows; nothing here deletes an old image once no deployed release still
  references it. With immutable tags on (Step 9), a cleanup policy can't delete tagged images
  either, so such a policy would also need immutability turned off, or old tags handled some
  other way.
- The frontend's deployment: a container image and Helm chart for the Angular app, a frontend
  CI workflow, and the public Ingress (`/` to the frontend, `/api/*` to the gateway, with a
  `BackendConfig` health check per backend Service). Until then every Service is
  cluster-internal and nothing is reachable from outside the cluster.
- A Zipkin collector in the cluster — span export is off on GKE
  (`global.tracing.export.zipkin.enabled`) until one is deployed along with its endpoint value.
- A dedicated least-privilege node service account (`roles/container.defaultNodeServiceAccount`
  plus `roles/artifactregistry.reader`) instead of the Compute Engine default service account the
  node pools run as.

</details>

<details>
<summary><strong>🇻🇳 Tiếng Việt</strong></summary>

Đây là hướng dẫn từng bước để dựng hạ tầng GKE cho việc triển khai Kubernetes của dự án: bản thân
cluster, operator CNPG (Postgres) và Strimzi (Kafka), secret lấy từ Secret Manager qua Secrets
Store CSI Driver, và các Helm chart triển khai 6 service Spring Boot.

**Phạm vi**: từ hạ tầng cluster GKE tới khi deploy `charts/cafe` bằng `scripts/deploy.sh` thành công
(Bước 1-8), cộng thêm CI pipeline build và push image container thật cho các pod đó (Bước 9). Tạm
dừng và bật lại cluster giữa các buổi làm việc là 1 quy trình làm tay (có mục riêng, sau Bước 9); tự
động hoá nó là việc riêng, chưa triển khai — xem mục "Chưa bao gồm trong tài liệu này" ở cuối. Khôi
phục Postgres từ backup, kể cả 1 lần tập dượt, có mục riêng ngay sau mục tạm dừng và bật lại.

Link tới file trong repo trỏ thẳng tới `master` trên GitHub.

Mọi lệnh `gcloud` giả định đã set project mặc định (xem mục "Yêu cầu môi trường"). Các lệnh bên dưới
được viết dạng bash thuần. Trên Git Bash cho Windows, `gcloud` cần đặt `CLOUDSDK_PYTHON` trước (xem
mục "Yêu cầu môi trường").

## Kiến trúc tổng quan

- GKE Standard, cluster **zonal** (không phải regional — ưu đãi miễn phí control-plane của GKE
  chỉ áp dụng cho 1 cluster zonal mỗi billing account), 1 namespace `cafe` duy nhất.
- 2 node pool: `stateful-pool` (on-demand, taint `workload=stateful:NoSchedule`, chứa Postgres +
  Kafka) và `stateless-pool` (Spot, autoscaling min 0, chứa mọi thứ còn lại).
- **CloudNativePG** (operator Postgres), **Barman Cloud Plugin** (backup lên GCS) và **Strimzi**
  (operator Kafka) đều chạy pod operator trên `stateless-pool` — cả 3 lệnh cài Helm đều không đặt
  toleration cho taint `workload=stateful`. Chỉ các workload do chúng quản lý mới bị ghim vào
  `stateful-pool` qua node affinity/toleration: pod instance Postgres (cùng sidecar
  `plugin-barman-cloud` được chèn vào đó) và Kafka broker.
- **Secrets Store CSI Driver** (provider GCP) đồng bộ secret từ Google Secret Manager (GSM)
  thành `Secret` object thật của Kubernetes, được Deployment của mỗi service sử dụng.
- **Workload Identity Federation** — mỗi pod xác thực với GCP bằng Google Service Account (GSA)
  riêng của nó, không có static service-account key nào cả.
- **Helm**: `charts/cafe-service` (1 chart tái sử dụng) + `charts/cafe` (umbrella chart với 6
  dependency alias: gateway, auth-service, menu-service, order-service, inventory-service,
  report-service).
- **GitHub Actions** (`.github/workflows/backend-ci.yml`, Bước 9) build và push image của từng
  service lên **Artifact Registry**, xác thực với GCP qua một cấu hình **Workload Identity
  Federation** riêng dành cho GitHub — cũng không dùng static key nào.
- Manifest Postgres/Kafka nằm ở `k8s/data-layer/`, áp dụng bằng `kubectl apply` thuần — **không**
  thuộc bất kỳ Helm release nào. Đây là chủ đích: `helm uninstall`/rollback tầng ứng dụng không
  bao giờ được phép cascade-xoá database.

## Yêu cầu môi trường

- Đã cài CLI `gcloud`, `kubectl`, `helm`, `cmctl` (CLI của cert-manager, cài theo tài liệu của
  cert-manager) và `openssl`; `gcloud` đã đăng nhập. `helm` phải là Helm 4.1.1 trở lên, mức tối
  thiểu mà `scripts/deploy.sh` bắt buộc: script chờ bằng cơ chế kiểm tra trạng thái
  `--wait=watcher`, thứ mà Helm 3 không có, còn các bản Helm 4 cũ hơn sẽ chờ tới hết timeout với 1
  Deployment đã fail thay vì báo lỗi ngay khi các resource còn lại đã ổn định.
- `gitleaks` (chỉ cần khi bạn di chuyển cặp khoá JWT dev, hay bất kỳ credential nào khác nằm trong
  `.gitleaksignore`, sang file/dòng khác và phải tạo lại fingerprint — xem `.gitleaksignore` bên
  dưới; bản thân CI chạy nó qua `gitleaks/gitleaks-action`, không cần cài local cho pipeline).
- `docker` (chỉ cần để chạy lint shellcheck đã pin ở local, Bước 9; CI tự chạy đúng image đó).
- `gke-gcloud-auth-plugin` nằm trong `PATH` (kiểm tra bằng `gke-gcloud-auth-plugin --version`).
  `kubectl`, `helm` và `cmctl` đều cần nó để nói chuyện với cluster GKE. Cài bằng
  `gcloud components install gke-gcloud-auth-plugin` (SDK độc lập / bộ cài Windows) hoặc, nếu
  dùng package manager, gói `google-cloud-cli-gke-gcloud-auth-plugin`. `clusters create` (Bước 1)
  tự ghi entry kubeconfig; khi làm tiếp từ shell hoặc máy mới, chạy
  `gcloud container clusters get-credentials cafe-cluster --zone=us-central1-a`.
- Có shell bash 4.3+ (Git Bash trên Windows dùng được; bash 3.2 có sẵn trên macOS thì không) —
  các lệnh dùng tính năng của bash như `${var//-/_}` và brace expansion, và `scripts/deploy.sh`
  dùng nameref (`local -n`). Các script được kiểm thử trên bash 5.x.
- Chỉ với Git Bash trên Windows: `CLOUDSDK_PYTHON` trỏ tới Python đi kèm Cloud SDK. Git Bash chạy
  launcher `gcloud` kiểu POSIX của SDK, launcher này chỉ tìm Python đi kèm ở 1 đường dẫn dành cho
  Unix, rồi chuyển sang `python3`/`python` trên `PATH`; khi 2 lệnh đó chỉ là alias của Microsoft
  Store, `gcloud` không khởi động được (exit code 49, "Python was not found"). `scripts/deploy.sh`
  chạy chính launcher `gcloud` kiểu POSIX đó nên cũng cần biến này, và sẽ dừng kèm gợi ý khi
  `gcloud` không khởi động được. Thêm biến vào `~/.bashrc`, rồi mở 1 cửa sổ Git Bash mới (hoặc chạy
  `source ~/.bashrc`):

  ```bash
  echo 'export CLOUDSDK_PYTHON="$LOCALAPPDATA/Google/Cloud SDK/google-cloud-sdk/platform/bundledpython/python.exe"' >> ~/.bashrc
  ```

  Đó là đường dẫn cài đặt mặc định theo user của Cloud SDK; nếu SDK của bạn nằm ở chỗ khác,
  `gcloud.cmd info --format='value(basic.python_location)'` sẽ in ra đường dẫn đúng.
- `git` và `sha256sum` nằm trong `PATH` — `scripts/deploy.sh` kiểm tra trạng thái repo bằng git, còn
  `scripts/image-tag.sh` tính hash bằng `sha256sum` (Git Bash có sẵn cả 2; macOS trước bản 15
  (Sequoia) không có `sha256sum`).
- Project GCP đã bật billing. Project của tài liệu này chạy trên billing account **Free Trial**,
  kết thúc vào ngày **2026-12-08** hoặc khi đã tiêu hết $300 (~7.875.000₫) credit, tuỳ mốc nào tới
  trước (Console → Billing → Overview, mục "Free credit", cho biết số ngày và credit còn lại). Khi
  đó mọi tài nguyên bị dừng; nếu không nâng cấp billing account trong 30 ngày gia hạn tiếp theo,
  mọi thứ trong project bị xoá vĩnh viễn: cluster cùng các persistent disk (dữ liệu của Postgres và
  Kafka), bucket backup trên GCS, Secret Manager, Artifact Registry và toàn bộ thiết lập IAM và
  Workload Identity. Nâng cấp trong thời gian gia hạn thì *có thể* lấy lại được, nhưng Google không
  cam kết. Trước ngày hết hạn, hoặc nâng cấp billing account lên tài khoản trả phí (credit chưa
  dùng vẫn được giữ tới hạn của nó), hoặc chép toàn bộ bucket backup ra khỏi project — sang 1
  bucket thuộc 1 project gắn với billing account trả phí khác, hoặc bằng
  `gcloud storage rsync -r gs://cafe-microservices-cafe-pg-backups <dir>`. Chép mọi prefix, vì sau
  1 lần khôi phục backup nằm dưới 1 prefix mới, và chép cả base backup lẫn WAL, vì thiếu 1 trong 2
  thì phần còn lại vô dụng; cách khôi phục từ 1 trong 2 bản chép này nằm ở cuối mục "Khôi phục sau
  khi mất cluster Postgres". Bucket chỉ chứa dữ liệu của Postgres; dữ liệu của Kafka xem gạch đầu
  dòng tiếp theo.
- Dữ liệu của Kafka không có backup nào: nó chỉ chứa các message saga đang chuyển giữa các
  service, và mất nó là mất các message chưa được consume. Job saga reconciliation của
  order-service gửi lại lệnh của order đang chờ reply cho lệnh reserve hoặc commit, nhưng chỉ 3
  lần, cách nhau khoảng 1 phút, và các lần gửi lại trong lúc Kafka không hoạt động cũng bị tính.
  Sau đó nó đưa order về OPEN hoặc CONFIRMED; stock mà inventory-service đã giữ hoặc đã trừ cho
  order đó vẫn như vậy, cũng như stock mà 1 lệnh release-stock bị mất lẽ ra đã nhả, cho tới khi
  được sửa tay.
- Chạy mọi lệnh từ thư mục gốc của repo — các đường dẫn như `k8s/data-layer/` và `charts/cafe`
  là đường dẫn tương đối so với thư mục đó.
- Chốt trước project ID, tên/zone cluster, tên bucket backup Postgres. Các giá trị này còn được đưa
  vào IAM binding và các file của repo: `gcp_project`, `cluster_zone` và `cluster_name` của
  `scripts/deploy.sh` (kube-context và gợi ý `get-credentials` mà script dùng); đường dẫn Artifact
  Registry, được `deploy.test.sh` giữ đồng bộ giữa `image_ref` của `scripts/deploy.sh`,
  `global.imageRegistry` của `charts/cafe/values.yaml` và `IMAGE` của
  `.github/workflows/backend-ci.yml`; các giá trị sau của workflow đó: `service_account`,
  `workload_identity_provider` (chứa project number; phần thiết lập GCP ở Bước 9 in ra tên đầy đủ)
  và host `registry:` ở bước Docker login (phần host của đường dẫn Artifact Registry, không được
  `deploy.test.sh` kiểm tra); `global.gcpProjectId` của `charts/cafe/values.yaml` (giá trị này
  render ra đường dẫn `resourceName:` của từng `SecretProviderClass` và annotation
  `iam.gke.io/gcp-service-account` của từng ServiceAccount), annotation `serviceAccountTemplate`
  trong `k8s/data-layer/postgres-cluster.yaml`, và `destinationPath` trong
  `k8s/data-layer/postgres-backup.yaml`. Tài liệu này dùng đúng giá trị thật của repo này
  (`cafe-microservices` / `cafe-cluster` / `us-central1-a` /
  `gs://cafe-microservices-cafe-pg-backups`) làm ví dụ; thay bằng giá trị của bạn.

Đặt project mặc định và bật các API mà tài liệu này dùng (trên project mới, lệnh `gcloud` đầu
tiên sẽ hỏi hoặc báo lỗi nếu chưa bật):

```bash
gcloud config set project cafe-microservices
gcloud services enable compute.googleapis.com container.googleapis.com secretmanager.googleapis.com storage.googleapis.com iam.googleapis.com iamcredentials.googleapis.com artifactregistry.googleapis.com sts.googleapis.com cloudresourcemanager.googleapis.com
```

Bước 9 còn cần một repository GitHub đã bật Actions và quyền admin trên repo đó (để cấu hình
branch protection) — không cần thêm CLI nào ngoài `gcloud` (và `docker`, chỉ cho bước lint local
tùy chọn), dù GitHub CLI (`gh`) là cách tiện lợi để chạy lần đầu thủ công.

### Cảnh báo ngân sách (budget alert)

Một budget của Cloud Billing gửi email cho các admin và user của billing account khi chi tiêu vượt
1 ngưỡng. Nó chỉ cảnh báo — không bao giờ chặn hay dừng việc chi tiêu — nhưng các ngưỡng thấp của
nó là cách rẻ để không quên 1 cluster vẫn chạy giữa các buổi làm việc. Tạo budget cần role Billing
Account Administrator hoặc Billing Account Costs Manager. Các API của nó được gọi qua project mà
`gcloud config` đang trỏ tới, nên bật ở chính project đó. Chạy cả 2 block trong cùng 1 shell:

```bash
gcloud services enable cloudbilling.googleapis.com billingbudgets.googleapis.com
billing_account="$(gcloud billing projects describe cafe-microservices --format='value(billingAccountName)')"
billing_account="${billing_account#billingAccounts/}"
gcloud billing accounts describe "$billing_account" --format='value(currencyCode)'
```

Lệnh cuối in ra đơn vị tiền của account; `--budget-amount` phải dùng đúng đơn vị đó. Với cách tính
credit mặc định, budget đếm chi tiêu *sau* khi trừ credit, mà trên Free Trial con số này gần như
bằng 0 cho tới khi hết credit, nên budget sẽ không bao giờ báo. Thay vào đó, budget chỉ trừ các
loại credit liệt kê trong `--filter-credit-types`: credit free tier (nhiều khả năng gồm cả credit
bù phí control plane của GKE; kiểm tra 1 lần ở cuối mục này) và giảm giá sustained use (node E2 và
Spot không được giảm giá này, nên ở đây nó không thay đổi gì; nó được liệt kê để budget vẫn chỉ đo
credit Free Trial nếu dùng loại máy khác). Credit `PROMOTION`, tức $300 (~7.875.000₫) của Free
Trial, không bị trừ mà vẫn tính là chi tiêu, nhờ vậy budget đo được tốc độ tiêu credit Free Trial.

Chi tiêu được tính lại từ ngày 1 mỗi tháng (giờ Thái Bình Dương) và chỉ hiện trong billing sau vài
giờ tới khoảng 1 ngày, nên mỗi cảnh báo nghĩa là "chi tiêu từ đầu tháng tới giờ đã vượt mức này".
Các ngưỡng là tỷ lệ của budget $100 (~2.625.000₫) và cho 2 tín hiệu:

- $10, $20 và $40 (~262.500₫, ~525.000₫ và ~1.050.000₫): 1 cluster bị quên chưa tạm dừng. Khi cả 2
  pool đều chạy, cluster tốn khoảng $2.2–2.8 (~58.000–74.000₫) mỗi ngày, nên chỉ riêng việc chạy
  liên tục cũng chạm các mức này sau khoảng 4, 7–9 và 14–18 ngày (ước tính từ giá niêm yết của node
  và disk, không lấy từ hoá đơn). Khi tạm dừng, disk, bucket, registry và secret chỉ tốn khoảng $1
  (~26.000₫) mỗi tháng, nên riêng việc tạm dừng không bao giờ chạm các mức này, nhưng các buổi làm
  việc trong tháng thì có: nếu 1 cảnh báo tới sớm hơn mức mà số giờ bạn đã chạy cluster giải thích
  được, hãy kiểm tra cả 2 pool đều đang ở 0 node (lệnh cuối của phần "Tạm dừng").
- $50, $90 và $100 (~1.312.500₫, ~2.362.500₫ và ~2.625.000₫), cùng mức $100 theo dự báo: tốc độ
  tiêu credit Free Trial. $100 mỗi tháng xấp xỉ tốc độ tiêu hết $300 (~7.875.000₫) trong 90 ngày
  của Free Trial, nên cảnh báo theo dự báo nghĩa là "đang tiêu nhanh tới mức sẽ hết credit sớm":

```bash
budgets="$(gcloud billing budgets list --billing-account="${billing_account:?run the block above in this shell first}" --filter='displayName="cafe-microservices monthly"' --format='value(name)')" &&
  { [[ -z $budgets ]] || { echo "a budget named cafe-microservices monthly already exists: $budgets (see the notes after this block)" >&2; false; }; } &&
  gcloud billing budgets create --billing-account="$billing_account" \
    --display-name="cafe-microservices monthly" \
    --budget-amount=100USD --calendar-period=month \
    --filter-projects=projects/cafe-microservices \
    --credit-types-treatment=include-specified-credits \
    --filter-credit-types=FREE_TIER,SUSTAINED_USAGE_DISCOUNT \
    --threshold-rule=percent=0.1 --threshold-rule=percent=0.2 --threshold-rule=percent=0.4 \
    --threshold-rule=percent=0.5 --threshold-rule=percent=0.9 --threshold-rule=percent=1.0 \
    --threshold-rule=percent=1.0,basis=forecasted-spend
```

Nếu đơn vị tiền của account không phải USD, thay `100USD` bằng số tiền tương đương theo đơn vị đó.
Nếu block dừng vì đã có 1 budget cùng tên, giữ budget đó — budget thứ 2 sẽ gửi mọi cảnh báo 2 lần —
và sửa nó bằng `gcloud billing budgets update`. Kiểm tra 1 lần trong Console → Billing → Reports
(lọc theo project này, có hiện credit) rằng credit của Kubernetes Engine được xếp vào loại credit
miễn phí (free tier). Nếu nó nằm ở loại credit khác, thêm loại đó vào budget; nếu không, budget sẽ
tính khoảng $73 (~1.900.000₫) phí control plane mỗi tháng của cluster là chi tiêu. Danh sách mới
thay thế toàn bộ danh sách cũ của budget, nên phải liệt kê lại cả 2 loại ở trên, và `<name>` là tên
`billingAccounts/…/budgets/…` mà
`gcloud billing budgets list --billing-account="$billing_account" --format='value(name)'` in ra:
`gcloud billing budgets update <name> --credit-types-treatment=include-specified-credits --filter-credit-types=FREE_TIER,SUSTAINED_USAGE_DISCOUNT,<type>`.

---

## Bước 1 — Cluster GKE và node pool

```bash
# The default pool is temporary (deleted below), so its disk settings don't affect the final cluster
gcloud container clusters create cafe-cluster \
  --zone=us-central1-a \
  --machine-type=e2-medium \
  --disk-type=pd-standard --disk-size=20 \
  --num-nodes=1 \
  --workload-pool=cafe-microservices.svc.id.goog \
  --workload-metadata=GKE_METADATA

kubectl create namespace cafe
```

Bật Workload Identity **ngay lúc tạo cluster** nếu có thể — bật sau trên cluster đã tồn tại
(`gcloud container clusters update --workload-pool=...`) vẫn được, nhưng mỗi node pool sau đó
còn cần thêm `--workload-metadata=GKE_METADATA`, việc này có hiệu lực ngay với các workload
đang chạy trên pool đó và có thể gây gián đoạn cho chúng.

Tạo 2 node pool, rồi xoá node pool mặc định do `clusters create` tạo ra, để nó không tồn tại
thừa như node pool thứ 3 không dùng tới:

```bash
# Stateful: Postgres + Kafka. No autoscaling — fixed size; to pause it at 0 nodes, hibernate
# Postgres first (see "Pausing and resuming the cluster between sessions").
gcloud container node-pools create stateful-pool \
  --cluster=cafe-cluster --zone=us-central1-a \
  --machine-type=e2-medium --disk-type=pd-balanced --disk-size=100 \
  --num-nodes=1 \
  --node-taints=workload=stateful:NoSchedule \
  --workload-metadata=GKE_METADATA

# Stateless: everything else, on Spot. Autoscaling removes idle nodes but not the last 2-3
# (system-pods appendix); pausing to 0 nodes is manual (see "Pausing and resuming the cluster
# between sessions").
gcloud container node-pools create stateless-pool \
  --cluster=cafe-cluster --zone=us-central1-a \
  --machine-type=e2-medium --spot --disk-type=pd-standard --disk-size=50 \
  --num-nodes=1 --enable-autoscaling --min-nodes=0 --max-nodes=6 \
  --workload-metadata=GKE_METADATA

# The default pool is no longer needed once the two pools above exist.
gcloud container node-pools delete default-pool --cluster=cafe-cluster --zone=us-central1-a --quiet
```

Dùng `pd-standard` thay vì `pd-balanced`/SSD cho `stateless-pool`: rẻ hơn, và trên project Free
Trial thì quota SSD theo region rất dễ cạn mà không xin tăng được — `pd-standard` lấy từ 1 quota
bucket khác, rộng rãi hơn nhiều.

---

## Bước 2 — GCS bucket, service account, Workload Identity binding

```bash
# Postgres backup bucket
gcloud storage buckets create gs://cafe-microservices-cafe-pg-backups --location=us-central1

# Backup-writing GSA for the Postgres pod itself
gcloud iam service-accounts create cafe-postgres-backup
gcloud storage buckets add-iam-policy-binding gs://cafe-microservices-cafe-pg-backups \
  --member="serviceAccount:cafe-postgres-backup@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/storage.objectAdmin

# Barman also reads bucket metadata (storage.buckets.get), which objectAdmin doesn't include
gcloud storage buckets add-iam-policy-binding gs://cafe-microservices-cafe-pg-backups \
  --member="serviceAccount:cafe-postgres-backup@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/storage.legacyBucketReader

# Read-only GSA for restoring from those backups (see "Restoring Postgres from a backup"): a
# restore only reads the bucket, so the cluster doing it can't overwrite or delete the backups
gcloud iam service-accounts create cafe-postgres-restore
for role in roles/storage.objectViewer roles/storage.legacyBucketReader; do
  gcloud storage buckets add-iam-policy-binding gs://cafe-microservices-cafe-pg-backups \
    --member="serviceAccount:cafe-postgres-restore@cafe-microservices.iam.gserviceaccount.com" \
    --role="$role"
done

# One GSM-reader GSA per service (5 app services + gateway):
for svc in auth-service menu-service order-service inventory-service report-service gateway; do
  gcloud iam service-accounts create "${svc}-gsm-reader"
done
```

Gắn mỗi GSA với ServiceAccount Kubernetes (KSA) tương ứng qua Workload Identity. KSA chưa cần
tồn tại — template [serviceaccount.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe-service/templates/serviceaccount.yaml) của `charts/cafe-service` sẽ tạo nó sau, đúng tên và có
annotation `iam.gke.io/gcp-service-account`:

```bash
for svc in auth-service menu-service order-service inventory-service report-service gateway; do
  gcloud iam service-accounts add-iam-policy-binding \
    "${svc}-gsm-reader@cafe-microservices.iam.gserviceaccount.com" \
    --role=roles/iam.workloadIdentityUser \
    --member="serviceAccount:cafe-microservices.svc.id.goog[cafe/${svc}]"
done

# The Postgres backup GSA binds to `cafe-postgres`, the KSA CNPG creates itself (named after the Cluster)
gcloud iam service-accounts add-iam-policy-binding \
  "cafe-postgres-backup@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/iam.workloadIdentityUser \
  --member="serviceAccount:cafe-microservices.svc.id.goog[cafe/cafe-postgres]"

# The restore GSA binds to `cafe-postgres-restore`, the KSA of the scratch Cluster a rehearsal
# restores into; the binding stays, so a rehearsal never waits on IAM propagation
gcloud iam service-accounts add-iam-policy-binding \
  "cafe-postgres-restore@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/iam.workloadIdentityUser \
  --member="serviceAccount:cafe-microservices.svc.id.goog[cafe/cafe-postgres-restore]"
```

GSA backup của pod Postgres được gắn annotation theo cách khác — qua `serviceAccountTemplate` của
CNPG `Cluster` (xem [k8s/data-layer/postgres-cluster.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/data-layer/postgres-cluster.yaml)),
không phải ServiceAccount do Helm quản lý, vì đây là tầng dữ liệu, không phải service ứng dụng.
Template đó chỉ đặt annotation, nên binding của `cafe-postgres-backup` → `cafe/cafe-postgres`
ở trên vẫn bắt buộc — thiếu nó thì pod không xác thực được với bucket backup.

Thay đổi IAM có thể mất vài phút để lan truyền — pod báo lỗi CSI mount
`PermissionDenied: iam.serviceAccounts.getAccessToken denied` ngay sau khi vừa gắn binding mới
thường chỉ là độ trễ lan truyền, không phải lỗi cấu hình. Nó tự hết nhờ cơ chế thử lại mount
tự động của kubelet.

---

## Bước 3 — Hạ tầng cluster (các operator)

Cài theo đúng thứ tự này — cert-manager phải Ready trước Barman Cloud Plugin, vì manifest cài
đặt của plugin này tạo resource `cert-manager.io/Certificate` mà webhook của cert-manager phải
admit được ngay lập tức.

```bash
# cert-manager, pinned to v1.21.2 - newer releases may exist,
# see github.com/cert-manager/cert-manager/releases
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.21.2/cert-manager.yaml
cmctl check api --wait=2m   # confirm Ready before continuing

# CloudNativePG operator
helm repo add cnpg https://cloudnative-pg.github.io/charts
helm repo update cnpg
helm upgrade --install cnpg cnpg/cloudnative-pg --version 0.29.0 -n cnpg-system --create-namespace

# Barman Cloud Plugin (backups) - same repo as CNPG
helm upgrade --install plugin-barman-cloud cnpg/plugin-barman-cloud --version 0.8.0 -n cnpg-system

# Secrets Store CSI Driver + GCP provider
helm repo add secrets-store-csi-driver https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
helm repo update secrets-store-csi-driver
helm upgrade --install csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver \
  --version 1.6.1 -n kube-system --set syncSecret.enabled=true
# GCP provider, pinned to v1.17.0 rather than the main branch - newer releases may exist,
# see github.com/GoogleCloudPlatform/secrets-store-csi-driver-provider-gcp/releases
kubectl apply -f https://raw.githubusercontent.com/GoogleCloudPlatform/secrets-store-csi-driver-provider-gcp/v1.17.0/deploy/provider-gcp-plugin.yaml

# Strimzi (Kafka operator)
helm repo add strimzi https://strimzi.io/charts/
helm repo update strimzi
helm upgrade --install strimzi strimzi/strimzi-kafka-operator --version 1.2.0 \
  -n strimzi-system --create-namespace -f k8s/operators/strimzi-values.yaml
```

Có 2 flag không hiển nhiên ở trên, cả 2 đều quan trọng (không có thì lệnh cài vẫn "thành công"
nhưng mọi thứ phía sau không hoạt động):

- **`--set syncSecret.enabled=true`** trên CSI driver: tính năng này **mặc định tắt** trong
  chart gốc. Không có nó, `SecretProviderClass.spec.secretObjects` (cơ chế biến 1 secret được
  CSI mount thành 1 `Secret` object thật mà `secretKeyRef` của Deployment có thể tham chiếu) sẽ
  âm thầm không làm gì cả — không lỗi, file vẫn được mount ở `/mnt/secrets-store`, nhưng
  `Secret` không bao giờ xuất hiện.
- **`-f`** [k8s/operators/strimzi-values.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/operators/strimzi-values.yaml), đặt `watchNamespaces: [cafe]`: chart mặc
  định `watchAnyNamespace: false` / `watchNamespaces: []`, nghĩa là operator chỉ reconcile
  resource trong namespace cài đặt của chính nó (`strimzi-system`) và âm thầm bỏ qua mọi
  `Kafka`/`KafkaNodePool` áp dụng vào namespace `cafe` — không event, không lỗi, chỉ đơn giản
  là không có gì xảy ra.

Kiểm tra cert-manager, operator CNPG, Barman Cloud Plugin, Secrets Store CSI Driver + provider
GCP, và Strimzi đều đã sẵn sàng trước khi tiếp tục — mỗi lệnh trả về ngay khi đối tượng của nó
sẵn sàng, hoặc báo lỗi sau khi hết thời gian chờ:

```bash
for ns in cert-manager cnpg-system strimzi-system; do
  kubectl wait --for=condition=Available deployment --all -n "$ns" --timeout=300s
done
kubectl rollout status daemonset/csi-secrets-store-secrets-store-csi-driver -n kube-system --timeout=300s
kubectl rollout status daemonset/csi-secrets-store-provider-gcp -n kube-system --timeout=300s
```

---

## Bước 4 — Secret trên Google Secret Manager

Tạo 1 secret cho mỗi credential, rồi gắn quyền đọc giới hạn đúng secret đó (không phải toàn
project) cho GSA tương ứng ở Bước 2:

| Tên secret | Ai dùng |
|---|---|
| `{service}-db-username` / `{service}-db-password` (× auth/menu/order/inventory/report) | role Postgres của service đó |
| `auth-service-jwt-private-key` | auth-service (ký JWT) |
| `gateway-jwt-public-key` | gateway (xác thực JWT) — nửa public của **cùng** cặp khoá |

```bash
for svc in auth-service menu-service order-service inventory-service report-service; do
  # the username must equal the CNPG role name (managed.roles in k8s/data-layer/postgres-cluster.yaml)
  printf '%s' "${svc//-/_}" | gcloud secrets create "${svc}-db-username" --data-file=-
  openssl rand -base64 24 | tr -d '\r\n' | gcloud secrets create "${svc}-db-password" --data-file=-
  for name in db-username db-password; do
    gcloud secrets add-iam-policy-binding "${svc}-${name}" \
      --member="serviceAccount:${svc}-gsm-reader@cafe-microservices.iam.gserviceaccount.com" \
      --role=roles/secretmanager.secretAccessor
  done
done

# The JWT keypair is generated in a throwaway directory so the private key never lands in the repo
pushd "$(mktemp -d)"
openssl genrsa 2048 | openssl pkcs8 -topk8 -nocrypt > jwt-private.pem
openssl rsa -in jwt-private.pem -pubout > jwt-public.pem
gcloud secrets create auth-service-jwt-private-key --data-file=jwt-private.pem
gcloud secrets create gateway-jwt-public-key --data-file=jwt-public.pem
rm jwt-private.pem jwt-public.pem
d=$PWD
popd
rmdir "$d"

gcloud secrets add-iam-policy-binding auth-service-jwt-private-key \
  --member="serviceAccount:auth-service-gsm-reader@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/secretmanager.secretAccessor
gcloud secrets add-iam-policy-binding gateway-jwt-public-key \
  --member="serviceAccount:gateway-gsm-reader@cafe-microservices.iam.gserviceaccount.com" \
  --role=roles/secretmanager.secretAccessor

# Every secret must show at least 1 version (0 means it was created empty)
for s in {auth,menu,order,inventory,report}-service-db-{username,password} auth-service-jwt-private-key gateway-jwt-public-key; do
  echo "$s: $(gcloud secrets versions list "$s" --format='value(name)' | wc -l) version(s)"
done
```

Mỗi lệnh `gcloud secrets create` sẽ lỗi nếu secret đã tồn tại, nên chỉ chạy khối này 1 lần trên
project mới. Các secret username chứa đúng tên role CNPG (`auth_service`, `menu_service`, …) vì
`managed.roles` ở Bước 6 tạo role với đúng những tên đó — lệch tên thì initContainer `wait-for-db`
của service đó sẽ timeout.

Tạo cặp khoá JWT **mới hoàn toàn** ở đây — đừng tái sử dụng cặp khoá dev đã commit cho việc chạy
local (trong `.env.example`, và trong `application-local.yml.example` của auth-service và
gateway).

Secret nào hiện 0 version thì cần thêm 1 version bằng
`gcloud secrets versions add <tên> --data-file=-`, vì chạy lại `create` sẽ lỗi "already exists".

---

## Bước 5 — Cấu hình ứng dụng (đã có sẵn trong repo, chỉ để tham khảo)

Các giá trị sau được để trống trong `application.yml` của từng service:

- `spring.datasource.username` và `spring.datasource.password` ở 5 service dùng DB;
- `app.jwt.private-key` ở `auth-service`;
- `app.jwt.public-key` ở `gateway`.

Chúng được lấy từ biến môi trường thay thế (`SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`, `APP_JWT_PRIVATE_KEY`, `APP_JWT_PUBLIC_KEY`), được nạp qua
`secretKeyRef` trong `deployment.yaml` của `charts/cafe-service` ở môi trường triển khai thật,
hoặc qua `docker-compose.yml` / `application-local.yml` của từng service khi chạy local.

Xem [auth-service/application.yml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/backend/auth-service/src/main/resources/application.yml)
và [gateway/application.yml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/backend/gateway/src/main/resources/application.yml) để thấy
đúng khuôn mẫu này.

---

## Bước 6 — Manifest tầng dữ liệu (`k8s/data-layer/`)

4 file, áp dụng trực tiếp bằng `kubectl` — không bao giờ đóng gói vào Helm chart:

- [postgres-storageclass.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/data-layer/postgres-storageclass.yaml) — 1 StorageClass
  riêng với policy `Retain` cho PVC của Postgres (các class dựng sẵn của GKE đều là
  `Delete`; Postgres là nguồn dữ liệu gốc của app này, nên đĩa của nó phải sống sót kể cả khi
  PVC/Cluster bị xoá nhầm). Lấy lại dữ liệu từ 1 đĩa được giữ lại như vậy chỉ là cách làm tay,
  bất đắc dĩ; đường khôi phục được hỗ trợ là các backup trên object store (xem "Khôi phục
  Postgres từ backup").
- [postgres-cluster.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/data-layer/postgres-cluster.yaml) — CNPG `Cluster` cùng các database và role của nó:
  - `Cluster` bootstrap `auth_db` ngay lúc tạo (1 `Cluster` chỉ bootstrap được 1 database);
  - 4 `Database` CR tạo database cho các service còn lại;
  - 5 mục `managed.roles` được reconcile dựa theo Secret `{service}-db-credentials` của từng
    service, đồng bộ từ 2 secret GSM `{service}-db-username`/`-db-password` ở Bước 4 qua cơ chế
    map `secretObjects` của CSI driver (bật ở Bước 3; định nghĩa ở `secretproviderclass.yaml`
    của Bước 7);
  - `imageName` ghim image Postgres (18.4, flavor `standard` — flavor upstream khuyên dùng cùng
    Barman Cloud Plugin) bằng 1 tag bất biến có timestamp, nên cả việc nâng cấp operator CNPG lẫn
    việc rebuild vá CVE của tag rolling upstream đều không thể âm thầm đổi thứ đang chạy. Với
    `instances: 1`, đổi giá trị này sẽ restart instance duy nhất (gián đoạn ngắn);
  - mẹo: field đúng là `spec.storage.storageClass` (field riêng của CNPG CRD), không phải
    `storageClassName` (tên field của PVC thuần) — rất dễ nhầm, dùng
    `kubectl explain cluster.spec.storage` để kiểm tra lại.
- [postgres-backup.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/data-layer/postgres-backup.yaml) — `ObjectStore` của Barman
  Cloud Plugin (trỏ tới GCS bucket, xác thực qua Workload Identity của chính Cluster, không cần
  Secret credential riêng; `retentionPolicy` 10 ngày — "Tạm dừng" nói nó chạy khi nào) và 1
  `ScheduledBackup` chạy hằng đêm (00:00 UTC).
- [kafka-cluster.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/k8s/data-layer/kafka-cluster.yaml) — `KafkaNodePool` + `Kafka` KRaft
  1 broker, ghim vào `stateful-pool` qua node affinity/toleration. Cố tình dùng StorageClass
  `standard` dựng sẵn của GKE (reclaim `Delete`), khác với Postgres — Kafka ở đây chỉ chứa
  message saga đang chuyển giữa các service, không phải dữ liệu gốc. Giống Postgres, phiên bản
  Kafka (4.3.1) được ghim, nên nâng cấp operator Strimzi không thể âm thầm đổi nó.

```bash
kubectl apply -f k8s/data-layer/postgres-storageclass.yaml
kubectl apply -f k8s/data-layer/
```

Áp StorageClass trước để PVC của CNPG `Cluster` tìm thấy class ngay từ đầu, rồi mới áp phần còn
lại của thư mục.

Có 1 thứ chưa giải quyết xong cho tới Bước 8: các Secret `{service}-db-credentials` chỉ tồn tại
khi service pod mount volume CSI, nên `managed.roles` của CNPG `Cluster` chưa thể reconcile đầy
đủ trước đó (CNPG báo trạng thái và tiếp tục retry). Khi các pod khởi động ở Bước 8, initContainer
`wait-for-db` của từng service (Bước 7) chờ tối đa 600s để role của nó dùng được.

---

## Bước 7 — Helm chart

### `charts/cafe-service` — 1 chart tái sử dụng, tạo ra 6 instance

Các value chính (schema đầy đủ: [values.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe-service/values.yaml)):

- `appName` — quyết định tên của Deployment/Service/ServiceAccount/ConfigMap *và* label selector
  của pod; riêng `SecretProviderClass` được đặt tên qua `secretProviderClassName`.
- `db.enabled` / `kafka.enabled` / `jwt.privateKey.enabled` / `jwt.publicKey.enabled` — bật/tắt
  env var, key trong ConfigMap, mục `SecretProviderClass` và initContainer `wait-for-db` nào
  được render cho instance service đó; riêng `db.enabled` còn quyết định `strategy.type` của
  rollout.
- `image.tag` mặc định là giá trị cố tình không hợp lệ `unset` — CI (Bước 9) không bao giờ push
  tag `latest`, chỉ push tag content-hash, nên 1 lần deploy quên override nó sẽ báo lỗi ngay lập
  tức thay vì âm thầm cố pull 1 tag không tồn tại. Bước 8 set nó cho từng service qua
  `--set-string <alias>.image.tag=<content-hash-tag>`.

Template ([deployment.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe-service/templates/deployment.yaml)):

- `strategy.type` là `Recreate` cho service dùng DB (`RollingUpdate` cho các service còn lại)
  — tránh việc 1 pod cũ và 1 pod mới (sau khi Flyway đã migrate) chạy song song; đánh đổi chấp
  nhận được cho 1 dự án không nhắm tới zero-downtime deploy.
- initContainer `wait-for-db` (chỉ với service dùng DB) chạy
  [wait-for-db.sh](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe-service/files/wait-for-db.sh),
  được template nhúng vào bằng `.Files.Get` (thiếu file thì render thất bại). Script thử kết nối
  `psql` lặp lại tới 600s, đọc host, user, database và password từ chính các biến của libpq là
  `PGHOST`/`PGUSER`/`PGDATABASE`/`PGPASSWORD`, `PGCONNECT_TIMEOUT` giới hạn phần kết nối của mỗi
  lần thử ở 5s, và tính khung chờ bằng `date +%s` — **không phải** `$SECONDS`, vì BusyBox `ash`
  (shell của image `postgres:16-alpine`) âm thầm coi nó là chuỗi rỗng, biến điều kiện timeout thành
  dead code. Nó ghi log lỗi của psql ở lần đầu và mỗi khi lỗi thay đổi, và in lỗi cuối cùng khi hết
  giờ: `kubectl logs <pod> -n cafe -c wait-for-db`. Bộ test của nó, `wait-for-db.test.sh`, nằm
  ngay cạnh; `.helmignore` của chart loại `*.test.sh` ra khỏi chart được đóng gói.
- `progressDeadlineSeconds: 1200` trên các Deployment dùng DB, để 1 pod đang chờ trong
  `wait-for-db` không làm fail 1 lần rollout đầu hợp lệ (xem Bước 8 để biết cách tính).
- `startupProbe` (ngân sách 30 × 10s) quyết định khi nào `readinessProbe`/`livenessProbe` mới
  bắt đầu được kiểm tra — chắc chắn hơn việc đoán 1 `initialDelaySeconds` cố định trong lúc JVM
  + Flyway khởi động trên node Spot.
- 2 volume pod riêng biệt: `config` (kiểu `configMap`) và `secrets-store` (kiểu `csi`) — không
  thể lồng chung vào 1 volume `projected`, vì `csi` không phải nguồn hợp lệ của `projected`.
  Volume CSI **bắt buộc phải** thực sự được mount (không chỉ khai báo) — 1 volume CSI không
  được mount sẽ không bao giờ kích hoạt việc đồng bộ `secretObjects` của driver, nên `Secret`
  tương ứng sẽ không bao giờ được tạo ra.

[secretproviderclass.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe-service/templates/secretproviderclass.yaml) liệt kê
các secret GSM mà instance service đó cần (có điều kiện, theo các cờ `db.enabled` /
`jwt.*.enabled`) và map chúng vào `secretObjects` — cầu nối từ "file được mount ở
`/mnt/secrets-store`" sang "1 `Secret` K8s thật mà resource khác có thể tham chiếu qua
`secretKeyRef`".

### `charts/cafe` — umbrella chart

[Chart.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe/Chart.yaml) khai báo `cafe-service` như 6 dependency Helm được
*alias* (mẫu chuẩn cho nhiều instance service gần giống nhau dùng chung 1 chart).
[values.yaml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/charts/cafe/values.yaml) set `global.gcpProjectId` và
`global.imageRegistry` (host+path của Artifact Registry mà image mọi service được pull về, ghép
vào trước `image.repository` khi render mỗi Deployment) 1 lần duy nhất, cộng thêm 1 block cho mỗi
alias với giá trị port/db/kafka/jwt thật của service đó và `image.repository` riêng của nó.

- `global.tracing.export.zipkin.enabled` (`false`) — được render vào ConfigMap của mọi service
  thành `management.tracing.export.zipkin.enabled` của Spring. Chỉ exporter Zipkin bị tắt:
  sampling, việc truyền trace context và trace ID trong log không bị ảnh hưởng. Không dùng
  `management.tracing.export.enabled` chung, vì nó còn tắt cả việc truyền trace context và việc
  gắn trace ID vào log. Chart dùng chung `cafe-service` mặc định là `true`, đúng mặc định của
  Spring.

```bash
# `build`, not `update`: packages the file:// subchart against the committed Chart.lock without
# rewriting it, and fails if Chart.yaml's dependencies no longer match that lock;
# --skip-refresh: a file:// dependency needs none of the Helm repositories added above
helm dependency build --skip-refresh charts/cafe
# render and lint locally before touching the real cluster (rendering is the real check);
# lint should report 0 failed - an "icon is recommended" INFO and a "templates/ directory does
# not exist" warning are normal for this umbrella chart
helm lint charts/cafe
helm template charts/cafe > /dev/null
```

---

## Bước 8 — Deploy thật

Bước này cần có image để deploy: làm phần thiết lập GCP ở Bước 9 và để 1 lần chạy CI build image
trước.

Tag image của mỗi service là 1 hash nội dung tính từ source của chính nó, `common-lib` và pom
cha (xem Bước 9), nên — khác với 1 tag release dùng chung — không thể dùng 1 `$TAG` cho cả 6
service. [scripts/deploy.sh](https://github.com/tanhutminh/cafe-microservice-project/blob/master/scripts/deploy.sh)
deploy image ứng với commit đang checkout. Trước mọi lần `helm upgrade`, script dừng nếu:

- bash cũ hơn 4.3, hoặc `gcloud`, `helm`, `kubectl`, `gke-gcloud-auth-plugin`, `git` hay
  `sha256sum` không có trên `PATH` (script nêu tên mọi tool còn thiếu).
- `backend/`, `charts/` hoặc `scripts/image-tag.sh` có thay đổi chưa commit (bất kể
  `status.showUntrackedFiles` đặt thế nào), có file mà git status được bảo bỏ qua (assume-unchanged
  hoặc skip-worktree, được liệt kê với chính tag `h`/`s`/`S` của git; sparse checkout cũng đặt
  `S`), hoặc `charts/` chứa file bị git-ignore (được liệt kê với tiền tố `!! `). 1 lần deploy phải
  ứng với đúng 1 commit: image được build từ code `backend/` đã commit, nên sửa đổi backend ở local
  sẽ âm thầm không được deploy; còn chart và `image-tag.sh` được dùng nguyên trạng từ working tree,
  nên sửa đổi ở đó sẽ được deploy mà không được ghi lại trong commit nào. Helm đóng gói mọi file
  trong thư mục chart không bị `.helmignore` của nó loại ra, dù có bị git-ignore hay không; riêng
  `charts/cafe/charts/*.tgz` được miễn khỏi mọi kiểm tra này, vì lần deploy tự tạo lại nó. Bản
  thân `scripts/deploy.sh` cũng được miễn, để có thể sửa script rồi thử trước khi commit; nội dung
  release thuộc về chart, không thuộc về các cờ `helm` của script.
- không đọc được commit `HEAD`, hoặc nó không nằm trên `master`: không phải commit mà
  `origin/master` đang trỏ tới (theo lần fetch gần nhất), cũng không phải 1 commit tổ tiên của nó.
  `HEAD` chỉ được đọc 1 lần, nên bước kiểm tra này, mọi image tag và description của release đều chỉ
  cùng 1 commit. CI chỉ build image từ `master`, còn chart được deploy từ working tree, vốn phải
  khớp với `HEAD` theo kiểm tra ở trên, nên 1 commit trên nhánh khác sẽ đưa lên cluster những thay
  đổi chart chưa được lần merge nào ghi nhận. Hãy merge nó qua 1 PR rồi deploy từ `master`, hoặc
  chạy `git fetch` nếu nó đã có trên `master`; thiếu ref `origin/master` cũng làm bước kiểm tra này
  fail. Sửa đổi chưa commit của `scripts/deploy.sh` vẫn thử được từ `master`, vì kiểm tra ở trên
  miễn cho nó. Các kiểm tra trạng thái repo này chạy trước các kiểm tra môi trường bên dưới, và các
  biến git được kế thừa (`GIT_DIR` và các biến tương tự) bị xóa trước, nên chúng luôn đọc đúng
  checkout này.
- `helm` trên `PATH` cũ hơn 4.1.1 (xem phần Yêu cầu môi trường), hoặc không báo được phiên bản.
- `gcloud` trên `PATH` không khởi động được (trên Git Bash cho Windows, hãy đặt `CLOUDSDK_PYTHON`
  — xem phần Yêu cầu môi trường).
- kube-context `gke_cafe-microservices_us-central1-a_cafe-cluster` không tồn tại (chạy lệnh
  `get-credentials` ở phần Yêu cầu môi trường). Mọi lệnh làm việc với cluster đều chỉ định rõ
  context đó, nên 1 lần deploy không bao giờ rơi vào cluster mà context hiện tại đang trỏ tới.
- không tính được tag của 1 service tại `HEAD` (ví dụ: thư mục `backend/<service>` của nó không có
  trong commit).
- image của 1 service nào đó bị thiếu hoặc không truy cập được trên Artifact Registry (script nêu
  tên image đó). Set 1 tag mà không có image tương ứng chỉ dẫn tới `ImagePullBackOff` âm thầm về
  sau. Nếu lỗi của chính gcloud in phía trên thông báo không phải lỗi not-found (xác thực, quyền
  hoặc mạng), hãy sửa lỗi đó trước. Nếu không: tag được tính từ nội dung `backend/` đã commit của
  `HEAD`, và CI chỉ build image từ `master` (Bước 9). Vậy hoặc `HEAD` là 1 commit `master` mà chưa
  lần chạy CI nào build (ví dụ 1 commit bên trong 1 nhánh đã merge) — hãy deploy 1 commit mà CI đã
  build, như đỉnh của `master` hoặc 1 merge commit — hoặc lần chạy `backend-ci` trên `master` cho
  nó vẫn đang chạy hoặc đã fail (chờ, hoặc sửa lỗi); nếu `master` đã có nội dung đó nhưng chưa lần
  chạy nào build nó, chạy `workflow_dispatch` cho `backend-ci` trên `master` để build. Bước kiểm
  tra này dùng credential gcloud của chính bạn; còn node của cluster pull image bằng service
  account riêng của chúng, được cấp `roles/artifactregistry.reader` ở phần thiết lập GCP của
  Bước 9.

Nếu không, nó chạy `helm dependency build --skip-refresh` (để subchart `cafe-service` đã đóng gói
luôn khớp với `charts/cafe-service`), rồi `helm upgrade --install` với
`-n cafe --wait=watcher --timeout 22m`, ghi commit đang checkout làm description của release
(`helm history` hiện nó cho mọi revision đã deploy thành công; revision fail thì hiện thông báo lỗi
của Helm, còn revision do rollback tạo ra thì ghi "Rollback to N"), và liệt kê pod và Secret trong
namespace `cafe`:

```bash
git checkout master && git pull
bash scripts/deploy.sh
```

Script chỉ trả về khi cả 6 app Deployment đã sẵn sàng — các pod dùng DB đi qua `Init:0/1` trong
lúc initContainer `wait-for-db` của chúng chờ (Bước 6/7) — hoặc thất bại, kèm danh sách pod để
thấy pod nào đang kẹt (xem phần Xử lý sự cố bên dưới). Chart đặt `progressDeadlineSeconds` của mỗi
Deployment dùng DB là 1200 (gateway, không có `wait-for-db`, giữ mặc định 600s): 1 pod đang chờ
trong `wait-for-db` không tạo ra tiến triển rollout nào, và lần rollout đầu chậm nhất mà vẫn hợp lệ
mất khoảng 930s (1 khung 600s của `wait-for-db` cộng tối đa 8s cho lần thử cuối, 10s back-off
khởi động lại nếu database lên ngay sau khung đó, tối đa 300s cho startup probe và 1 chu kỳ
readiness 10s), cộng thời gian pull image và, khi autoscaler phải thêm 1 node cho
`stateless-pool`, thời gian khởi động node đó kể cả pod CSI driver của nó — 1200s chừa khoảng 4,5
phút cho các phần đó. `--wait=watcher` của Helm 4.1.1 trở lên đánh dấu 1 Deployment đã quá
deadline là Failed ("Progress deadline exceeded") và trả về lỗi đó ngay khi mọi resource khác đã
ổn định; timeout 22 phút cao hơn deadline 2 phút, nên chính deadline, chứ không phải 1 timeout
trơn, kết thúc 1 lần rollout bị kẹt. Sau 1 lần chạy thành công, các Secret
`{service}-db-credentials` và `*-jwt-key` phải xuất hiện, còn `cafe-postgres-1` và pod Kafka cũng
phải `Running`.

Trong lúc chờ, script không in gì: 1 pod bị kẹt, chẳng hạn ở `ImagePullBackOff`, vẫn được tính là
đang tiến hành cho tới deadline của Deployment đó (600s với gateway, 1200s với các service dùng
DB), không bao giờ vượt quá timeout 22 phút. Theo dõi từ 1 shell khác bằng
`kubectl get pods -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -w`.

`scripts/deploy.sh` luôn truyền `-n cafe` cho lệnh `helm upgrade --install` của nó — cần thiết vì
bản thân chart không hardcode namespace nào (mọi template đều dùng `{{ .Release.Namespace }}`), nên
nếu chạy `helm upgrade --install cafe charts/cafe` mà thiếu `-n cafe` thì mọi thứ, kể cả
ServiceAccount riêng của từng Deployment, sẽ âm thầm bị deploy vào `default` thay vì `cafe`.

### Xử lý sự cố khi deploy

Các triệu chứng có thể gặp khi deploy — phần lớn ở lần deploy thật đầu tiên — kèm nguyên nhân gốc:

1. **CSI mount lỗi `driver name secrets-store.csi.k8s.io not found`** trên 1 node rất mới —
   thường chỉ là DaemonSet CSI chưa khởi động xong trên node đó. Kiểm tra tuổi của node trước
   khi coi đây là vấn đề thật.
2. **`add-iam-policy-binding` báo lỗi `Identity Pool does not exist`** — Workload Identity chưa
   thực sự được bật trên cluster (Bước 1). Sửa ở cấp cluster (`--workload-pool=...`), sau đó
   mỗi node pool còn cần thêm `--workload-metadata=GKE_METADATA` (có hiệu lực ngay với các
   workload đang chạy trên pool đó).
3. **CSI mount báo `PermissionDenied: iam.serviceAccounts.getAccessToken denied` ngay sau khi
   vừa gắn IAM binding mới** (Bước 2) — độ trễ lan truyền, tự hết sau vài phút nhờ kubelet tự
   retry.
4. **CSI mount thành công (`SecretProviderClassPodStatus` báo `mounted: true`) nhưng `Secret`
   tương ứng không bao giờ xuất hiện** — chưa bật `syncSecret.enabled` trên Helm release của
   CSI driver (Bước 3). Kiểm tra bằng
   `kubectl auth can-i list secrets --as=system:serviceaccount:kube-system:secrets-store-csi-driver -A`.
5. **Postgres báo `ContinuousArchiving=False` và bucket backup vẫn trống** — đọc điều kiện bằng
   `kubectl get cluster cafe-postgres -n cafe -o jsonpath='{.status.conditions}'` và lỗi thật
   bằng `kubectl logs cafe-postgres-1 -n cafe -c plugin-barman-cloud`. Lỗi
   `403 ... does not have storage.buckets.get access` nghĩa là thiếu binding bucket
   `roles/storage.legacyBucketReader` ở Bước 2 (chỉ có `objectAdmin` thì không đủ quyền đó). Nếu
   lỗi xảy ra trước khi tới được bucket, khả năng cao là thiếu binding Workload Identity của
   `cafe-postgres-backup` → `cafe/cafe-postgres`, cũng ở Bước 2.
6. **CNPG `Cluster` báo thiếu Secret mật khẩu của 1 role** — bình thường cho tới Bước 8: các
   Secret `{service}-db-credentials` chỉ tồn tại khi service pod mount volume CSI (xem Bước 6).
   Tự hết khi các pod chạy.
7. **1 pod ứng dụng bị `ImagePullBackOff`** — cơ chế kiểm tra ở Bước 8 lẽ ra đã phát hiện image
   thiếu trước khi tới đây. `kubectl describe pod <pod> -n cafe` cho thấy `image:` mà pod đang cố
   pull và, trong phần events, lý do pull thất bại:
   - **not found** — reference không khớp với những gì `gcloud artifacts docker images describe`
     báo cho tag đó. `deploy.sh` từ chối chạy khi có thay đổi chưa commit trong `backend/`,
     `charts/` hoặc `scripts/image-tag.sh`, nên nếu lệch thì thường là do image được deploy theo
     cách khác (ví dụ: chạy `helm upgrade` thủ công với tag tự tính) — deploy lại qua `deploy.sh`,
     vì bước kiểm tra của nó xác nhận từng image có tồn tại trước.
   - **403 / denied** — image có tồn tại (bước kiểm tra ở Bước 8, dùng credential của chính bạn,
     đã pass), nhưng node không đọc được: kiểm tra service account của node có
     `roles/artifactregistry.reader` trên repository (phần thiết lập GCP ở Bước 9), và access
     scope của các node pool có gồm `devstorage.read_only` hoặc `cloud-platform`.
8. **Chạy lại bị lỗi `another operation (install/upgrade/rollback) is in progress`** — Helm từ
   chối vì revision cuối của release vẫn đang `pending-*`. Hoặc 1 lần deploy hay rollback khác trên
   release này vẫn đang chạy, hoặc 1 lần trước đó bị cắt ngang trước khi Helm kịp ghi kết quả (đóng
   terminal hay phiên SSH, process bị kill, mất hẳn kết nối tới cluster, hoặc cluster không kết nối
   được đúng lúc Helm cố ghi kết quả). Ctrl+C trong lúc `helm upgrade --install` của `deploy.sh`
   chạy thì thường được xử lý: Helm ghi revision đó là `failed`; nếu nó vẫn hiện `pending-upgrade`
   thì Helm đã không nhận được tín hiệu — coi như bị cắt ngang (bên dưới). Xem lịch sử release bằng
   `helm history cafe -n cafe --kube-context gke_cafe-microservices_us-central1-a_cafe-cluster`.
   1 thao tác còn sống không thể ở trạng thái pending lâu hơn nhiều so với timeout 22 phút của nó,
   nên nếu thời điểm UPDATED của revision pending vẫn nằm trong khoảng đó cộng vài phút (khoảng 25
   phút), có thể 1 lần deploy khác vẫn đang chạy — hãy chờ rồi kiểm tra lại. Khi đã cũ hơn, thao
   tác đó đã bị cắt ngang:
   - **`pending-upgrade`** — rollback về revision `deployed` gần nhất (nếu không có revision nào
     `deployed`, uninstall như với `pending-install`):
     `helm rollback cafe <revision> -n cafe --kube-context gke_cafe-microservices_us-central1-a_cafe-cluster --wait=watcher --timeout 22m`,
     rồi chạy lại.
   - **`pending-rollback`** (`helm rollback` không xử lý Ctrl+C, nên 1 lần rollback bị ngắt sẽ nằm
     lại ở pending) — chạy lại đúng lần rollback đó, về revision mà description "Rollback to N" của
     nó ghi, bằng cùng lệnh trên. Không phải về revision `deployed` gần nhất: đó có thể chính là
     bản lỗi mà lần rollback đang muốn rời khỏi.
   - **`pending-install`** (lần install đầu tiên chưa bao giờ xong) — gỡ nó đi:
     `helm uninstall cafe -n cafe --kube-context gke_cafe-microservices_us-central1-a_cafe-cluster`,
     rồi chạy lại. Postgres và Kafka nằm ngoài release (Bước 6) nên không bị ảnh hưởng; các Secret
     được sync sẽ xuất hiện lại khi pod mount lại volume CSI.
   - **`deployed` hoặc `failed`** — thao tác kia đã xong trong lúc đó; chỉ cần chạy lại `deploy.sh`.
9. **Bản thân revision mới bị lỗi** (upgrade fail, hoặc thành công nhưng service chạy sai) — cách
   thường làm là commit bản sửa rồi deploy lại. Nếu cần quay về trạng thái chạy được trước, ưu tiên
   checkout commit `master` tốt gần nhất (description trong `helm history` ghi commit của từng
   revision đã deploy; với 1 revision "Rollback to N", xem description của revision N, lặp lại nếu
   N cũng là 1 rollback) rồi chạy lại `deploy.sh`: image của nó đã có sẵn. Nếu không, rollback về 1
   revision cụ thể — sau 1 lần upgrade fail thì là revision vẫn đang `deployed`; sau 1 lần upgrade
   thành công nhưng chạy sai thì là revision `superseded` gần nhất — bằng
   `helm rollback cafe <revision> -n cafe --kube-context gke_cafe-microservices_us-central1-a_cafe-cluster --wait=watcher --timeout 22m`
   (`helm rollback` không kèm revision sẽ quay về revision ngay trước, kể cả khi revision đó fail;
   nếu lần install đầu tiên chưa từng thành công thì không có gì để rollback). Cách nào thì cũng chỉ
   image và manifest quay lại, còn schema database thì không: mặc định `*:future` của Flyway để
   image cũ khởi động qua được các migration nó không biết, nhưng `ddl-auto: validate` của
   Hibernate làm nó fail lúc khởi động nếu 1 cột hay bảng nó map tới đã bị xóa, đổi tên hoặc đổi
   kiểu, và 1 cột `NOT NULL` mới không có giá trị mặc định sẽ làm lệnh insert của nó fail lúc
   chạy. Event Kafka mà bản mới đã publish cũng có thể không deserialize được ở consumer cũ và rơi
   vào DLQ. Không có gì replay các record trong `<topic>.dlq`; chúng được giữ lại để chẩn đoán. Các
   order đang chờ reply cho lệnh reserve hoặc commit sẽ được job saga reconciliation của
   order-service gửi lại lệnh và, sau khi hết số lần retry, đưa về OPEN hoặc CONFIRMED. 1 lệnh
   release-stock đã rơi vào DLQ thì không được retry, nên lượng stock đó vẫn bị giữ cho tới khi
   sửa tay. Nếu chính reply mới là thứ rơi vào DLQ thì inventory-service đã thực hiện lệnh rồi:
   order quay về OPEN hoặc CONFIRMED trong khi stock vẫn bị giữ hoặc đã bị trừ — cũng phải sửa tay.
   Mục 8 xử lý trường hợp release bị kẹt ở `pending-*`.
10. **1 pod dùng DB đứng mãi ở `Init:0/1`** — initContainer `wait-for-db` của nó chưa kết nối
    được tới database. Xem lý do bằng `kubectl logs <pod> -n cafe -c wait-for-db`: nó ghi log lỗi
    của psql mỗi khi lỗi thay đổi (connection timeout hay connection refused nghĩa là Postgres
    chưa chạy hoặc chưa truy cập được; ở lần deploy đầu, lỗi xác thực là bình thường trong 1 lúc,
    cho tới khi CNPG tạo role từ Secret vừa được sync — xem mục 6). Sau 600s nó thoát kèm lỗi cuối
    cùng và khởi động lại với 1 khung chờ mới; nếu mãi không qua được, `progressDeadlineSeconds`
    1200s của Deployment sẽ làm fail lần rollout.

---

## Bước 9 — CI pipeline

Build và push image của từng service lên Artifact Registry ở mỗi lần push lên `master` mà job
`test` có chạy (xem "Workflow làm gì" bên dưới), hoặc qua `workflow_dispatch` thủ công trên
`master` — chỉ sau khi các kiểm tra lint/test/coverage và bước quét secret của chính lần chạy đó
pass. Mọi thứ dưới đây đã được implement trong
[backend-ci.yml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/.github/workflows/backend-ci.yml)
và [image-tag.sh](https://github.com/tanhutminh/cafe-microservice-project/blob/master/scripts/image-tag.sh)
— mục này chỉ ghi lại phần cấu hình phía GCP mà 2 file đó giả định đã có, và cách các phần khớp
với nhau.

### Thiết lập phía GCP

```bash
PROJECT_ID=cafe-microservices
REGION=us-central1
AR_REPO=cafe-images
CI_SA=github-actions-ci
WIF_POOL=github-actions-pool
WIF_PROVIDER=github-actions-provider
GH_REPO=tanhutminh/cafe-microservice-project

# The registry the workflow pushes to. --immutable-tags: a pushed content-hash tag can never be
# moved to a different image, so what deploy.sh checked is what the nodes pull. It also means a
# tagged image can't be deleted or untagged - turn immutability off first if you ever must.
gcloud artifacts repositories create "$AR_REPO" \
  --repository-format=docker --location="$REGION" --project="$PROJECT_ID" \
  --description="Backend service images" --immutable-tags

# A repository created before without the flag: turn it on, then confirm (prints True)
gcloud artifacts repositories update "$AR_REPO" \
  --location="$REGION" --project="$PROJECT_ID" --immutable-tags
gcloud artifacts repositories describe "$AR_REPO" \
  --location="$REGION" --project="$PROJECT_ID" --format='value(dockerConfig.immutableTags)'

# Nodes need to pull from it. A node pool with no --service-account set at creation uses the
# Compute Engine default SA - `gcloud container node-pools describe ... --format="value(config.serviceAccount)"`
# then literally prints "default", not the real email; the real principal is always
# <project-number>-compute@developer.gserviceaccount.com.
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format="value(projectNumber)")
gcloud artifacts repositories add-iam-policy-binding "$AR_REPO" \
  --location="$REGION" --project="$PROJECT_ID" \
  --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role=roles/artifactregistry.reader

# A dedicated GSA for CI to push as - never a static key, see Workload Identity Federation below
gcloud iam service-accounts create "$CI_SA" --project="$PROJECT_ID" \
  --display-name="GitHub Actions CI (backend image build+push)"
gcloud artifacts repositories add-iam-policy-binding "$AR_REPO" \
  --location="$REGION" --project="$PROJECT_ID" \
  --member="serviceAccount:${CI_SA}@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role=roles/artifactregistry.writer

# Workload Identity Federation for GitHub Actions - a separate trust setup from the per-pod one
# in Step 2 (that one lets a K8s pod act as a GSA; this one lets a GitHub Actions run act as one,
# with no per-pod-equivalent component). The attribute-condition restricts it to this exact repo
# AND to runs whose ref is master (here, a push or a workflow_dispatch on master; a pull_request
# run's ref is refs/pull/<n>/merge), not just anyone who learns the provider's resource name.
gcloud iam workload-identity-pools create "$WIF_POOL" \
  --project="$PROJECT_ID" --location=global --display-name="GitHub Actions"
gcloud iam workload-identity-pools providers create-oidc "$WIF_PROVIDER" \
  --project="$PROJECT_ID" --location=global --workload-identity-pool="$WIF_POOL" \
  --display-name="GitHub Actions OIDC" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref" \
  --attribute-condition="assertion.repository=='${GH_REPO}' && assertion.ref=='refs/heads/master'" \
  --issuer-uri="https://token.actions.githubusercontent.com"
gcloud iam service-accounts add-iam-policy-binding \
  "${CI_SA}@${PROJECT_ID}.iam.gserviceaccount.com" --project="$PROJECT_ID" \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${WIF_POOL}/attribute.repository/${GH_REPO}"

# The workflow file needs this exact resource name in its workload_identity_provider field
gcloud iam workload-identity-pools providers describe "$WIF_PROVIDER" \
  --project="$PROJECT_ID" --location=global --workload-identity-pool="$WIF_POOL" \
  --format="value(name)"
```

Không cần GitHub Secret nào cho phần này — Workload Identity Federation đổi token OIDC riêng của
GitHub lấy 1 access token GCP có thời hạn ngắn ngay tại thời điểm chạy, nên ngay từ đầu đã không
có credential tĩnh nào cần lưu hay có thể bị lộ.

### Workflow làm gì

5 job, đều nằm trong [backend-ci.yml](https://github.com/tanhutminh/cafe-microservice-project/blob/master/.github/workflows/backend-ci.yml):

- **`changes`** — [dorny/paths-filter](https://github.com/dorny/paths-filter) tính ra 5 output
  riêng biệt, để các job còn lại có thể bỏ qua khi không liên quan (đây là nơi duy nhất liệt kê
  các path; các mục bên dưới chỉ gọi output theo tên):
  - `backend` — `backend/**`;
  - `scripts` — `scripts/**` hoặc `.gitignore` ở gốc repo, vì cơ chế kiểm tra trạng thái repo của
    `deploy.sh` và các case của `deploy.test.sh` dựa vào các pattern trong đó;
  - `charts` — `charts/**`;
  - `k8s` — `k8s/**`;
  - `workflow` — chính file workflow. Mọi job và step được gate theo các output kia cũng chạy khi
    output này là true, nên 1 PR sửa step nào thì step đó được chạy thử trước khi merge (và
    `deploy.test.sh`, vốn đọc file workflow, cũng chạy lại).

  Trên PR, filter so với nhánh base; trên 1 lần push lên `master`, so với đỉnh nhánh ngay trước
  lần push đó (nên 1 lần push nhiều commit được xét như 1 khối). Cố tình **không đặt path filter
  trên trigger của chính workflow** (`on.push`/`on.pull_request`) — nếu đặt, cả workflow (chứ
  không chỉ 1 job) sẽ không bao giờ chạy cho 1 PR không liên quan (ví dụ: chỉ sửa frontend), và
  một khi `changes`/`gitleaks`/`test`/`validate-manifests` đã là required status check (xem
  Branch protection bên dưới), 1 PR không có lần chạy check nào cho chúng sẽ bị chặn merge vĩnh
  viễn, chứ không chỉ được bỏ qua đúng cách.
- **`gitleaks`** — quét secret (xem `.gitleaksignore` bên dưới). Chạy vô điều kiện ở mọi lần chạy
  workflow (push lên `master`, PR hay `workflow_dispatch`), không có path filter nào — secret có
  thể lọt vào bất kỳ loại file nào (1 credential dán nhầm vào doc, 1 key lạc vào manifest YAML),
  không riêng gì Java backend, nên không bị gate theo `changes` như `test`/`validate-manifests`.
- **`test`** — chỉ chạy khi output `backend`, `scripts`, `charts`, `k8s` hoặc `workflow` là true
  (hoặc khi `workflow_dispatch`); có `charts` và `k8s` vì `deploy.test.sh` đối chiếu `deploy.sh` với
  các file trong `charts/` và `k8s/data-layer/`, và có `charts` còn vì job này lint và test script
  initContainer `wait-for-db` của chính chart. Job chạy 2 nhóm kiểm tra độc lập; mỗi nhóm vẫn chạy
  khi nhóm kia fail, nên 1 lỗi không bao giờ che mất kết quả của nhóm còn lại:
  - các kiểm tra script: `shellcheck` trên mọi script trong `scripts/` và trong `files/` của các
    chart, rồi 3 bộ test `scripts/image-tag.test.sh`,
    `charts/cafe-service/files/wait-for-db.test.sh` và `scripts/deploy.test.sh`. shellcheck chạy từ
    1 image được pin theo digest; cùng lệnh đó dùng để lint ở local:

    ```bash
    docker run --rm -v "$PWD:/mnt:ro" -w /mnt koalaman/shellcheck:v0.11.0@sha256:61862eba1fcf09a484ebcc6feea46f1782532571a34ed51fedf90dd25f925a8d -x scripts/*.sh charts/*/files/*.sh
    ```

    (trên Git Bash, thêm `MSYS_NO_PATHCONV=1` ở đầu lệnh). `wait-for-db.test.sh` chạy chính
    `wait-for-db.sh` bằng `sh` với các file thực thi giả `psql`, `date` và `sleep`: kiểm tra nó ghi
    log gì và khi nào, mốc 600s, việc `psql` không nhận cờ kết nối nào, và việc đồng hồ chỉ được
    đọc bằng `date +%s`. Mỗi lần chạy script bị `timeout` giới hạn ở 10s, nên 1 vòng lặp không bao
    giờ kết thúc sẽ làm fail test case đó thay vì treo CI. Nó cũng kiểm tra template Deployment cung
    cấp cho initContainer các biến `PGHOST`, `PGUSER`, `PGPASSWORD` và `PGDATABASE` (user và
    password lấy từ key `username`/`password` của Secret credentials, host và database lấy từ
    `db.host`/`db.name` của chart) cùng 1 giá trị `PGCONNECT_TIMEOUT` dương.
    `deploy.test.sh` unit-test các hàm của `deploy.sh` (cách ráp image reference và các đối số
    `--set-string`, việc tag được tính tại đúng git ref được truyền vào, kiểm tra phiên bản bash,
    tool, phiên bản Helm và việc `gcloud` khởi động được, và việc `main` chạy mọi kiểm tra theo đúng
    thứ tự đã mô tả, trước khi build chart), rồi chạy toàn bộ script trong 1 git repo tạm với các
    file thực thi giả `kubectl`, `helm`, `gcloud` và `gke-gcloud-auth-plugin` ghi lại mọi lời gọi,
    tách rõ từng tham số. Git ở đó được cô lập khỏi config và môi trường git của bạn, và được bảo vệ
    để chỉ có thể tác động lên đúng repo tạm đó. Mỗi cơ chế chặn (file chưa commit, bị git ẩn hoặc
    bị git-ignore, không đọc được commit `HEAD`, `HEAD` không nằm trên `origin/master`, phiên bản
    Helm, `gcloud` không khởi động được, kube-context, không tính được tag, thiếu image) phải dừng
    trước mọi lời gọi phía sau; 1 lần chạy thành công trọn vẹn phải gọi đúng các lệnh mong đợi theo
    đúng thứ tự, không ghi gì ra stderr, và trên stdout chỉ có output của bước build chart, upgrade
    và việc liệt kê pod và Secret; còn 1 lần upgrade thất bại vẫn phải trỏ tới runbook. Các tool
    thật không bao giờ được gọi (xem Bước 8 để biết `deploy.sh` làm gì). `deploy.test.sh` cũng fail
    nếu đường dẫn registry hoặc danh sách 6 service bị lệch giữa `deploy.sh`, `charts/cafe` và
    workflow này, vì cả 3 đều lặp lại chúng; nếu `k8s/data-layer/` không ghi namespace nào, hoặc ghi
    1 namespace khác với namespace mà `deploy.sh` deploy vào (`cafe`); nếu `progressDeadlineSeconds`
    của các Deployment dùng DB không còn đủ cho khung chờ của `wait-for-db` (đọc từ
    `wait-for-db.sh`, file mà template phải nhúng đúng 1 lần) cộng startup probe cộng 2 phút; hoặc
    nếu timeout Helm của `deploy.sh` không lớn hơn deadline lớn nhất đang có hiệu lực (tính cả mặc
    định 600s của gateway) ít nhất 2 phút.
  - các kiểm tra Maven, khi output `backend` hoặc `workflow` là true, ở mọi lần push lên
    `master` mà `test` có chạy, hoặc khi `workflow_dispatch`, bước sau chỉ chạy khi bước trước
    pass: `spotless:check` (kiểm tra format, chỉ có ý nghĩa thật trên 1 lần chạy `pull_request` —
    xem ghi chú về Spotless ở dưới), toàn bộ reactor `mvn test`, và `mvn jacoco:check` với 5 module
    có bật sàn coverage (mỗi `pom.xml` của module tự đặt `jacoco.line.coverage.minimum` — 1
    ratchet không cho phép thụt lùi: khớp đúng coverage hiện tại của module đó, hoặc mặc định 70%
    của pom cha cho module đã đạt hoặc vượt mức đó, và chỉ tăng dần khi coverage cải thiện).

  Vì vậy 1 PR không đổi code backend, cũng không đổi file workflow, sẽ bỏ qua lần chạy Maven khoảng
  2 phút, trong khi `test` vẫn là 1 required check duy nhất. Trên `master` các kiểm tra Maven chạy
  mỗi khi `test` chạy, vì đó là nơi `build-and-push` chạy: mọi image nó push đều đến từ 1 lần chạy
  đã test đúng cây backend mà image đó được build ra, kể cả khi bản thân lần push chỉ đụng tới
  script, file chart, manifest trong `k8s/` hay `.gitignore` ở gốc repo (file này chứa pattern
  ignore cho toàn bộ repo, nên 1 thay đổi trong đó dành cho phần khác của repo, ví dụ frontend, cũng
  tốn lần chạy Maven đó; `build-and-push` sau đó thấy mọi image đã có sẵn).
- **`validate-manifests`** — chặn việc 1 resource CNPG/Strimzi/Barman hoặc 1 StorageClass bị thêm
  nhầm vào `charts/*/templates/` (tầng data layer đó nằm ngoài mọi Helm release, xem "Kiến trúc tổng
  quan"), sau đó `helm dependency build --skip-refresh` (dùng `build` thay vì `update`, để 1
  `Chart.lock` bị lệch làm check fail thay vì được tạo lại âm thầm trên runner), 1 bước kiểm tra
  không có bộ test nào (`*.test.sh`) lọt vào subchart `cafe-service` đã đóng gói (`.helmignore` của
  nó loại chúng ra), và `helm lint`/`helm template` (render 1 lần cho mỗi service, mỗi lần kiểm tra
  ConfigMap của service đó có tắt export span sang Zipkin), với Helm được pin ở v4.3.0 để kết quả
  tái lập được (các bước này chạy khi output `charts`, `k8s` hoặc `workflow` là true, hoặc khi
  `workflow_dispatch`), rồi — chỉ khi output `k8s` hoặc `workflow` là true, hoặc khi
  `workflow_dispatch` — `kubeconform` với `k8s/data-layer/*.yaml`. kubeconform không đi kèm schema
  nào; nó tải schema của các kind có sẵn từ
  [yannh/kubernetes-json-schema](https://github.com/yannh/kubernetes-json-schema) và schema
  CNPG/Strimzi/Barman từ [CRDs-catalog](https://github.com/datreeio/CRDs-catalog) của cộng đồng, cả
  2 đều được pin theo 1 commit. Không có gì tự cập nhật các pin đó: làm mới bằng
  `git ls-remote <repo> HEAD`, và luôn làm mới pin của CRDs-catalog khi phiên bản CNPG, Strimzi hay
  barman-cloud trong `k8s/` thay đổi, nếu không manifest sẽ bị kiểm theo schema CRD cũ.
- **`build-and-push`** — cần cả `test` lẫn `gitleaks` cùng thành công, và chỉ chạy khi push (hoặc
  `workflow_dispatch` thủ công) lên `master`, không bao giờ chạy trên PR. Bước build image của nó
  chỉ đóng gói (`-DskipTests`): test chỉ chạy 1 lần, trong job `test`. Với mỗi trong 6 service: tính
  tag bằng `scripts/image-tag.sh <service>` (hash nội dung của thư mục service đó, `common-lib` và
  pom cha — đúng các input mà `Dockerfile` của nó copy vào; xem comment ở đầu file script để biết
  những gì cố tình bị loại ra, và về hằng số `salt` — tăng giá trị này để buộc tag của mọi service
  đổi ngay cả khi không input nào trong số đó thay đổi, ví dụ sau khi vá bảo mật base image), kiểm
  tra xem Artifact Registry đã có image ở tag đó chưa (`docker manifest inspect`), và chỉ build+push
  nếu chưa có. Điều này làm job trở nên idempotent: 1 lần chạy `workflow_dispatch` trên `master`,
  hoặc bất kỳ lần push nào sau đó lên `master` mà `test` có chạy, sẽ build những gì còn thiếu sau
  khi các kiểm tra Maven của chính lần chạy đó pass, bất kể lần chạy trước đã build hay chưa build
  gì — kể cả nội dung còn chưa được build vì job `test` của 1 lần chạy trước thất bại, thứ mà 1 kiểm
  tra kiểu "commit này có đụng tới service này không" đơn thuần sẽ bỏ sót vĩnh viễn. Push nào mà
  `test` không chạy (ví dụ chỉ sửa docs) thì không build gì, nên sau 1 lần chạy thất bại, hãy kích
  hoạt `workflow_dispatch` trên `master` nếu cần image trước lần đổi backend kế tiếp. Immutable tags
  của repository (phần thiết lập GCP ở trên) từ chối mọi lần push làm 1 tag đã có trỏ sang image
  khác, nên nếu `docker manifest inspect` lỗi tạm thời với 1 image thật ra đã có, image build lại
  (với digest khác) bị từ chối và job fail — hãy chạy lại job bị fail. 2 lần chạy của job này không
  bao giờ làm cùng 1 service cùng lúc (1 nhóm `concurrency` cho mỗi service), nên 2 lần chạy trên
  `master` sát nhau không cùng build lại 1 service không đổi rồi bị từ chối lần push thứ 2. Với
  `queue: max`, các lần chạy job này cho cùng 1 service chờ trong nhóm thay vì thay thế nhau, nên
  lần nào chạy sau chỉ kiểm tra khi lần trước đã xong, và bỏ qua bước build nếu lần trước đã push
  image.

Mọi job đều đặt `timeout-minutes` (từ 5 đến 20 phút, thay cho mặc định 360 phút của GitHub), nên
1 lần pull/push image bị treo sẽ làm check fail thay vì giữ 1 required status ở trạng thái chờ
hàng giờ; các step script trong `test` cũng có giới hạn riêng: 2 phút, hoặc 5 phút với
`deploy.test.sh`, vì nó chạy `deploy.sh` từ đầu tới cuối hàng chục lần.

**Vì sao kiểm tra Spotless của `test` chỉ có ý nghĩa thật trên 1 lần chạy `pull_request`**: cấu
hình `ratchetFrom: origin/master` của dự án chỉ kiểm tra các file khác biệt so với
`origin/master` — trên 1 lần `push` lên chính `master`, diff đó rỗng (nhánh đang được so sánh với
chính nó), nên bước này pass 1 cách hiển nhiên mà không kiểm tra gì cả. Nó chỉ thực sự làm việc
trên 1 PR, nơi nhánh PR thực sự khác `origin/master`. Đây là lý do branch protection (bên dưới)
yêu cầu PR cho mọi thay đổi — 1 lần push trực tiếp lên `master` sẽ bỏ qua Spotless hoàn toàn, chứ
không chỉ bỏ qua 1 lần kiểm tra lại thừa.

**`.gitleaksignore`** chứa fingerprint của cặp khoá JWT dev mà dự án đã cố tình commit công khai
(xem Bước 5) — thiếu nó, 1 lần chạy `workflow_dispatch` (trigger duy nhất quét toàn bộ lịch sử
thay vì chỉ các commit vừa push) sẽ fail vì 1 secret mà dự án đã quyết định giữ công khai. Tạo
lại fingerprint bằng `gitleaks detect --report-format json` nếu cặp khoá đó, hay bất kỳ credential
dev nào khác đã được chấp nhận, bị chuyển sang file hoặc dòng khác.

### Branch protection

GitHub Settings → Branches → thêm rule cho `master`:

- **Require a pull request before merging** — xem ghi chú về Spotless ở trên để biết vì sao điều
  này quan trọng, không chỉ là thông lệ tốt chung chung.
- **Require status checks to pass before merging** → thêm `changes`, `gitleaks`, `test` và
  `validate-manifests` (chúng chỉ xuất hiện sau khi đã chạy ít nhất 1 lần — merge PR thêm workflow
  này trước, hoặc chạy 1 lần `workflow_dispatch`, trước khi cấu hình mục này). Cần cả `changes`
  vì `test` và `validate-manifests` phụ thuộc vào nó: nếu nó fail, cả 2 bị bỏ qua, và GitHub tính
  1 job bị bỏ qua là thành công, đủ để thoả 1 required check. **Không** thêm
  `build-and-push` — nó không bao giờ chạy trên PR, nên 1 PR sẽ hiển thị nó là "Expected — Waiting
  for status to be reported" mãi mãi, không có cách nào thoả mãn được.
- **Do not allow bypassing the above settings** — nếu không có mục này, bất kỳ ai có quyền admin
  (kể cả chủ repo) vẫn có thể push thẳng lên `master`, đúng con đường mà mục đầu tiên tồn tại để
  chặn lại.

### Chạy lần đầu và xác minh nó hoạt động

Lần chạy đầu tiên chưa có gì để so sánh (chưa có image nào tồn tại), và kiểm tra idempotent của
`build-and-push` chỉ hữu ích khi registry đã có sẵn thứ gì đó — kích hoạt 1 lần thủ công ngay khi
file workflow và branch protection đã sẵn sàng cả hai:

```bash
# GitHub UI: Actions -> backend-ci -> Run workflow, branch = master
# or, with the GitHub CLI:
gh workflow run backend-ci.yml --ref master
```

Xác nhận cả 6 image đã lên registry:

```bash
gcloud artifacts docker images list \
  us-central1-docker.pkg.dev/cafe-microservices/cafe-images \
  --include-tags --project=cafe-microservices
```

Từ đây trở đi, 1 lần push bình thường lên `master` có đụng tới `backend/**` chỉ rebuild những
service có nội dung thực sự thay đổi (hoặc cả 6, nếu `common-lib`/`pom.xml` cha thay đổi) — xem
Bước 8 để deploy nó (`scripts/deploy.sh`).

## Tạm dừng và bật lại cluster giữa các buổi làm việc

Không có gì ở đây cần chạy suốt ngày đêm, nên giữa các buổi làm việc có thể đưa cả 2 node pool về 0
node. Khi đó vẫn còn tính phí: persistent disk của các PVC Postgres và Kafka, bucket GCS chứa
backup, dung lượng Artifact Registry và các version secret trên Secret Manager. Phí control plane
của cluster zonal vẫn được bù bằng credit miễn phí (xem dòng Zonal cluster trong phụ lục đối chiếu
GCP ↔ AWS).

Autoscaler của `stateless-pool` không tự về 0 được: các pod hệ thống của GKE và các operator ở
Bước 3 (cert-manager, operator CNPG cùng Barman Cloud Plugin, và Strimzi) luôn cần chỗ chạy, nên
pool dừng lại ở 2-3 node (xem phụ lục "các pod hệ thống GKE tự động thêm vào mỗi node"). Vì vậy khi
tạm dừng phải tắt autoscaling của pool này rồi resize bằng tay — chính GKE khuyến nghị không dùng
cluster autoscaler cùng lúc với resize bằng tay trên cùng 1 node pool.

### Tạm dừng

Tắt các service trước, để không còn client nào của Postgres hay Kafka; sau đó ngưng lịch backup
hằng đêm (`suspend`) và backup Postgres, để bucket chứa mọi thứ đã ghi trước khi tạm dừng và không
còn backup theo lịch nào đang chạy khi Postgres tắt; rồi cho Postgres hibernate, để CloudNativePG
tắt nó an toàn mà vẫn giữ PVC; rồi mới bỏ các node, `stateful-pool` trước `stateless-pool` — ngược
với thứ tự của phần Bật lại. Chạy các block này, và các block của lần tập dượt, trong cùng 1 shell:
block sau đọc biến (`$backup`, `$counts_dir`, `$counts_query`) do block trước đặt.

Tắt các service và chờ tới khi pod của chúng biến mất hẳn (`kubectl scale` trả về trước khi các pod
tắt xong):

```bash
kubectl scale deployment gateway auth-service menu-service order-service inventory-service report-service --replicas=0 -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
kubectl wait --for=delete pod -l 'app in (gateway,auth-service,menu-service,order-service,inventory-service,report-service)' -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=120s
```

Nếu muốn tập dượt restore trong lần tạm dừng này, hãy ghi lại số dòng ngay bây giờ (bước 1 của "Tập
dượt restore"), trước khi backup.

Ngưng lịch backup hằng đêm, rồi backup Postgres bằng 1 `Backup` chạy theo yêu cầu và chờ xong:

```bash
backup="cafe-postgres-on-demand-$(date -u +%Y%m%d-%H%M%S)"
kubectl patch scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --type=merge -p '{"spec":{"suspend":true}}' &&
  kubectl create -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -f - <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata:
  name: ${backup}
  labels: {app.kubernetes.io/managed-by: kubectl}
spec:
  cluster: {name: cafe-postgres}
  method: plugin
  pluginConfiguration: {name: barman-cloud.cloudnative-pg.io}
EOF
phase=""
for _ in $(seq 90); do
  phase=$(kubectl get backup.postgresql.cnpg.io "$backup" -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.status.phase}') || { phase="kubectl error"; break; }
  case "$phase" in ""|pending|started) sleep 10 ;; *) break ;; esac
done
echo "$backup: ${phase:-no phase yet}"
```

Vòng lặp chờ tối đa 15 phút rồi in ra phase cuối cùng. 1 backup kiểu này đi từ `pending` sang
`started` rồi tới `completed` hoặc `failed`; nó nằm ở `pending` khi 1 backup khác của cluster vẫn
đang chạy (ví dụ 1 backup hằng đêm đã bắt đầu trước khi lịch bị ngưng, hoặc backup bù mà 1 lần
bật lại có thể tạo ra), và 15 phút đó đã tính cả thời gian chờ này. CNPG chạy lần lượt từng backup
của 1 cluster, cái cũ trước, và việc ngưng lịch ngăn không cho backup mới được tạo, nên khi
backup này hoàn tất thì không còn backup nào đang chạy để bị lần hibernate cắt ngang. Chỉ
`completed` mới cho block tiếp theo chạy; với các giá trị khác mà vòng lặp in ra:

- `pending` hoặc `started`: backup vẫn đang chạy. Chạy lại vòng lặp từ dòng `phase=""` (vẫn dùng
  `$backup` cũ).
- `no phase yet`: operator CNPG chưa nhận backup. Kiểm tra các pod trong `cnpg-system` có đang chạy
  không, rồi chạy lại vòng lặp như trên.
- `kubectl error`: kubectl đã in lý do ở phía trên. Nếu `Backup` không tồn tại (lệnh patch lịch
  hoặc `kubectl create` bị lỗi), làm lại từ block backup; nếu không, chạy lại vòng lặp từ dòng
  `phase=""`.
- `failed` hoặc giá trị khác: lý do nằm ở
  `kubectl get backup.postgresql.cnpg.io "$backup" -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.status.error}'`.
  Cluster vẫn chạy, với lịch backup hằng đêm đang bị ngưng, nên hãy xử lý nguyên nhân rồi làm
  lại từ block backup — hoặc, nếu muốn bỏ lần tạm dừng này, xem "Xử lý sự cố khi bật lại", mục 5.

Sau đó cho Postgres hibernate:

```bash
[[ $(kubectl get backup.postgresql.cnpg.io "${backup:?run the backup block in this shell first}" -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.status.phase}') == completed ]] || { echo "the backup is not completed (see the backup block's phases)" >&2; false; } &&
  kubectl annotate cluster cafe-postgres cnpg.io/hibernation=on --overwrite -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster &&
  kubectl wait cluster/cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=condition=cnpg.io/hibernation --timeout=5m
```

Nếu block này dừng ở bước kiểm tra backup, quay lại block backup (xem ý nghĩa phase cuối cùng ở
trên). Nếu Postgres chưa hibernate xong trong 5 phút, lệnh sau cho biết nó đã tới đâu:

```bash
kubectl get cluster cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.status.conditions[?(@.type=="cnpg.io/hibernation")].reason}{"\n"}'
```

- `Hibernated`: việc hibernate đã xong trong lúc đó.
- `DeletingPods` hoặc `WaitingPodsDeletion`: Postgres vẫn đang tắt.
- `WaitingForHealthy`: CNPG chưa bắt đầu hibernate cho tới khi Cluster healthy
  (`kubectl get cluster cafe-postgres` cho thấy status của nó), rồi sẽ tự bắt đầu.
- Dòng trống: operator CNPG chưa xử lý annotation — kiểm tra các pod trong `cnpg-system` (operator
  và Barman Cloud Plugin) có đang chạy không.

Sau đó chạy lại block hibernate từ dòng `kubectl wait` của nó.

Nếu muốn tập dượt restore, hãy làm ngay lúc này — Postgres đã tắt, nhờ vậy `stateful-pool` có chỗ
cho cluster tạm — rồi quay lại chạy block cuối cùng (xem "Tập dượt restore").

Cuối cùng là bỏ các node. Các dòng trước lệnh resize kiểm tra lại rằng lịch backup hằng đêm đã
ngưng, Postgres đã hibernate và không còn sót cluster tạm nào:

```bash
[[ $(kubectl get scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.suspend}') == true ]] || { echo "the nightly schedule is not suspended (see the notes after this block)" >&2; false; } &&
  kubectl wait cluster/cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=condition=cnpg.io/hibernation --timeout=10s &&
  kubectl wait --for=delete cluster.postgresql.cnpg.io/cafe-postgres-restore -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=10s &&
  kubectl wait --for=delete pod -l cnpg.io/cluster=cafe-postgres-restore -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=2m &&
  gcloud container clusters resize cafe-cluster --node-pool=stateful-pool --num-nodes=0 --zone=us-central1-a --quiet &&
  gcloud container node-pools update stateless-pool --cluster=cafe-cluster --zone=us-central1-a --no-enable-autoscaling &&
  gcloud container clusters resize cafe-cluster --node-pool=stateless-pool --num-nodes=0 --zone=us-central1-a --quiet
gcloud compute instances list --filter="name~^gke-cafe-cluster-"
```

Lệnh cuối không được liệt kê instance nào. Nếu 1 bước kiểm tra fail thì chưa có gì bị resize và cả
2 pool vẫn chạy. Nếu 1 lệnh sau đó fail, xử lý nguyên nhân rồi chạy lại cả block: các bước kiểm tra
vẫn qua, và resize 1 pool vốn đã ở 0 node không thay đổi gì. Nếu lịch backup hằng đêm chưa ngưng,
hãy ngưng nó bằng
`kubectl patch scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --type=merge -p '{"spec":{"suspend":true}}'`.
Nếu Postgres chưa hibernate, hãy chạy xong block hibernate trước (xem phần ngay sau block đó). Nếu
cluster tạm `cafe-postgres-restore` vẫn còn, chạy bước 4 của "Tập dượt restore" rồi chạy lại block
này.

Đừng resize `stateful-pool` khi chưa hibernate: PodDisruptionBudget của instance Postgres chặn việc
drain node tới 1 giờ, sau đó Postgres bị tắt đột ngột, không qua bước tắt an toàn. Kafka không cần
xử lý gì riêng — pod broker duy nhất bị evict khi node của `stateful-pool` bị drain, Strimzi tạo lại
nó ngay, và pod mới nằm ở `Pending` cho tới khi `stateful-pool` có node trở lại.

`ScheduledBackup` hằng đêm (`cafe-postgres-daily-backup`, 00:00 UTC) bị ngưng từ block backup
cho tới khi phần Bật lại mở lại nó, sau khi Postgres đã sẵn sàng: hibernate sẽ cắt ngang 1 backup
hằng đêm còn đang chạy và đánh dấu nó là failed, và CNPG cũng không backup được 1 cluster đang
hibernate. Bản backup tạo trước khi hibernate đã bao quát thời gian tạm dừng. Nếu trong lúc tạm
dừng đã có 1 mốc 00:00 UTC trôi qua, CNPG sẽ tạo nhiều nhất 1 backup bù ngay khi lịch được mở lại,
đặt tên theo mốc 00:00 UTC đầu tiên bị lỡ, và backup này thành công vì Postgres đang chạy — nên mỗi
buổi làm việc thường bắt đầu bằng 1 base backup thứ 2, rất rẻ. Retention (`retentionPolicy: 10d`
trong `postgres-backup.yaml`) chạy 30 phút 1 lần khi Postgres đang chạy và không bao giờ xoá backup
hoàn tất mới nhất, dù tạm dừng lâu bao nhiêu. Object `Backup` của các backup hoàn tất của cluster
này bị xoá cùng dữ liệu của nó khi retention xoá backup đó; object của các backup bị fail thì còn
cho tới khi bạn tự xoá
(`kubectl get backup.postgresql.cnpg.io -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster`
liệt kê chúng).

### Bật lại

Làm theo thứ tự ngược lại: `stateless-pool` trước, để các operator và pod hệ thống của GKE có chỗ
chạy — operator CNPG phải chạy thì mới đánh thức được Postgres — sau đó `stateful-pool`, rồi
Postgres, rồi các service, inventory-service trước: job saga reconciliation của order-service gửi
lại ngay lệnh của các order đang dở saga lúc tạm dừng và bỏ cuộc sau vài phút (xem Yêu cầu môi
trường), nên inventory-service cần đang consume sẵn:

```bash
gcloud container clusters resize cafe-cluster --node-pool=stateless-pool --num-nodes=1 --zone=us-central1-a --quiet
gcloud container node-pools update stateless-pool --cluster=cafe-cluster --zone=us-central1-a --enable-autoscaling --min-nodes=0 --max-nodes=6
gcloud container clusters resize cafe-cluster --node-pool=stateful-pool --num-nodes=1 --zone=us-central1-a --quiet
for ns in cert-manager cnpg-system strimzi-system; do
  kubectl wait --for=condition=Available deployment --all -n "$ns" --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=300s
done
kubectl annotate cluster cafe-postgres cnpg.io/hibernation=off --overwrite -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
kubectl get cluster cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.metadata.annotations.cnpg\.io/hibernation}{"\n"}'
kubectl wait cluster/cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=jsonpath='{.status.readyInstances}'=1 --timeout=10m &&
  kubectl patch scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --type=merge -p '{"spec":{"suspend":false}}'
kubectl get scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.suspend}{"\n"}'
kubectl wait pod/cafe-kafka-cafe-kafka-pool-0 -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=condition=Ready --timeout=10m
kubectl scale deployment inventory-service --replicas=1 -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
kubectl wait --for=condition=Available deployment/inventory-service -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=22m &&
  kubectl scale deployment gateway auth-service menu-service order-service report-service --replicas=1 -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster &&
  kubectl wait --for=condition=Available deployment --all -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=22m
```

Lệnh kiểm tra annotation phải in ra `off`, và lệnh kiểm tra lịch backup phải in ra `false`; lịch
chỉ được mở lại khi Postgres đã sẵn sàng, nên backup bù mà nó có thể tạo ra sẽ thấy Postgres đang
chạy. 1 lệnh resize bị fail hay 1 lệnh chờ bị timeout đều không chặn các dòng sau nó — trừ việc các
service còn lại chỉ được scale lên và được chờ khi inventory-service đã sẵn sàng. Nếu 1 lệnh chờ
bị timeout, sửa nguyên nhân (xem "Xử lý sự cố khi bật lại"), rồi chạy lại block từ lệnh chờ đó;
mọi dòng từ lệnh chờ đầu tiên trở đi đều chạy lại được an toàn. Với 1 lệnh resize bị fail, xem
mục 3 ở đó. Lệnh chờ Kafka đọc pod broker chứ không đọc resource `Kafka`, vì condition `Ready` của
resource này có thể vẫn là `True` từ trước lúc tạm dừng. `--replicas=1` khớp với giá trị `replicas`
của chart, và timeout 22 phút (giống `scripts/deploy.sh`) dài hơn `progressDeadlineSeconds` 1200s
của các Deployment dùng DB, con số được tính để vừa với lần khởi động hợp lệ chậm nhất của chúng;
`kubectl wait` không dừng ở deadline đó, nên 1 service bị kẹt sẽ hiện ra dưới dạng 1 trong các lệnh
chờ này bị timeout.

`bash scripts/deploy.sh` (Bước 8) cũng đưa được các service lên lại, nhưng đồng thời deploy luôn
commit đang checkout, có thể khác với bản đã chạy trước đó, và script không tự đánh thức Postgres
hay mở lại lịch backup. Script khởi động cả 6 service cùng lúc, nên để inventory-service chạy trước
order-service, hãy bật lại bằng toàn bộ block ở trên trước rồi mới chạy script. Cho tới khi các
service chạy, `Cluster` CNPG có thể báo thiếu Secret mật khẩu của các role — xem mục 6 của "Xử lý
sự cố khi deploy".

### Xử lý sự cố khi bật lại

1. **`kubectl get cluster cafe-postgres` báo
   `Cluster cannot proceed to reconciliation due to an error while interacting with plugins`, và
   không có pod `cafe-postgres-1` nào** — trước tiên kiểm tra annotation hibernation đã thật sự là
   `off` chưa (lệnh kiểm tra ở phần Bật lại): khi Postgres còn đang hibernate, status có thể vẫn
   hiện 1 lỗi cũ, nên riêng thông báo này nói được rất ít.
2. **Bước chờ operator bị treo, và operator CNPG ghi log
   `name resolver error: produced zero addresses`** — 1 node Spot của `stateless-pool` đã bị thu
   hồi, kéo theo operator CNPG, Barman Cloud Plugin và cert-manager: `kubectl get nodes` hiện node
   đó `NotReady`, các pod trên đó hiện `Completed`, và `kubectl get endpointslices -n cnpg-system`
   không có địa chỉ nào. Nó tự hồi phục khi pod thay thế được xếp lên 1 node Ready; chạy lại lệnh
   chờ. (`kubectl get endpoints` đã deprecated và có thể cập nhật chậm; hãy đọc EndpointSlice.)
3. **1 lệnh resize fail với `ZONE_RESOURCE_POOL_EXHAUSTED`** — zone tạm hết máy `e2-medium`. Các
   dòng sau nó vẫn chạy, và các lệnh chờ bị timeout (Ctrl+C để dừng chúng). Đợi 1 lúc, rồi chạy lại
   block của phần Bật lại từ lệnh resize `stateful-pool`. Nếu lệnh bị fail là lệnh resize
   `stateless-pool`, đừng chạy lại nó: autoscaling do dòng kế tiếp bật sẽ thêm node cho các pod đang
   `Pending` khi zone có máy trở lại, còn resize bằng tay sẽ bỏ bớt các node mà autoscaler đã thêm
   ngoài node đầu tiên.
4. **Volume CSI của 1 pod không mount được trên node mới** — xem mục 1 của "Xử lý sự cố khi deploy".
5. **1 lần tạm dừng bị bỏ dở giữa chừng** (các service đã tắt; có thể lịch backup đã bị ngưng và
   Postgres đã hibernate; node vẫn còn) — đưa mọi thứ trở lại bằng các dòng của phần Bật lại, từ
   dòng annotation hibernation trở đi, gồm cả lệnh patch lịch backup; không có gì khác mở lại lịch
   backup. Nếu Postgres chưa hề hibernate, các dòng đó chỉ thay đổi lịch backup và các
   service. Nếu 1 lần tập dượt restore cũng bị bỏ dở (lệnh
   `kubectl get cluster.postgresql.cnpg.io cafe-postgres-restore -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster`
   vẫn thấy nó), hãy xoá nó trước bằng bước 4 của "Tập dượt restore": request 250m CPU của nó khiến
   `stateful-pool` không còn chỗ cho `cafe-postgres-1`, pod này sẽ nằm ở `Pending`.

---

## Khôi phục Postgres từ backup

Các backup trong `gs://cafe-microservices-cafe-pg-backups` (base backup cùng WAL liên tục, xem Bước
6 và "Tạm dừng") là đường được hỗ trợ để lấy lại Postgres. Việc restore luôn dựng 1 `Cluster` CNPG
*mới* từ bucket — dùng `bootstrap.recovery` thay cho `initdb` — và không bao giờ đụng vào các
backup mà nó đọc. Lượng dữ liệu có thể mất — thứ mà *recovery point objective* (RPO) đặt giới hạn
cho: khi Postgres đang chạy, tối đa là phần WAL chưa được archive, mà CNPG đóng lại và archive ít
nhất 5 phút 1 lần (`archive_timeout`); qua 1 lần tạm dừng thì không mất gì, vì lần tạm dừng bắt
đầu bằng 1 backup đã hoàn tất. Restore mất bao lâu — thứ mà *recovery time objective* (RTO) đặt
giới hạn cho — thì do lần tập dượt dưới đây đo.

### Tập dượt restore

1 bước kiểm tra làm tay, thỉnh thoảng mới làm, để chắc rằng backup thật sự restore được, làm trong
lúc tạm dừng: khi Postgres đã hibernate, `stateful-pool` có chỗ cho cluster tạm, vốn cần cùng mức
request 250m CPU như cluster thật. Làm lại sau mỗi lần đổi bản Postgres lớn (major version), đổi
Barman Cloud Plugin, đổi bucket hoặc IAM của nó. Lần tập dượt sẽ restore vào 1 `Cluster` riêng tên
`cafe-postgres-restore` (cluster này đọc bucket bằng GSA chỉ đọc `cafe-postgres-restore` ở Bước 2
và không archive gì), rồi xoá cluster đó.

1. Trong lúc làm "Tạm dừng", sau khi pod của các service đã biến mất và trước khi backup, ghi lại
   số dòng chính xác của mọi bảng:

   ```bash
   counts_dir="$(mktemp -d)"
   counts_query="select table_schema||'.'||table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) c from %I.%I', table_schema, table_name), false, true, '')))[1]::text from information_schema.tables where table_type = 'BASE TABLE' and table_schema not in ('pg_catalog', 'information_schema') order by 1"
   for db in auth_db menu_db order_db inventory_db report_db; do
     echo "== $db"
     kubectl exec cafe-postgres-1 -c postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -- psql -d "$db" -Atc "$counts_query"
   done > "$counts_dir/before.txt"
   ```

2. Tiếp tục "Tạm dừng" cho tới hết block hibernate, rồi tạo cluster tạm. Nó lấy image, resource và
   dung lượng đĩa từ `cafe-postgres` đang tồn tại và chạy ở cùng vị trí, nhưng dùng StorageClass
   `standard` của GKE (reclaim `Delete`, nên xoá cluster xong không còn đĩa sót lại), không có khối
   `plugins` (nên không archive WAL cũng không áp retention lên bucket), và restore `auth_db` do
   `auth_service` sở hữu làm database bootstrap (nếu không, CNPG sẽ tạo thêm 1 database và role
   `app`). `serverName` cho biết backup nào cần đọc — thiếu nó, plugin sẽ tìm theo tên của chính
   cluster mới; block này đọc giá trị đó từ mục plugin của `cafe-postgres` đang tồn tại (nếu không
   đặt thì là tên Cluster, giá trị mặc định của plugin):

   ```bash
   restore_start=$(date +%s)
   server_name=$(kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.plugins[?(@.name=="barman-cloud.cloudnative-pg.io")].parameters.serverName}') &&
     image=$(kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.imageName}') &&
     resources=$(kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.resources}') &&
     storage_size=$(kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.storage.size}') &&
     kubectl apply -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -f - <<EOF &&
   apiVersion: postgresql.cnpg.io/v1
   kind: Cluster
   metadata:
     name: cafe-postgres-restore
     labels: {app.kubernetes.io/managed-by: kubectl}
   spec:
     instances: 1
     imageName: ${image:?cafe-postgres has no spec.imageName}
     resources: ${resources:?cafe-postgres has no spec.resources}
     enableSuperuserAccess: false
     storage: {size: ${storage_size:?cafe-postgres has no spec.storage.size}, storageClass: standard}
     affinity:
       tolerations: [{key: "workload", operator: "Equal", value: "stateful", effect: "NoSchedule"}]
       nodeSelector: {cloud.google.com/gke-nodepool: stateful-pool}
     serviceAccountTemplate:
       metadata:
         annotations: {iam.gke.io/gcp-service-account: "cafe-postgres-restore@cafe-microservices.iam.gserviceaccount.com"}
     bootstrap:
       recovery: {source: origin, database: auth_db, owner: auth_service}
     externalClusters:
       - name: origin
         plugin:
           name: barman-cloud.cloudnative-pg.io
           parameters: {barmanObjectName: cafe-postgres-backup-store, serverName: ${server_name:-cafe-postgres}}
   EOF
     kubectl wait cluster.postgresql.cnpg.io/cafe-postgres-restore -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=condition=Ready --timeout=15m &&
     echo "restore took $(( $(date +%s) - restore_start ))s"
   ```

   CNPG chạy trước 1 Job `cafe-postgres-restore-1-full-recovery`, rồi khởi động instance; instance
   này tự restart 1 lần (sidecar của plugin chỉ cần trong lúc khôi phục); lệnh chờ đã tính cả lần
   restart đó. Thời gian nó in ra chính là thời gian restore đo được. Nếu bước 3 vẫn không vào được
   pod, chỉ chạy lại dòng `kubectl wait`, bỏ `&&` ở cuối, rồi bước 3 — không chạy lại lệnh `echo`,
   vì nó sẽ cộng thêm cả khoảng thời gian từ đó tới giờ. Nếu lệnh chờ bị timeout, chạy lại ngay,
   cùng lúc, 2 dòng cuối của block: thời gian chúng in ra vẫn tính từ lúc bắt đầu block, nên chậm
   trễ trước khi chạy lại sẽ bị cộng vào.

3. Đếm số dòng của các bảng đã restore theo cùng cách rồi so sánh:

   ```bash
   [[ -f ${counts_dir:?run step 1 in this shell first}/before.txt && -n ${counts_query:?run step 1 in this shell first} ]] &&
     for db in auth_db menu_db order_db inventory_db report_db; do
       echo "== $db"
       kubectl exec cafe-postgres-restore-1 -c postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -- psql -d "$db" -Atc "$counts_query"
     done > "$counts_dir/restored.txt" &&
     diff "$counts_dir/before.txt" "$counts_dir/restored.txt" && echo "restore matches"
   ```

4. Dù bước 2–3 ra sao, hãy xoá cluster tạm và chờ tới khi nó và pod của nó biến mất hẳn — nếu
   không, PodDisruptionBudget của nó sẽ chặn việc drain `stateful-pool` — rồi hoàn tất "Tạm dừng"
   bằng block cuối cùng:

   ```bash
   kubectl delete cluster.postgresql.cnpg.io cafe-postgres-restore --ignore-not-found -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
   kubectl wait --for=delete pod -l cnpg.io/cluster=cafe-postgres-restore -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=5m
   ```

### Khôi phục sau khi mất cluster Postgres

Khi `Cluster` `cafe-postgres` cùng dữ liệu của nó đã mất, hãy tạo lại nó dưới đúng tên đó, để
Service `-rw`, binding Workload Identity của KSA, các CR `Database` và `ScheduledBackup` vẫn hoạt
động. Chỉ làm việc này khi thật sự cần khôi phục.

Trước tiên tắt các service bằng block đầu tiên của "Tạm dừng", để không service nào ghi vào
Cluster mới trước khi bước 3 backup nó; nếu chúng đã tắt sẵn thì block này không thay đổi gì. Nếu
cluster đang tạm dừng, tiếp đó chạy các dòng của phần Bật lại tới hết các lệnh chờ operator (vòng
lặp `for ns …`) — không chạy các dòng hibernation, vì Cluster mới không hibernate — và xoá
`cafe-postgres-restore` nếu còn sót (xem "Xử lý sự cố khi bật lại", mục 5). Sau bước 4, chạy nốt 5
dòng cuối của phần Bật lại (lệnh chờ Kafka, lệnh scale và lệnh chờ inventory-service, rồi lệnh
scale và lệnh chờ các service còn lại).

1. Kiểm tra không còn gì của cluster cũ:

   ```bash
   kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
   kubectl get pvc -l cnpg.io/cluster=cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
   ```

   Lệnh đầu phải báo lỗi `NotFound`. Nếu nó tìm thấy Cluster, đĩa của Cluster có thể vẫn còn dữ
   liệu: instance bị crash-loop hay đĩa đầy là lỗi cần sửa, không phải mất dữ liệu, nên dừng lại.
   Chỉ khi đã kết luận dữ liệu bị mất (PVC không còn, hoặc đĩa không đọc được) mới xoá Cluster —
   PVC bị xoá theo vì Cluster sở hữu nó, còn policy `Retain` của PV vẫn giữ đĩa (xem bước 5) — rồi
   chạy lại các lệnh kiểm tra ở trên:

   ```bash
   kubectl delete cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
   kubectl wait --for=delete pvc -l cnpg.io/cluster=cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --timeout=5m
   ```

   Lệnh kiểm tra thứ 2 phải in `No resources found in cafe namespace.` 1 PVC còn sót nghĩa là
   đĩa cũ vẫn còn và có thể chứa dữ liệu mới hơn bucket; việc dựng lại từ đĩa đó không được hướng
   dẫn ở đây. Cluster mới sẽ nhận lại PVC đó và chạy trên đĩa cũ thay vì restore, nên muốn restore
   từ bucket thì xoá nó trước (policy `Retain` của PV vẫn giữ đĩa, xem bước 5):
   `kubectl delete pvc <name> -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster`.

2. Trong `k8s/data-layer/postgres-cluster.yaml`, thay `initdb: {database: auth_db, owner:
   auth_service}` bằng `recovery: {source: origin, database: auth_db, owner: auth_service}` và thêm
   đúng mục `externalClusters` như ở phần tập dượt, với `serverName` là `serverName` của plugin mà
   file đang có (`cafe-postgres` nếu chưa có). Rồi đặt `serverName` của plugin trong `parameters`
   thành 1 tên mới chưa có prefix nào trong bucket — tên `cafe-postgres-rN` đầu tiên
   (`cafe-postgres-r1`, `-r2`, …) mà `gcloud storage ls gs://cafe-microservices-cafe-pg-backups/`
   không liệt kê. Archive WAL sẽ fail nếu prefix đã có WAL (prefix nào có backup cũng có WAL).
   Apply, rồi chờ instance của nó và lần archive WAL đầu tiên:

   ```bash
   kubectl apply -f k8s/data-layer/postgres-cluster.yaml --context gke_cafe-microservices_us-central1-a_cafe-cluster
   kubectl wait cluster.postgresql.cnpg.io/cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=jsonpath='{.status.readyInstances}'=1 --timeout=15m &&
     kubectl wait cluster.postgresql.cnpg.io/cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --for=condition=ContinuousArchiving --timeout=10m
   ```

   Khác với lần tập dượt, instance này không restart sau khi khôi phục xong: plugin archive vẫn giữ
   sidecar của nó. Nếu lệnh chờ archive bị timeout, lỗi archive nằm ở
   `kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.status.conditions[?(@.type=="ContinuousArchiving")].message}{"\n"}'`.
3. Tạo ngay 1 base backup đầu tiên dưới tên mới, thay vì chờ lịch backup hằng đêm: chạy block
   backup của "Tạm dừng" (ý nghĩa phase cuối cùng của nó được giải thích ở đó), và chỉ đi tiếp khi
   in `completed`. 1 backup hằng đêm được tạo trong lúc Cluster đã mất có thể chạy trước; vòng lặp
   sẽ chờ nó như chờ 1 backup bù. Sau đó mở lại lịch backup, vốn bị block backup ngưng và không
   bao giờ được reset khi apply `k8s/data-layer/`, nên luôn chạy lệnh này:

   ```bash
   kubectl patch scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster --type=merge -p '{"spec":{"suspend":false}}'
   kubectl get scheduledbackup.postgresql.cnpg.io cafe-postgres-daily-backup -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.spec.suspend}{"\n"}'
   ```

   Lệnh kiểm tra phải in ra `false`.
4. Trong `postgres-cluster.yaml`, đặt lại `initdb: {database: auth_db, owner: auth_service}` và bỏ
   mục `externalClusters`; giữ `serverName` mới trong `parameters` của plugin. `bootstrap` chỉ được
   đọc khi tạo 1 Cluster, nên apply thay đổi này không ảnh hưởng gì tới cluster đang chạy, và file
   vẫn dùng được để dựng 1 môi trường mới. Apply rồi commit thay đổi này trên 1 branch và merge
   qua 1 PR, để lần `kubectl apply -f k8s/data-layer/` sau từ `master` không đặt tên archive về
   lại tên cũ:

   ```bash
   kubectl apply -f k8s/data-layer/postgres-cluster.yaml --context gke_cafe-microservices_us-central1-a_cafe-cluster
   ```

5. Từ đó trở đi các backup nằm dưới tên mới: lần tập dượt tự đọc tên này, còn lần khôi phục sau
   đọc nó trong `externalClusters` và archive dưới tên tiếp theo chưa dùng. Không có gì tự dọn
   prefix cũ trong bucket. Khi đã tin tưởng cluster vừa khôi phục và đã qua 10 ngày (khoảng
   retention) kể từ lúc khôi phục, hãy tự xoá nó bằng
   `gcloud storage rm --recursive gs://cafe-microservices-cafe-pg-backups/<old-serverName>/`
   (`<old-serverName>` là `serverName` mà bước 2 đặt vào `externalClusters`), cùng với các object
   `Backup` của cluster cũ — retention chỉ quản lý các object của Cluster hiện tại, nên chúng vẫn
   hiện `completed` sau khi dữ liệu đã mất. Xoá 1 object `Backup` không bao giờ đụng tới bucket.
   Liệt kê chúng (các backup hoàn tất do 1 Cluster UID khác tạo), xem lại danh sách, rồi xoá:

   ```bash
   cluster_uid=$(kubectl get cluster.postgresql.cnpg.io cafe-postgres -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{.metadata.uid}') &&
     old_backups=$(kubectl get backup.postgresql.cnpg.io -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster -o jsonpath='{range .items[?(@.spec.cluster.name=="cafe-postgres")]}{.metadata.name}{" "}{.status.pluginMetadata.clusterUID}{"\n"}{end}' | awk -v uid="$cluster_uid" '$2 != "" && $2 != uid {print $1}') &&
     printf '%s\n' "$old_backups"
   ```

   ```bash
   [[ -n ${old_backups?run the block above in this shell first} ]] &&
     kubectl delete backup.postgresql.cnpg.io $old_backups -n cafe --context gke_cafe-microservices_us-central1-a_cafe-cluster
   ```

   PersistentVolume cũ (reclaim policy `Retain`) nằm ở trạng thái `Released` và đĩa của nó vẫn tính
   phí cho tới khi bạn xoá cả 2. Lệnh sau liệt kê các PV cùng claim, StorageClass và đĩa của chúng;
   PV cũ là PV ở trạng thái `Released`, có class `cafe-postgres-retain`:

   ```bash
   kubectl get pv --context gke_cafe-microservices_us-central1-a_cafe-cluster -o custom-columns=NAME:.metadata.name,STATUS:.status.phase,CLAIM:.spec.claimRef.name,CLASS:.spec.storageClassName,DISK:.spec.csi.volumeHandle
   ```

   Xoá PV đó bằng
   `kubectl delete pv <name> --context gke_cafe-microservices_us-central1-a_cafe-cluster`, rồi xoá
   đĩa của nó, có tên là đoạn cuối của đường dẫn `DISK`, bằng
   `gcloud compute disks delete <disk> --zone=us-central1-a` (lệnh này hỏi bạn xác nhận).

Các managed role và CR `Database` reconcile với các role và database đã restore như bình thường;
các Secret mật khẩu của service được lấy lại từ Secret Manager khi các service chạy.

Kafka không được khôi phục cùng Postgres: cả 5 database quay về cùng 1 thời điểm, nhưng Kafka vẫn
giữ các message và offset đã commit của các consumer từ sau thời điểm đó:

- Các dòng outbox chưa gửi tại thời điểm đó sẽ được gửi, có dòng là gửi lần 2, và không bản trùng
  nào được áp dụng 2 lần (inbox của inventory-service kiểm tra correlation ID, saga của
  order-service kiểm tra step hiện tại và correlation ID).
- 1 message đã được consume sau thời điểm đó sẽ không được gửi lại, dù tác dụng của nó đã mất.
  Điều này gồm cả những gì các service vẫn chạy trong lúc mất Postgres đã lấy từ Kafka: mỗi
  message được thử lại tối đa vài phút, rồi bị chuyển vào `<topic>.dlq`, nơi không có gì replay
  lại (xem mục 9 của "Xử lý sự cố khi deploy"). Trong lúc đó không có gì mới vào Kafka, vì mọi
  message saga đều được gửi từ 1 database, nên phát hiện việc mất muộn cũng không làm việc này
  tệ hơn.
- Order đang chờ reply cho lệnh reserve hoặc commit sẽ được job saga reconciliation của
  order-service gửi lại lệnh, tối đa 3 lần cách nhau khoảng 1 phút, bắt đầu ngay khi
  order-service chạy; inventory-service trả lời 1 lệnh nó đã áp dụng bằng reply nó đã lưu. 5 dòng
  cuối của phần Bật lại khởi động inventory-service trước để các lần gửi lại này được trả lời;
  nếu cả 3 lần đều không được trả lời, order quay về OPEN hoặc CONFIRMED.
- Stock cần sửa tay: phần inventory-service giữ hoặc trừ cho 1 order như vậy khi nó consume các
  lệnh đó, và stock bị giữ mà không thuộc order nào — do 1 lệnh release-stock bị mất, hoặc 1 lệnh
  cho 1 order đã mất mà inventory-service chưa consume.

Để khôi phục vào 1 môi trường mới từ 1 bản chép của bucket (xem Yêu cầu môi trường), dựng môi
trường đó theo tài liệu này, đặt bucket chứa các backup vào `destinationPath` của
`postgres-backup.yaml` (xem danh sách các tên được ghi cứng vào file của repo trong Yêu cầu môi
trường). Bản chép nằm trong bucket của 1 project trả phí khác thì dùng được ngay, bỏ qua lệnh
`buckets create` ở Bước 2; bản chép trên máy thì đưa vào bucket mới bằng
`gcloud storage rsync -r <dir> gs://<new-bucket>`, sau đó `gcloud storage ls gs://<new-bucket>/`
phải liệt kê các prefix `<serverName>/` ngay ở gốc bucket. Trước lệnh
`kubectl apply -f k8s/data-layer/` của Bước 6, sửa `postgres-cluster.yaml` như ở bước 2, để Cluster
mới restore từ bucket thay vì chạy `initdb`. Sau lệnh apply đó, chạy các lệnh chờ của bước 2, bước
3 và bước 4, rồi làm tiếp từ Bước 7; phần mở đầu của mục này không áp dụng, vì chưa có service nào
chạy và Bước 8 sẽ khởi động chúng. Kafka ở đó bắt đầu rỗng, nên mọi message đã gửi nhưng chưa được
consume tại thời điểm Postgres quay về đều mất, dù Kafka cũ sau đó có giao nó hay không; ngoài ra
các gạch đầu dòng ở trên vẫn đúng. `deploy.sh` của Bước 8 khởi động cả 6 service cùng lúc, nên nếu
inventory-service lên sau order-service vài phút, job saga reconciliation của order-service có thể
bỏ cuộc với các order đang dở saga lúc đó trước khi inventory-service trả lời; khi đó hãy kiểm tra
stock cần sửa tay như gạch đầu dòng cuối ở trên mô tả.

---

## Phụ lục: các pod hệ thống GKE tự động thêm vào mỗi node

Mỗi node trong cluster này — đã bật Workload Identity (Bước 1) và dùng datapath mặc định của GKE
(không phải Dataplane V2) — đều tự động chạy 1 tập pod hệ thống bắt buộc ngay khi vừa gia nhập
cluster. `e2-medium` (2 vCPU / danh nghĩa 2000m) chỉ có 940m allocatable bất kể workload gì, do
GKE giữ cố định 1060 mCPU trên các máy shared-core E2 (`e2-micro`/`e2-small`/`e2-medium`), chứ
không theo công thức phần trăm trên mỗi core thông thường của nó; các pod hệ thống bên dưới
sau đó tiêu tốn thêm 1 phần từ ngân sách 940m đã bị giảm đó, trước khi bất kỳ pod ứng dụng,
operator, hay CNPG/Strimzi nào được lên lịch. Sự kết hợp này là lý do vì sao `stateless-pool`
của dự án này thực tế ổn định ở 2-3 node `e2-medium` ngay cả khi mọi Deployment ứng dụng đã scale
về 0, thay vì co về mức tối thiểu 0 đã cấu hình: các pod này luôn cần có chỗ để chạy.

**DaemonSet trên mỗi node** (1 pod trên mỗi node, `kube-system` trừ khi ghi chú khác):

| Pod | CPU request ước tính (`e2-medium`) | Vai trò |
|---|---|---|
| `kube-proxy` | 100m | Triển khai networking cho Service (rule iptables/IPVS). |
| `netd` | 8m | Agent networking Pod trên mỗi node của GKE (sinh CNI spec từ PodCIDR của node và quản lý việc chuyển hướng gói tin). |
| `node-local-dns` | 30m | Cache DNS trên mỗi node, giảm tải cho `kube-dns`. |
| `konnectivity-agent` | 15m | Tạo tunnel traffic từ API server tới node (thay thế SSH tunnel cũ). |
| `gke-metadata-server` | 100m | Phục vụ metadata Workload Identity cho pod trên node đó. |
| `gke-metrics-agent` | 21m | Gửi metric node/pod tới Cloud Monitoring. |
| `fluentbit-gke` | 105m | Gửi log container tới Cloud Logging. |
| `pdcsi-node` | 15m | Thành phần mount PVC trên mỗi node của Persistent Disk CSI driver. |
| `collector` (`gmp-system`) | 5m | Bộ thu thập metric trên mỗi node của Google Managed Prometheus (2 container `prometheus`+`config-reloader`); mặc định bật sẵn trên cluster GKE Standard mới, giữ bật ở đây. |

**Singleton toàn cluster** (1-2 replica tổng cộng, rơi vào node nào còn chỗ; `kube-system` trừ
khi ghi chú khác):

| Pod | CPU request ước tính (`e2-medium`) | Vai trò |
|---|---|---|
| `kube-dns` | 270m | Phân giải DNS cho cả cluster — pod hệ thống tốn CPU nhất đo được trên node của dự án này. |
| `kube-dns-autoscaler` | 20m | Tự động scale số replica của `kube-dns` theo kích thước cluster. |
| `konnectivity-agent-autoscaler` | 10m | Tự động scale số replica của `konnectivity-agent` theo kích thước cluster. |
| `event-exporter-gke` | 3m | Gửi Kubernetes event tới Cloud Logging. |
| `l7-default-backend` | 10m | Backend mặc định cho Ingress load balancer do GKE tạo. |
| `metrics-server` | 44m | Phục vụ Kubernetes Metrics API (`kubectl top`, Horizontal Pod Autoscaler). |
| `gmp-operator` (`gmp-system`) | 1m | Quản lý DaemonSet `collector` và các CRD của Google Managed Prometheus. |

---

## Phụ lục: đối chiếu thuật ngữ GCP ↔ AWS

Dành cho ai quen AWS hơn GCP — các khái niệm trong tài liệu này, đối chiếu với khái niệm gần
nhất bên AWS. Đây là tương đồng gần nhất, không phải khớp 1:1 tuyệt đối — xem cột Ghi chú để
biết chỗ nào việc đối chiếu không còn chính xác.

| GCP (dùng trong tài liệu này) | Tương đương bên AWS | Ghi chú |
|---|---|---|
| GKE (Google Kubernetes Engine) | EKS (Elastic Kubernetes Service) | Control plane Kubernetes được quản lý. |
| GKE Standard mode | EKS with self-managed/managed node groups | Tương đồng gần hơn của GKE Autopilot bên AWS là EKS + Fargate profile, không dùng trong tài liệu này. |
| Zone (`us-central1-a`) / region (`us-central1`) | Availability Zone / Region | 1 zone là 1 vùng lỗi bên trong 1 region, tương tự AZ của AWS. Tuy nhiên cách đặt tên thì khác: AWS gán AZ vật lý vào tên 1 cách ngẫu nhiên theo từng account, nên `us-east-1a` có thể là AZ vật lý khác ở account khác (AZ ID như `use1-az1` mới là định danh ổn định); còn GCP không có tài liệu nào nói tên zone bị đổi ánh xạ theo từng project. `--zone=` ghim cluster và node pool ở đây; `--location=` chọn region cho GCS bucket. |
| Zonal cluster | — (EKS has no zonal/regional tier) | Control plane của EKS luôn multi-AZ trong 1 region, và tính phí ~$0.10/giờ (~2.625₫/giờ) cho phiên bản Kubernetes trong thời gian hỗ trợ tiêu chuẩn, không có ưu đãi miễn phí nào — khác với GKE, vốn miễn phí phí này cho 1 cluster zonal mỗi billing account (1 yếu tố thật sự ảnh hưởng tới thiết kế chi phí, xem "Kiến trúc tổng quan"). |
| Node pool | Managed node group | 1 tập hợp worker node dùng chung 1 cấu hình (loại máy, đĩa, taint). |
| Node autoscaling (`--enable-autoscaling`, min 0) | Cluster Autoscaler / Karpenter | Thêm hoặc bớt node theo số pod đang chờ. Autoscaler của GKE có sẵn và cấu hình theo từng node pool; trên EKS bạn thường phải tự cài Cluster Autoscaler hoặc Karpenter. `--no-enable-autoscaling` tắt nó cho từng pool (cần làm trước khi resize pool đó bằng tay). |
| Node pool resize (`gcloud container clusters resize --node-pool --num-nodes`) | Managed node group desired size (`eksctl scale nodegroup` / `aws eks update-nodegroup-config --scaling-config`) | Đặt số node của 1 node pool bằng tay, có thể về 0 để tạm dừng giữa các buổi làm việc (xem "Tạm dừng và bật lại cluster giữa các buổi làm việc"). GKE khuyến nghị không dùng nó cùng lúc với cluster autoscaler trên cùng 1 pool, nên phải tắt autoscaling trước. Managed node group của EKS cũng scale được về 0; ở đó Cluster Autoscaler đang chạy cũng sẽ giành lại số node đặt bằng tay. |
| Compute Engine (GKE nodes are Compute Engine VMs) | Amazon EC2 | Dịch vụ VM của GCP. Mọi node GKE Standard đều là 1 VM Compute Engine, nên các dòng cấp node bên dưới (machine type, Spot VM, service account mặc định, metadata server — xem dòng `--workload-metadata` — và access scopes) là khái niệm của Compute Engine, cũng như các khái niệm tương ứng bên EKS thuộc về EC2. API của nó (`compute.googleapis.com`) được bật cùng với API của GKE (xem Yêu cầu môi trường). |
| Compute Engine machine type (`e2-medium`) | AWS EC2 instance type (e.g. `t3.medium`) | Cách đặt tên/phân loại kích thước khác nhau giữa 2 cloud; `t3.medium` khớp khá sát hình dạng của `e2-medium` — cả 2 đều 2 vCPU/4GB, đều thuộc nhóm burstable/tối ưu chi phí. |
| GKE node allocatable reservation (1060 mCPU on shared-core E2) | EKS `kube-reserved` (node bootstrap defaults) | Cả 2 đều cắt 1 phần cố định của mỗi node cho thành phần hệ thống. GKE công bố 1 công thức CPU theo bậc dùng chung cho mọi loại máy (6% core đầu tiên, 1% core kế tiếp, 0,5% cho 2 core kế, 0,25% cho phần vượt quá 4 core) và ghi đè bằng mức cố định 1060 mCPU trên các máy E2 shared-core; AMI tối ưu của EKS áp dụng đúng công thức CPU theo bậc đó lúc bootstrap node, không có ngoại lệ nào cho máy shared-core. Chỉ riêng CPU là khớp — phần memory thì mỗi bên tính theo cách khác nhau. Xem phụ lục "các pod hệ thống GKE tự động thêm vào mỗi node". |
| Spot VM | EC2 Spot Instance | Cùng cơ chế: dùng capacity dư thừa với giá rẻ hơn, có thể bị thu hồi với báo trước ngắn. |
| Zone resource stock-out (`ZONE_RESOURCE_POOL_EXHAUSTED`) | EC2 insufficient capacity (`InsufficientInstanceCapacity`) | Zone tạm thời không còn capacity dư cho machine type được yêu cầu, nên việc tạo VM — ở đây là 1 lệnh resize node pool hoặc 1 lần autoscaler thêm node — fail với `ZONE_RESOURCE_POOL_EXHAUSTED` (hoặc `…_WITH_DETAILS`). Đây không phải lỗi quota (lỗi quota là `QUOTA_EXCEEDED`), nên cách xử lý là đợi rồi thử lại. EC2 trả về `InsufficientInstanceCapacity` trong cùng tình huống. |
| Persistent Disk (`pd-standard`/`pd-balanced`/`pd-ssd`) | EBS (`gp2`/`gp3`/`io1`/`io2`/`st1`/`sc1`) | Các tier lưu trữ block gắn qua mạng; `pd-standard` ≈ `st1`/`sc1` (HDD), `pd-balanced` ≈ `gp3`, `pd-ssd` nằm khoảng giữa `gp3` và `io1`/`io2` (không có tương đương chính xác); `pd-extreme` (không dùng ở đây) là tương đương gần nhất của `io1`/`io2` provisioned-IOPS. Đĩa của 1 PV có policy `Retain` tồn tại lâu hơn PV và phải tự xoá bằng `gcloud compute disks delete`, giống 1 EBS volume với `aws ec2 delete-volume`. |
| PD CSI driver (`pdcsi-node`) + built-in StorageClasses (`standard-rwo` default, `standard`) | EBS CSI driver (EKS add-on) + StorageClasses (legacy in-tree `gp2`, not default on new clusters since EKS 1.30; a `gp3` class — the EBS CSI add-on's optional default, or one you create) | Cấp PersistentVolume từ block storage (dòng Persistent Disk đã nói về các tier đĩa). `standard-rwo` (`pd-balanced`) dùng CSI trực tiếp và bind theo `WaitForFirstConsumer`; `standard` (`pd-standard`) tương ứng với class `gp2` cũ của EKS xét theo provisioner, không phải loại đĩa: cả 2 vẫn mang tên provisioner in-tree (`kubernetes.io/gce-pd`, `kubernetes.io/aws-ebs`), được CSI migration chuyển sang CSI driver. Khác với `gp2` (bind theo `WaitForFirstConsumer`), `standard` bind ngay (`Immediate`). Volume của Kafka và lần tập dượt restore dùng `standard`; mọi class có sẵn đều reclaim `Delete`. GKE cài sẵn driver; trên EKS đây là add-on cần cấu hình IAM riêng. |
| Workload Identity Federation | IAM Roles for Service Accounts (IRSA) / EKS Pod Identity | Cả 2 đều cho phép 1 pod nhận danh tính IAM của cloud mà không cần static key. IRSA nối qua 1 OIDC provider đăng ký với cluster; EKS Pod Identity (mới hơn) đơn giản hoá cùng ý tưởng đó. Workload pool (`<project>.svc.id.goog`, dùng trong member `serviceAccount:<pool>[ns/ksa]`) là điểm neo tin cậy, giống IAM OIDC provider của IRSA; Pod Identity không có khái niệm tương ứng. GCP tự tạo pool, 1 lần cho mỗi project. |
| Workload Identity Federation **for external identities** (GitHub Actions OIDC, Step 9) | IAM OIDC identity provider + `AssumeRoleWithWebIdentity` | Cùng cơ chế nền tảng với dòng phía trên, nhưng bên gọi là 1 lần chạy GitHub Actions xác thực qua token OIDC của chính nó, không phải 1 pod Kubernetes — không có thành phần nào theo pod/node, chỉ cần 1 workload identity pool + provider + 1 IAM binding. IAM OIDC identity provider của AWS đóng vai trò điểm neo tin cậy giống pool ở đây. |
| Security Token Service (`sts.googleapis.com`) + IAM Service Account Credentials API (`iamcredentials.googleapis.com`) | AWS STS (`sts:AssumeRoleWithWebIdentity`) | Các API thực sự thực hiện việc đổi token OIDC lấy access token đứng sau cả 2 dòng Workload Identity Federation ở trên — chỉ cần bật 1 lần cho mỗi project (xem Yêu cầu môi trường). |
| `docker login` with username `oauth2accesstoken` and a Workload-Identity-issued access token as the password (Step 9) | `aws ecr get-login-password` | Cả 2 đều biến 1 credential cloud có thời hạn ngắn thành thứ Docker CLI cần để push; GCP tái dùng cơ chế login username/password chung của Docker thay vì 1 lệnh helper riêng. |
| `gke-metadata-server` | EKS Pod Identity Agent | Pod chạy trên mỗi node, cấp credential Workload Identity cho các pod. Chỉ là tương đương gần nhất: IRSA không cần pod theo node như vậy. |
| `--workload-metadata=GKE_METADATA` (node pool) | — (no equivalent) | Công tắc theo từng node pool, thay metadata server Compute Engine thô bằng metadata server của Workload Identity; không có nó, pod trên pool đó rơi về dùng service account của chính node. Bật nó trên pool đã tồn tại có hiệu lực ngay với các workload đang chạy ở đó, khiến chúng không còn dùng được service account của node và có thể gây gián đoạn. EKS không cần công tắc cấp node như vậy — IRSA/Pod Identity hoạt động theo từng pod. |
| Google Service Account (GSA) | IAM Role | Danh tính phía cloud mà 1 KSA được gắn vào. |
| Compute Engine default service account (`<project-number>-compute@developer.gserviceaccount.com`) | EKS node IAM role (attached to the node group's EC2 instances via an instance profile) | Danh tính mà các node GKE dùng khi node pool được tạo không có `--service-account`, như cả 2 pool ở đây (`node-pools describe` chỉ in ra `default`); Bước 9 cấp cho nó `roles/artifactregistry.reader` để node pull được image, giống như node role của EKS được gắn `AmazonEC2ContainerRegistryPullOnly`. GCP tự tạo nó cùng Compute Engine API và cấp cho nó role Editor rộng trên toàn project, trừ khi organization policy `iam.automaticIamGrantsForDefaultServiceAccounts` được enforce (mặc định với các organization tạo từ ngày 3/5/2024 trở đi); AWS không tạo sẵn role mặc định nào, nên managed node group của EKS cần 1 node role do bạn (hoặc `eksctl`) tạo. Google khuyến nghị dùng 1 node service account riêng với quyền tối thiểu (`roles/container.defaultNodeServiceAccount`, cộng quyền đọc registry) thay cho nó; tài liệu này giữ SA mặc định. Trên 1 pool `GKE_METADATA`, các pod thông thường dùng Workload Identity của chúng thay vì SA này (xem dòng `--workload-metadata`), nhưng các agent logging và monitoring của GKE và mọi pod `hostNetwork: true` vẫn dùng SA này. |
| IAM role bindings (`roles/storage.objectAdmin`, `roles/secretmanager.secretAccessor`, `roles/iam.workloadIdentityUser`, …) | IAM policies (identity/resource-based) + trust policies | 1 role của GCP là tập quyền được cấp cho 1 principal trên 1 resource; *role* của AWS là 1 danh tính có thể assume (xem dòng GSA). Đại khái: `roles/storage.objectAdmin` ≈ 1 managed policy, binding ở cấp bucket/secret ≈ resource-based policy, và binding `roles/iam.workloadIdentityUser` đóng vai trò của trust policy của role. |
| KSA annotation `iam.gke.io/gcp-service-account` | KSA annotation `eks.amazonaws.com/role-arn` | Cùng cơ chế gắn kết, khác tên annotation. |
| Google Secret Manager | AWS Secrets Manager | Kho lưu secret được quản lý, quyền truy cập qua IAM, có versioning. |
| Secrets Store CSI Driver + **GCP provider** | Secrets Store CSI Driver + **AWS provider** | Cùng 1 driver Kubernetes SIGs gốc (`secrets-store-csi-driver`); chỉ khác plugin theo từng cloud. |
| Google Cloud Storage (GCS) bucket | S3 bucket | Object storage — ở đây là nơi Barman Cloud Plugin của CNPG lưu WAL/backup của Postgres (plugin này cũng hỗ trợ S3 trực tiếp). Việc restore từ đó (`bootstrap.recovery` của CNPG, xem "Khôi phục Postgres từ backup") giống 1 lần restore point-in-time của RDS: cả 2 luôn dựng 1 instance mới từ backup chứ không ghi đè lên instance đang có. Việc chép 1 bucket sang bucket khác hoặc ra 1 thư mục local, hay chép ngược lại (`gcloud storage rsync -r`), tương ứng với `aws s3 sync`, lệnh này mặc định đã đệ quy; `gcloud storage ls` và `gcloud storage rm --recursive` tương ứng với `aws s3 ls` và `aws s3 rm --recursive`. |
| Artifact Registry | Elastic Container Registry (ECR) | Registry lưu image container — chứa 6 image service mà CI pipeline ở Bước 9 build và push. |
| Artifact Registry immutable tags (`--immutable-tags`) | ECR tag immutability (`imageTagMutability: IMMUTABLE`) | Cả 2 đều từ chối lần push làm 1 tag đã có trỏ sang image khác. Artifact Registry chặt hơn: khi bật, không xóa hay gỡ tag được khỏi 1 image còn tag (dù làm tay hay qua cleanup policy), còn ECR vẫn cho xóa image và cho lifecycle policy dọn chúng. |
| Access scopes (node pool / VM: `cloud-platform`, `devstorage.read_only`) | — (no direct equivalent) | Các OAuth scope kiểu cũ, gắn theo từng VM, giới hạn những gì service account gắn với VM được làm, chồng lên trên các IAM role của nó; pull từ Artifact Registry cần `devstorage.read_only` hoặc `cloud-platform` (scope sau giao toàn quyền quyết định cho IAM). Giới hạn gần nhất bên AWS là 1 IAM permissions boundary, đặt trên role chứ không theo từng instance. |
| Google Managed Prometheus (GMP) | Amazon Managed Service for Prometheus (AMP) | Dịch vụ thu thập metric tương thích Prometheus được quản lý, mặc định bật sẵn trên cluster GKE Standard mới. Các pod `gmp-operator` và `collector` (mỗi node 1 pod) của nó chạy trong `gmp-system`. |
| Cloud Monitoring / Cloud Logging | Amazon CloudWatch (metrics / Logs) | Nơi lưu metric và log được quản lý mà `gke-metrics-agent`, `fluentbit-gke` và `event-exporter-gke` ghi vào. Trên EKS, việc đẩy metric node/pod và log container sang CloudWatch phải bật thêm (Container Insights / add-on CloudWatch Observability). |
| GCP project | AWS account | Ranh giới cô lập tài nguyên, IAM và bật API; phần billing được gom về 1 billing account riêng (dòng kế tiếp). |
| Billing account | AWS Organizations management (payer) account | Phương tiện thanh toán mà các project gắn vào, tách rời khỏi bản thân project: credit, quota và các ưu đãi miễn phí được tính theo billing account chứ không theo project — ưu đãi miễn phí của GKE là 1 khoản credit hàng tháng cho mỗi billing account, chỉ bù được phí cluster zonal/Autopilot (xem dòng Zonal cluster). Bên AWS không có sự tách bạch tương ứng dưới cấp account; thay vào đó consolidated billing gom nhiều account về 1 payer account. |
| Free Trial ($300 credit for 90 days) | AWS Free plan (accounts created since 2025-07-15; legacy 12-month Free Tier for older ones) | Cả 2 đều là gói dùng thử dựa trên credit, có hạn kết thúc cứng. Gói của GCP kết thúc sau 90 ngày hoặc khi tiêu hết $300 (~7.875.000₫); khi đó tài nguyên bị dừng và, nếu không nâng cấp trong 30 ngày, bị xoá vĩnh viễn (xem Yêu cầu môi trường). Free plan của AWS cho $100–200 (~2.625.000–5.250.000₫) credit trong tối đa 6 tháng; khi hết, account bị đóng và AWS giữ dữ liệu 90 ngày: nâng cấp lên gói trả phí trong 90 ngày đó thì lấy lại quyền truy cập, nếu không account cùng tài nguyên bị xoá vĩnh viễn. |
| Cloud Billing budget (`gcloud billing budgets create`) | AWS Budgets | Ngưỡng chi tiêu gửi cảnh báo (mặc định qua email); budget của tài liệu này không bao giờ dừng chi tiêu (xem "Cảnh báo ngân sách" trong Yêu cầu môi trường). Spend-cap budget của GCP tạm dừng việc dùng mới của 1 vài dịch vụ tính theo request (như Cloud Run và Gemini API) nhưng không bao giờ dừng VM hay disk đang chạy, nên không dừng được 1 cluster; AWS Budgets tự chạy được Budget Actions (vd áp 1 IAM policy, dừng EC2 instance). Ngoài ra, GCP cần 1 programmatic budget notification (gửi tới 1 topic Pub/Sub) cộng code của chính bạn. |
| Billing account IAM roles (`roles/billing.admin`, `roles/billing.costsManager`) | IAM policies with AWS Budgets actions (`budgets:ModifyBudget`, `budgets:ViewBudget`) | Cần để tạo budget (xem "Cảnh báo ngân sách"). GCP cấp các role này trên billing account, 1 resource tách khỏi project (xem dòng Billing account). AWS không có resource billing riêng: đó là các quyền IAM bình thường trong account sở hữu budget; 1 budget ở management (payer) account có thể theo dõi chi phí của cả organization. |
| Pub/Sub | Amazon SNS (fan-out) / Amazon SQS (queue) | Tài liệu này không dùng; đây là kênh mà programmatic budget notification được gửi tới. 1 budget của AWS cũng có thể gửi thông báo tới 1 topic SNS. |
| Cloud Billing Reports (Console → Billing → Reports) | AWS Cost Explorer | Biểu đồ chi tiêu tương tác theo project/account, dịch vụ và SKU, có hiện riêng các khoản credit; là nơi tài liệu này kiểm tra khoản bù phí của GKE thuộc loại credit nào (xem "Cảnh báo ngân sách"). |
| Billing credit types (`PROMOTION`, `FREE_TIER`, `SUSTAINED_USAGE_DISCOUNT`) | AWS Budgets "Charge type" filter (Credit, Refund, …) | Quyết định budget trừ những credit nào khỏi chi tiêu. GCP chọn được từng loại credit, nhờ vậy budget của tài liệu này trừ credit free tier mà không trừ credit `PROMOTION` của Free Trial; budget của AWS tính hoặc bỏ credit như 1 charge type Credit duy nhất. Sustained use discount không có tương đương bên AWS: Savings Plans và Reserved Instances đều cần cam kết trước. |
| Organization policy (`iam.automaticIamGrantsForDefaultServiceAccounts`) | AWS Organizations policies (SCPs, declarative policies) | Các ràng buộc đặt ở cấp organization, folder hoặc project, giới hạn cấu hình mà các project bên dưới được phép dùng. Constraint này ngăn GCP tự động cấp role Editor cho các service account mặc định, và được enforce mặc định với các organization tạo từ ngày 3/5/2024 trở đi (xem dòng Compute Engine default service account); Google hiện khuyến nghị constraint chặt hơn `iam.managed.preventPrivilegedBasicRolesForDefaultServiceAccounts`, chặn cả việc cấp Editor hoặc Owner cho chúng về sau. 1 project không thuộc organization nào — như project của tài liệu này — thì không có organization policy, nên service account compute mặc định của nó vẫn giữ role Editor được cấp tự động. Bên AWS, SCP giới hạn quyền mà các account được dùng, còn declarative policy enforce cấu hình dịch vụ; cả 2 đều không có constraint tương đương, vì AWS không tạo role mặc định nào để cấp. |
| Google Cloud console (Console → Billing) | AWS Management Console (Billing and Cost Management console) | Giao diện web. Tài liệu này chỉ dùng nó cho những gì `gcloud` không hiển thị: số credit và số ngày còn lại của Free Trial (Billing → Overview) và lần kiểm tra loại credit 1 lần duy nhất trong Billing Reports (xem dòng Cloud Billing Reports). Các trang Billing của nó hiển thị từng billing account một, lọc được theo project; console Billing and Cost Management của AWS hiển thị chi phí của account đang đăng nhập, hoặc, từ management account của 1 organization, chi phí của mọi member account. |
| `gcloud` CLI | `aws` CLI + `eksctl` | GCP gộp thao tác cluster vào `gcloud container clusters`; các thao tác riêng cho EKS bên AWS thường cần thêm `eksctl` (hoặc Terraform) cùng với CLI `aws` gốc. Được cài dưới dạng Google Cloud CLI, 1 trong các công cụ Google gộp dưới tên Google Cloud SDK (cùng với các thư viện client) — vì thế mới có thư mục cài đặt `Cloud SDK` và các biến môi trường `CLOUDSDK_*` như `CLOUDSDK_PYTHON` (xem Yêu cầu môi trường); `gke-gcloud-auth-plugin` là 1 trong các component tùy chọn của nó. Nó tự mang theo Python trên Windows và Linux x86_64; trên macOS, installer sẽ cài 1 bản nếu cần. Bên AWS, 1 "SDK" là 1 thư viện client theo từng ngôn ngữ; CLI `aws` được cài riêng. |
| `gcloud services enable` (API enablement) | — (no per-service enablement) | GCP yêu cầu bật API của từng dịch vụ cho mỗi project; các dịch vụ AWS nhìn chung dùng được mà không cần bước bật riêng (vài tính năng, như Region opt-in, vẫn cần opt-in). |
| `gke-gcloud-auth-plugin` | `aws eks get-token` (via the `aws` CLI) | Plugin exec-credential của kubectl, đổi credential cloud thành token xác thực với cluster. `gcloud container clusters get-credentials` tương ứng với `aws eks update-kubeconfig`. |
| `netd` + GKE's default (non-Dataplane V2) datapath | `aws-node` (Amazon VPC CNI plugin) | DaemonSet networking riêng của từng cloud, chạy trên mỗi node. `netd` thiết lập pod networking của node — sinh CNI spec cho plugin PTP từ PodCIDR của node và quản lý việc chuyển hướng gói tin trên node; GKE chạy nó khi bật Workload Identity Federation for GKE (bật ở đây), intranode visibility hoặc dual-stack. `aws-node` làm nhiều hơn — nó còn cấp cho pod IP thật trong VPC lấy từ ENI. Lưu ý là không có gì trên cluster này thực thi NetworkPolicy: với cluster không dùng Dataplane V2, việc đó cần `--enable-network-policy`, cờ này cài Calico (`calico-node`) và mặc định tắt. Dataplane V2 của GKE (eBPF/Cilium, không dùng ở đây) mới là tương đồng gần của việc chạy Cilium trên EKS. |
| `konnectivity-agent`, `konnectivity-agent-autoscaler` | — (no EKS equivalent) | Tạo tunnel cho traffic từ control plane tới node (`kubectl exec`/`logs`, lời gọi webhook), cần có vì control plane của GKE chạy trong 1 project do Google quản lý. EKS thay vào đó đặt ENI của control plane ngay trong VPC của bạn, nên không có pod tương ứng. |
| `node-local-dns` (NodeLocal DNSCache), `kube-dns-autoscaler` | — (self-managed on EKS) | Các add-on Kubernetes gốc mà GKE cài và quản lý sẵn; trên EKS bạn tự deploy và tự chỉnh kích thước. |
| `kube-dns`, `kube-proxy`, `metrics-server` | CoreDNS, `kube-proxy`, metrics-server (EKS add-ons) | Các pod hệ thống còn lại mà GKE cài sẵn và tự nâng phiên bản giúp bạn. DNS mặc định của cluster GKE là `kube-dns` chứ không phải CoreDNS. EKS cũng cài sẵn CoreDNS và `kube-proxy` theo mặc định, nhưng dưới dạng add-on mà bạn tự nâng phiên bản; `metrics-server` thì cluster EKS không cài mặc định (`eksctl` bản mới thêm nó như 1 add-on của EKS; nếu không, đó là community add-on bạn tự thêm), còn GKE cài sẵn và tự chỉnh kích thước nó. |
| GKE Ingress load balancer (`l7-default-backend`) | AWS Load Balancer Controller (ALB) | Tạo HTTP(S) load balancer từ 1 Ingress. GKE tự chạy controller giúp bạn; trên EKS bạn tự cài. `l7-default-backend` (backend trả 404) không có pod tương đương bên ALB. |
| `BackendConfig` (GKE CRD) | AWS Load Balancer Controller annotations (e.g. `alb.ingress.kubernetes.io/healthcheck-path`) | Cấu hình load balancer theo từng Service (health check, timeout, Cloud CDN, Cloud Armor, IAP, …), gắn vào Service bằng annotation `cloud.google.com/backend-config`. Với controller của AWS, các cấu hình tương đương của ALB (health check, WAF, xác thực OIDC/Cognito, …) là annotation đặt trên Ingress hoặc trên chính Service, annotation của Service được ưu tiên; không có resource riêng chứa cấu hình theo từng Service, và CDN là 1 dịch vụ riêng (CloudFront). |

**Ghi chú**: tài khoản GCP của dự án này đang ở dạng Free Trial, chặn hết mọi yêu cầu tăng quota
(bên AWS, Service Quotas cấp account, cho phép xin tăng qua support case). Cách né quota-cạn dùng
trong tài liệu này (vd dùng `pd-standard` thay vì `pd-balanced` cho `stateless-pool`, xem Bước 1)
là đặc thù của giới hạn Free Trial đó, không phải khác biệt chung giữa GCP và AWS.

---

## Chưa bao gồm trong tài liệu này (việc riêng, làm sau)

- Tự động hoá quy trình "Tạm dừng và bật lại cluster giữa các buổi làm việc" (1 workflow CD/teardown
  hoặc 1 job chạy theo lịch).
- Tự động kiểm tra backup: "Tập dượt restore" là 1 bước kiểm tra làm tay, thỉnh thoảng mới làm,
  không phải 1 job chạy theo lịch tự restore và so sánh.
- Khôi phục Postgres trực tiếp từ đĩa có policy `Retain` mà 1 `Cluster` đã xoá để lại: CNPG dùng
  lại 1 PVC còn sót nếu nó vẫn mang đủ label và annotation của CNPG, nhưng việc tự dựng lại 1 PVC
  như vậy quanh 1 đĩa `Released` không được hướng dẫn ở đây. Hãy khôi phục từ bucket.
- Backup dữ liệu của Kafka — nó chỉ chứa các message saga đang chuyển giữa các service (xem phần
  Yêu cầu môi trường để biết mất chúng thì phải sửa tay những gì).
- Chính sách dọn dẹp Artifact Registry — tag content-hash không bao giờ trùng hay bị ghi đè, nên
  registry chỉ có tăng lên; không có gì ở đây xoá 1 image cũ khi không còn release nào đang deploy
  tham chiếu tới nó nữa. Khi đã bật immutable tags (Bước 9), cleanup policy cũng không xóa được
  image còn tag, nên 1 policy như vậy còn cần tắt immutability, hoặc xử lý tag cũ theo cách khác.
- Việc deploy frontend: image container và Helm chart cho app Angular, 1 workflow CI cho frontend,
  và Ingress công khai (`/` tới frontend, `/api/*` tới gateway, kèm 1 `BackendConfig` health check
  cho mỗi Service backend). Cho tới lúc đó mọi Service chỉ dùng được bên trong cluster, và không có
  gì truy cập được từ bên ngoài cluster.
- 1 Zipkin collector trong cluster — việc export span đang tắt trên GKE
  (`global.tracing.export.zipkin.enabled`) cho tới khi deploy collector cùng giá trị endpoint của
  nó.
- 1 node service account riêng với quyền tối thiểu (`roles/container.defaultNodeServiceAccount`
  cộng `roles/artifactregistry.reader`) thay cho Compute Engine default service account mà các
  node pool đang dùng.

</details>
