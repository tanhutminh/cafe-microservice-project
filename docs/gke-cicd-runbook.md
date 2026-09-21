# GKE Data-Layer & Helm Chart Setup Guide

🇬🇧 English is expanded by default below —
🇻🇳 nhấn vào phần "Tiếng Việt" bên dưới để mở nội dung tiếng Việt.

<details open>
<summary><strong>🇬🇧 English</strong></summary>

This is a step-by-step runbook for provisioning the GKE infrastructure that backs this
project's Kubernetes deployment: the cluster itself, the CNPG (Postgres) and Strimzi (Kafka)
operators, Secret Manager-backed secrets via the Secrets Store CSI Driver, and the Helm charts
that deploy the 6 Spring Boot services.

**Scope**: cluster foundation through a successful `helm install` of `charts/cafe`. It does
**not** cover the CI pipeline (building/pushing container images to a registry) — that is a
separate, not-yet-implemented step. Until it exists, the application pods are created but
show `ImagePullBackOff` (no real image exists yet at `image.repository:image.tag`).

Links to repo files carry `@<hash>`: the last commit that changed that file as of the last time
this guide was checked against it, itself a link to that exact version on GitHub (also available
locally via `git show <hash>:<path>`). If `git log -1 --format=%h -- <path>` now returns a
different hash, the file has changed since and the description may be stale.

All `gcloud` commands assume the default project is set (see Prerequisites). The commands below
are written as plain bash, without a prefix. On some Windows Git Bash setups plain `gcloud` fails
to start; `cmd //c gcloud ...` is a working alternative there, including for calls that pipe data
in on stdin (`--data-file=-`, as in Step 4).

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
- Postgres/Kafka manifests live under `k8s/data-layer/`, applied with plain `kubectl apply`
  — **not** part of any Helm release. This is deliberate: a `helm uninstall`/rollback of the
  app layer must never be able to cascade-delete the database.

## Prerequisites

- `gcloud`, `kubectl`, `helm`, `cmctl` (cert-manager's CLI, installed per cert-manager's
  documentation) and `openssl` installed; `gcloud` authenticated.
- `gke-gcloud-auth-plugin` on your `PATH` (check with `gke-gcloud-auth-plugin --version`).
  `kubectl`, `helm` and `cmctl` all need it to talk to a GKE cluster. Install it with
  `gcloud components install gke-gcloud-auth-plugin` (standalone SDK / Windows installer) or, with
  a package manager, the `google-cloud-cli-gke-gcloud-auth-plugin` package. `clusters create`
  (Step 1) writes the kubeconfig entry itself; to resume from a new shell or machine, run
  `gcloud container clusters get-credentials cafe-cluster --zone=us-central1-a`.
- A bash shell (Git Bash on Windows works) — the commands use bash features such as
  `${var//-/_}` and brace expansion.
- A GCP project with billing enabled.
- Run every command from the repo root — paths like `k8s/data-layer/` and `charts/cafe` are
  relative to it.
- Decide your project ID, cluster name/zone, and Postgres backup bucket name up front. The
  cluster name and zone appear only as `gcloud`/`kubectl` flags in this guide; the project ID and
  bucket name are also baked into IAM bindings and repo files: `charts/cafe/values.yaml`'s
  `global.gcpProjectId` (which renders each `SecretProviderClass`'s `resourceName:` paths and
  each ServiceAccount's `iam.gke.io/gcp-service-account` annotation),
  `k8s/data-layer/postgres-cluster.yaml`'s `serviceAccountTemplate` annotation, and
  `k8s/data-layer/postgres-backup.yaml`'s `destinationPath`. This guide uses the actual values
  from this repo (`cafe-microservices` / `cafe-cluster` / `us-central1-a` /
  `gs://cafe-microservices-cafe-pg-backups`) as examples; substitute your own.

Set the default project and enable the APIs this guide uses (on a fresh project the first
`gcloud` call would otherwise prompt or fail):

```bash
gcloud config set project cafe-microservices
gcloud services enable container.googleapis.com secretmanager.googleapis.com storage.googleapis.com iam.googleapis.com iamcredentials.googleapis.com
```

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
# Stateful: Postgres + Kafka. No autoscaling — fixed size, scaled to 0 manually between sessions.
gcloud container node-pools create stateful-pool \
  --cluster=cafe-cluster --zone=us-central1-a \
  --machine-type=e2-medium --disk-type=pd-balanced --disk-size=100 \
  --num-nodes=1 \
  --node-taints=workload=stateful:NoSchedule \
  --workload-metadata=GKE_METADATA

# Stateless: everything else. Spot + autoscaling min 0 is the real cost lever.
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

# One GSM-reader GSA per service (5 app services + gateway):
for svc in auth-service menu-service order-service inventory-service report-service gateway; do
  gcloud iam service-accounts create "${svc}-gsm-reader"
done
```

Bind each GSA to its matching Kubernetes ServiceAccount (KSA) via Workload Identity. The KSA
doesn't need to exist yet — `charts/cafe-service`'s [serviceaccount.yaml](../charts/cafe-service/templates/serviceaccount.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/charts/cafe-service/templates/serviceaccount.yaml) template will create
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
```

The Postgres pod's own backup GSA is annotated differently — via the CNPG `Cluster`'s
`serviceAccountTemplate` (see [k8s/data-layer/postgres-cluster.yaml](../k8s/data-layer/postgres-cluster.yaml) @[`4c928f4`](https://github.com/tanhutminh/cafe-microservice-project/blob/4c928f4/k8s/data-layer/postgres-cluster.yaml)),
not a Helm-managed ServiceAccount, since it's the data layer, not an app service. That template
only sets the annotation, so the binding above is still required — without it the pod cannot
authenticate to the backup bucket.

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
- **`-f`** [k8s/operators/strimzi-values.yaml](../k8s/operators/strimzi-values.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/k8s/operators/strimzi-values.yaml), which sets `watchNamespaces: [cafe]`: the chart
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

See [auth-service/application.yml](../backend/auth-service/src/main/resources/application.yml) @[`d65155e`](https://github.com/tanhutminh/cafe-microservice-project/blob/d65155e/backend/auth-service/src/main/resources/application.yml)
and [gateway/application.yml](../backend/gateway/src/main/resources/application.yml) @[`d65155e`](https://github.com/tanhutminh/cafe-microservice-project/blob/d65155e/backend/gateway/src/main/resources/application.yml) for the
exact pattern.

---

## Step 6 — Data-layer manifests (`k8s/data-layer/`)

Four files, applied directly with `kubectl` — never wrapped into a Helm chart:

- [postgres-storageclass.yaml](../k8s/data-layer/postgres-storageclass.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/k8s/data-layer/postgres-storageclass.yaml) — a dedicated
  `Retain`-policy StorageClass for Postgres's PVC (GKE's built-in classes are all `Delete`;
  Postgres is this app's source of truth, so its disk must survive even a mistaken PVC/Cluster
  deletion).
- [postgres-cluster.yaml](../k8s/data-layer/postgres-cluster.yaml) @[`4c928f4`](https://github.com/tanhutminh/cafe-microservice-project/blob/4c928f4/k8s/data-layer/postgres-cluster.yaml) — the CNPG `Cluster` and its databases and roles:
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
- [postgres-backup.yaml](../k8s/data-layer/postgres-backup.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/k8s/data-layer/postgres-backup.yaml) — the Barman Cloud Plugin's
  `ObjectStore` (points at the GCS bucket, auths via the Cluster's own Workload Identity, no
  separate credentials Secret) and a daily `ScheduledBackup`.
- [kafka-cluster.yaml](../k8s/data-layer/kafka-cluster.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/k8s/data-layer/kafka-cluster.yaml) — a single-broker KRaft
  `KafkaNodePool` + `Kafka`, pinned to `stateful-pool` via node affinity/toleration. Uses GKE's
  built-in `standard` StorageClass (`Delete` reclaim) deliberately, unlike Postgres — Kafka here
  only carries replayable saga messages, not source-of-truth data. Like Postgres, the Kafka
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

Key values (full schema: [values.yaml](../charts/cafe-service/values.yaml) @[`333eba5`](https://github.com/tanhutminh/cafe-microservice-project/blob/333eba5/charts/cafe-service/values.yaml)):

- `appName` — drives the Deployment/Service/ServiceAccount/ConfigMap names *and* the pod label
  selector; the `SecretProviderClass` is named separately, via `secretProviderClassName`.
- `db.enabled` / `kafka.enabled` / `jwt.privateKey.enabled` / `jwt.publicKey.enabled` — gate
  which env vars, ConfigMap keys, `SecretProviderClass` entries and the `wait-for-db`
  initContainer get rendered for this particular service instance; `db.enabled` also picks the
  rollout `strategy.type`.
- `image.tag` ships with a placeholder default and is meant to be set on every deploy (via
  `--set-string <alias>.image.tag=<built-image-sha>`, see Step 8) — no real image tag exists
  until a CI pipeline builds one.

Templates ([deployment.yaml](../charts/cafe-service/templates/deployment.yaml) @[`75208c9`](https://github.com/tanhutminh/cafe-microservice-project/blob/75208c9/charts/cafe-service/templates/deployment.yaml)):

- `strategy.type` is `Recreate` for DB-backed services (`RollingUpdate` otherwise) — avoids an
  old pod and a post-Flyway-migration new pod running side by side; an acceptable trade for a
  project that isn't targeting zero-downtime deploys.
- A `wait-for-db` initContainer (DB-backed services only) retries a `psql` connection for up to
  600s using `date +%s` — **not** `$SECONDS`, which BusyBox `ash` (the `postgres:16-alpine`
  image's shell) silently expands to empty, turning the timeout check into dead code.
- `startupProbe` (30 × 10s budget) gates when `readinessProbe`/`livenessProbe` even start being
  checked — more robust than a fixed `initialDelaySeconds` guess under JVM + Flyway boot time on
  a Spot node.
- Two separate pod volumes: `config` (a `configMap`) and `secrets-store` (a `csi` volume) — they
  cannot be nested under one `projected` volume, since `csi` isn't a valid `projected` source.
  The CSI volume **must** actually be mounted (not just declared) — an unmounted CSI volume
  never triggers the driver's `secretObjects` sync, so the derived `Secret` never gets created.

[secretproviderclass.yaml](../charts/cafe-service/templates/secretproviderclass.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/charts/cafe-service/templates/secretproviderclass.yaml) lists the
GSM secrets this service instance needs (conditionally, per the `db.enabled` / `jwt.*.enabled`
flags) and maps them into `secretObjects` — the bridge from "files mounted under
`/mnt/secrets-store`" to "a real K8s `Secret` other resources can reference via `secretKeyRef`".

### `charts/cafe` — umbrella chart

[Chart.yaml](../charts/cafe/Chart.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/charts/cafe/Chart.yaml) declares `cafe-service` as 6 *aliased* Helm
dependencies (the standard pattern for many near-identical service instances sharing one
chart). [values.yaml](../charts/cafe/values.yaml) @[`333eba5`](https://github.com/tanhutminh/cafe-microservice-project/blob/333eba5/charts/cafe/values.yaml) sets `global.gcpProjectId` once, plus one
block per alias with that service's real port/db/kafka/jwt values and its own `image.repository`.

```bash
helm dependency update charts/cafe
# render and lint locally before touching the real cluster (rendering is the real check);
# lint should report 0 failed - an "icon is recommended" INFO and a "templates/ directory does
# not exist" warning are normal for this umbrella chart
helm lint charts/cafe
helm template charts/cafe > /dev/null
```

---

## Step 8 — Deploy for real

```bash
TAG=dummy   # replace with the image tag CI produced, once a CI pipeline exists

helm upgrade --install cafe charts/cafe -n cafe \
  --set-string gateway.image.tag="$TAG" \
  --set-string auth-service.image.tag="$TAG" \
  --set-string menu-service.image.tag="$TAG" \
  --set-string order-service.image.tag="$TAG" \
  --set-string inventory-service.image.tag="$TAG" \
  --set-string report-service.image.tag="$TAG"

kubectl get pods -n cafe
kubectl get secret -n cafe
```

Until a CI pipeline builds real images, `TAG` can only be a dummy value like the one above, so
the app pods end up in `ImagePullBackOff`. Expect the DB-backed pods to pass through `Init:0/1`
(their `wait-for-db` initContainer) and then `ErrImagePull` → `ImagePullBackOff`; the gateway has
no initContainer and goes straight to `ImagePullBackOff` (pods can also sit `Pending` briefly
while the Spot `stateless-pool` scales up from 0). The `{service}-db-credentials` and `*-jwt-key`
Secrets should appear, and `cafe-postgres-1` and the Kafka pod should be `Running`.

`-n cafe` is mandatory — nothing in the chart hardcodes a namespace (every template uses
`{{ .Release.Namespace }}`), so omitting it silently deploys everything, including each
Deployment's own ServiceAccount, into `default` instead.

### Troubleshooting a first real deploy

Symptoms you may hit on a first real deploy, with their root causes:

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
7. **Every application pod sits in `ImagePullBackOff`** — expected: no CI pipeline has built or
   pushed real images yet (out of scope for this guide), so there is nothing to pull.

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
| Node autoscaling (`--enable-autoscaling`, min 0) | Cluster Autoscaler / Karpenter | Adds or removes nodes based on pending pods. GKE's autoscaler is built in and configured per node pool; on EKS you typically install Cluster Autoscaler or Karpenter yourself. |
| GCP machine type (`e2-medium`) | AWS EC2 instance type (e.g. `t3.medium`) | Different per-cloud naming/sizing scheme; `t3.medium` matches `e2-medium`'s shape closely — both 2 vCPU/4GB, both burstable/cost-optimized. |
| GKE node allocatable reservation (1060 mCPU on shared-core E2) | EKS `kube-reserved` (node bootstrap defaults) | Both carve a fixed slice off each node for system components. GKE publishes one tiered CPU formula for all machine types (6% of the first core, 1% of the next core, 0.5% of the next 2 cores, 0.25% of anything above 4 cores) and overrides it with a flat 1060 mCPU on shared-core E2 types; EKS's optimized AMI applies that same tiered CPU formula at node bootstrap, with no shared-core exception. Only CPU lines up — each side computes its memory reservation differently. See "GKE system pods added automatically per node". |
| Spot VM | EC2 Spot Instance | Same mechanism: spare capacity at a discount, reclaimable with short notice. |
| Persistent Disk (`pd-standard`/`pd-balanced`/`pd-ssd`) | EBS (`gp2`/`gp3`/`io1`/`io2`/`st1`/`sc1`) | Network-attached block storage tiers; `pd-standard` ≈ `st1`/`sc1` (HDD), `pd-balanced` ≈ `gp3`, `pd-ssd` sits roughly between `gp3` and `io1`/`io2` (no exact match); `pd-extreme` (not used here) is the closest analogue of the provisioned-IOPS `io1`/`io2`. |
| PD CSI driver (`pdcsi-node`) + default StorageClass (`standard-rwo`) | EBS CSI driver (EKS add-on) + default StorageClass (commonly `gp2`) | Provisions PersistentVolumes from block storage (the Persistent Disk row covers the disk tiers). GKE ships the driver preinstalled; on EKS it is an add-on that needs its own IAM setup. |
| Workload Identity Federation | IAM Roles for Service Accounts (IRSA) / EKS Pod Identity | Both let a pod assume a cloud IAM identity with no static key. IRSA wires this through an OIDC provider registered against the cluster; EKS Pod Identity (newer) simplifies the same idea. The workload pool (`<project>.svc.id.goog`, used in `serviceAccount:<pool>[ns/ksa]` members) is the trust anchor, like the IAM OIDC provider in IRSA; Pod Identity has no counterpart. GCP creates the pool automatically, once per project. |
| `gke-metadata-server` | EKS Pod Identity Agent | The per-node pod that serves Workload Identity credentials to pods. Closest analogue only: IRSA needs no such per-node pod. |
| `--workload-metadata=GKE_METADATA` (node pool) | — (no equivalent) | Per-node-pool switch replacing the raw GCE metadata server with the Workload Identity one; without it, pods on that pool fall back to the node's own service account. Turning it on for an existing pool takes effect immediately for workloads already running there, which stops them using the node's service account and can disrupt them. EKS needs no node-level toggle — IRSA/Pod Identity work per pod. |
| Google Service Account (GSA) | IAM Role | The cloud-side identity a KSA is bound to. |
| IAM role bindings (`roles/storage.objectAdmin`, `roles/secretmanager.secretAccessor`, `roles/iam.workloadIdentityUser`, …) | IAM policies (identity/resource-based) + trust policies | A GCP role is a permission set granted to a principal on a resource; an AWS *role* is an assumable identity (see the GSA row). Roughly: `roles/storage.objectAdmin` ≈ a managed policy, bucket- and secret-level bindings ≈ resource-based policies, and the `roles/iam.workloadIdentityUser` binding plays the part of a role's trust policy. |
| KSA annotation `iam.gke.io/gcp-service-account` | KSA annotation `eks.amazonaws.com/role-arn` | Same binding mechanism, different annotation key. |
| Google Secret Manager | AWS Secrets Manager | Managed secret storage with IAM-scoped access and versioning. |
| Secrets Store CSI Driver + **GCP provider** | Secrets Store CSI Driver + **AWS provider** | Same upstream Kubernetes SIGs driver (`secrets-store-csi-driver`); only the cloud-provider plugin differs. |
| Google Cloud Storage (GCS) bucket | S3 bucket | Object storage — here, where CNPG's Barman Cloud Plugin archives Postgres WAL/backups (the plugin supports S3 natively too). |
| Artifact Registry | Elastic Container Registry (ECR) | Container image registry — not yet used in this project (planned for the CI pipeline, see "Not covered here"). |
| Google Managed Prometheus (GMP) | Amazon Managed Service for Prometheus (AMP) | Managed Prometheus-compatible metrics collection, enabled by default on a new GKE Standard cluster. Its `gmp-operator` and per-node `collector` pods run in `gmp-system`. |
| Cloud Monitoring / Cloud Logging | Amazon CloudWatch (metrics / Logs) | The managed metric and log stores that `gke-metrics-agent`, `fluentbit-gke` and `event-exporter-gke` write to. On EKS, sending node/pod metrics and container logs to CloudWatch is opt-in (Container Insights / the CloudWatch Observability add-on). |
| GCP project | AWS account | The resource-isolation, IAM and API-enablement boundary; billing rolls up to a separate billing account (next row). |
| Billing account | AWS Organizations management (payer) account | The payment instrument projects attach to, separate from the projects themselves: credits, quota and free-tier allowances are counted per billing account, not per project — GKE's free tier is a monthly credit per billing account that only offsets zonal/Autopilot cluster fees (see the Zonal cluster row). AWS has no equivalent split below the account; consolidated billing instead rolls several accounts up under one payer account. |
| `gcloud` CLI | `aws` CLI + `eksctl` | GCP bundles cluster operations into `gcloud container clusters`; EKS-specific operations on AWS typically need `eksctl` (or Terraform) alongside the base `aws` CLI. |
| `gcloud services enable` (API enablement) | — (no per-service enablement) | GCP requires enabling each service's API per project; AWS services are generally usable without a separate enablement step (some features, such as opt-in Regions, still need opting in). |
| `gke-gcloud-auth-plugin` | `aws eks get-token` (via the `aws` CLI) | kubectl exec-credential plugin that turns cloud credentials into cluster auth tokens. `gcloud container clusters get-credentials` corresponds to `aws eks update-kubeconfig`. |
| `netd` + GKE's default (non-Dataplane V2) datapath | `aws-node` (Amazon VPC CNI plugin) | Each cloud's own per-node networking DaemonSet. `netd` sets up the node's Pod networking — it generates the CNI spec for the PTP plugin from the node's PodCIDR and manages packet redirection on the node; GKE runs it when Workload Identity Federation for GKE (enabled here), intranode visibility or dual-stack is on. `aws-node` does more — it also hands pods real VPC IPs from ENIs. Note that nothing on this cluster enforces NetworkPolicy: on a non-Dataplane V2 cluster that needs `--enable-network-policy`, which installs Calico (`calico-node`) and is off by default. GKE's Dataplane V2 (eBPF/Cilium, not used here) is the closer analogue of running Cilium on EKS. |
| `konnectivity-agent`, `konnectivity-agent-autoscaler` | — (no EKS equivalent) | Tunnels control-plane-to-node traffic (`kubectl exec`/`logs`, webhook calls), needed because GKE's control plane runs in a Google-managed project. EKS places control-plane ENIs directly in your VPC instead, so no such pods exist there. |
| `node-local-dns` (NodeLocal DNSCache), `kube-dns-autoscaler` | — (self-managed on EKS) | Upstream Kubernetes add-ons that GKE installs and manages for you; on EKS you deploy and size them yourself. |
| `kube-dns`, `kube-proxy`, `metrics-server` | CoreDNS, `kube-proxy`, metrics-server (EKS add-ons) | The remaining system pods GKE preinstalls and versions for you. GKE's default cluster DNS is `kube-dns`, not CoreDNS. EKS installs CoreDNS and `kube-proxy` by default too, but as add-ons you version yourself; `metrics-server` is not installed by default on an EKS cluster (recent `eksctl` versions add it as an EKS add-on; otherwise it is an EKS community add-on you add yourself), while GKE ships and auto-resizes it. |
| GKE Ingress load balancer (`l7-default-backend`) | AWS Load Balancer Controller (ALB) | Provisions an HTTP(S) load balancer from an Ingress. GKE runs the controller for you; on EKS you install it yourself. `l7-default-backend` (the 404 backend) has no pod-level equivalent on ALB. |

**Note**: this project's GCP account is on a Free Trial, which blocks all quota increase
requests (AWS's equivalent, account-level Service Quotas, allows requesting increases via a
support case). The quota-exhaustion workaround used in this guide (e.g. `pd-standard` over
`pd-balanced` for `stateless-pool`, see Step 1) is specific to that Free Trial limitation, not a
general GCP-vs-AWS difference.

---

## Not covered here (separate, future work)

- CI pipeline (build + push images to Artifact Registry, Workload Identity Federation for
  CI→GCP auth) — without this, `image.repository:image.tag` never resolves to a real image.
- CD/teardown automation (scaling `stateful-pool` up/down around a deploy, ordered graceful
  shutdown of Postgres/Kafka).
- kubeconform/CI validation of `k8s/data-layer/*.yaml`.

</details>

<details>
<summary><strong>🇻🇳 Tiếng Việt</strong></summary>

Đây là hướng dẫn từng bước để dựng hạ tầng GKE cho việc triển khai Kubernetes của dự án: bản thân
cluster, operator CNPG (Postgres) và Strimzi (Kafka), secret lấy từ Secret Manager qua Secrets
Store CSI Driver, và các Helm chart triển khai 6 service Spring Boot.

**Phạm vi**: từ hạ tầng cluster tới khi `helm install` `charts/cafe` thành công. Tài liệu này
**không** bao gồm CI pipeline (build/push image vào registry) — đó là bước riêng, chưa triển
khai. Cho tới khi bước đó có, các pod ứng dụng được tạo ra nhưng sẽ báo `ImagePullBackOff` (chưa
có image thật ở `image.repository:image.tag`).

Các link tới file trong repo có kèm `@<hash>`: commit gần nhất đã thay đổi file đó tính tới lần
gần nhất tài liệu này được đối chiếu với file, bản thân hash là link tới đúng phiên bản đó trên
GitHub (cũng xem được ở local bằng `git show <hash>:<path>`). Nếu
`git log -1 --format=%h -- <path>` giờ trả về hash khác thì file đã đổi kể từ đó và phần mô tả
có thể đã lỗi thời.

Mọi lệnh `gcloud` giả định đã set project mặc định (xem mục "Yêu cầu môi trường"). Các lệnh bên
dưới được viết dạng bash thuần, không kèm tiền tố. Trên một số cấu hình Git Bash Windows,
`gcloud` trần không khởi động được; `cmd //c gcloud ...` là cách thay thế dùng được ở đó, kể cả
với các lệnh pipe dữ liệu vào stdin (`--data-file=-`, như ở Bước 4).

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
- Manifest Postgres/Kafka nằm ở `k8s/data-layer/`, áp dụng bằng `kubectl apply` thuần — **không**
  thuộc bất kỳ Helm release nào. Đây là chủ đích: `helm uninstall`/rollback tầng ứng dụng không
  bao giờ được phép cascade-xoá database.

## Yêu cầu môi trường

- Đã cài CLI `gcloud`, `kubectl`, `helm`, `cmctl` (CLI của cert-manager, cài theo tài liệu của
  cert-manager) và `openssl`; `gcloud` đã đăng nhập.
- `gke-gcloud-auth-plugin` nằm trong `PATH` (kiểm tra bằng `gke-gcloud-auth-plugin --version`).
  `kubectl`, `helm` và `cmctl` đều cần nó để nói chuyện với cluster GKE. Cài bằng
  `gcloud components install gke-gcloud-auth-plugin` (SDK độc lập / bộ cài Windows) hoặc, nếu
  dùng package manager, gói `google-cloud-cli-gke-gcloud-auth-plugin`. `clusters create` (Bước 1)
  tự ghi entry kubeconfig; khi làm tiếp từ shell hoặc máy mới, chạy
  `gcloud container clusters get-credentials cafe-cluster --zone=us-central1-a`.
- Có shell bash (Git Bash trên Windows dùng được) — các lệnh dùng tính năng của bash như
  `${var//-/_}` và brace expansion.
- Project GCP đã bật billing.
- Chạy mọi lệnh từ thư mục gốc của repo — các đường dẫn như `k8s/data-layer/` và `charts/cafe`
  là đường dẫn tương đối so với thư mục đó.
- Chốt trước project ID, tên/zone cluster, tên bucket backup Postgres. Tên cluster và zone chỉ
  xuất hiện dưới dạng flag của `gcloud`/`kubectl` trong tài liệu này; project ID và tên bucket
  còn được đưa vào IAM binding và các file của repo: `global.gcpProjectId` của
  `charts/cafe/values.yaml` (giá trị này render ra đường dẫn `resourceName:` của từng
  `SecretProviderClass` và annotation `iam.gke.io/gcp-service-account` của từng ServiceAccount),
  annotation `serviceAccountTemplate` trong `k8s/data-layer/postgres-cluster.yaml`, và
  `destinationPath` trong `k8s/data-layer/postgres-backup.yaml`. Tài liệu này dùng đúng giá trị
  thật của repo này (`cafe-microservices` / `cafe-cluster` / `us-central1-a` /
  `gs://cafe-microservices-cafe-pg-backups`) làm ví dụ; thay bằng giá trị của bạn.

Đặt project mặc định và bật các API mà tài liệu này dùng (trên project mới, lệnh `gcloud` đầu
tiên sẽ hỏi hoặc báo lỗi nếu chưa bật):

```bash
gcloud config set project cafe-microservices
gcloud services enable container.googleapis.com secretmanager.googleapis.com storage.googleapis.com iam.googleapis.com iamcredentials.googleapis.com
```

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
# Stateful: Postgres + Kafka. No autoscaling — fixed size, scaled to 0 manually between sessions.
gcloud container node-pools create stateful-pool \
  --cluster=cafe-cluster --zone=us-central1-a \
  --machine-type=e2-medium --disk-type=pd-balanced --disk-size=100 \
  --num-nodes=1 \
  --node-taints=workload=stateful:NoSchedule \
  --workload-metadata=GKE_METADATA

# Stateless: everything else. Spot + autoscaling min 0 is the real cost lever.
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

# One GSM-reader GSA per service (5 app services + gateway):
for svc in auth-service menu-service order-service inventory-service report-service gateway; do
  gcloud iam service-accounts create "${svc}-gsm-reader"
done
```

Gắn mỗi GSA với ServiceAccount Kubernetes (KSA) tương ứng qua Workload Identity. KSA chưa cần
tồn tại — template [serviceaccount.yaml](../charts/cafe-service/templates/serviceaccount.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/charts/cafe-service/templates/serviceaccount.yaml) của `charts/cafe-service` sẽ tạo nó sau, đúng tên và có
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
```

GSA backup của pod Postgres được gắn annotation theo cách khác — qua `serviceAccountTemplate` của
CNPG `Cluster` (xem [k8s/data-layer/postgres-cluster.yaml](../k8s/data-layer/postgres-cluster.yaml) @[`4c928f4`](https://github.com/tanhutminh/cafe-microservice-project/blob/4c928f4/k8s/data-layer/postgres-cluster.yaml)),
không phải ServiceAccount do Helm quản lý, vì đây là tầng dữ liệu, không phải service ứng dụng.
Template đó chỉ đặt annotation, nên binding ở trên vẫn bắt buộc — thiếu nó thì pod không xác
thực được với bucket backup.

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
- **`-f`** [k8s/operators/strimzi-values.yaml](../k8s/operators/strimzi-values.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/k8s/operators/strimzi-values.yaml), đặt `watchNamespaces: [cafe]`: chart mặc
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

Xem [auth-service/application.yml](../backend/auth-service/src/main/resources/application.yml) @[`d65155e`](https://github.com/tanhutminh/cafe-microservice-project/blob/d65155e/backend/auth-service/src/main/resources/application.yml)
và [gateway/application.yml](../backend/gateway/src/main/resources/application.yml) @[`d65155e`](https://github.com/tanhutminh/cafe-microservice-project/blob/d65155e/backend/gateway/src/main/resources/application.yml) để thấy
đúng khuôn mẫu này.

---

## Bước 6 — Manifest tầng dữ liệu (`k8s/data-layer/`)

4 file, áp dụng trực tiếp bằng `kubectl` — không bao giờ đóng gói vào Helm chart:

- [postgres-storageclass.yaml](../k8s/data-layer/postgres-storageclass.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/k8s/data-layer/postgres-storageclass.yaml) — 1 StorageClass
  riêng với policy `Retain` cho PVC của Postgres (các class dựng sẵn của GKE đều là
  `Delete`; Postgres là nguồn dữ liệu gốc của app này, nên đĩa của nó phải sống sót kể cả khi
  PVC/Cluster bị xoá nhầm).
- [postgres-cluster.yaml](../k8s/data-layer/postgres-cluster.yaml) @[`4c928f4`](https://github.com/tanhutminh/cafe-microservice-project/blob/4c928f4/k8s/data-layer/postgres-cluster.yaml) — CNPG `Cluster` cùng các database và role của nó:
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
- [postgres-backup.yaml](../k8s/data-layer/postgres-backup.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/k8s/data-layer/postgres-backup.yaml) — `ObjectStore` của Barman
  Cloud Plugin (trỏ tới GCS bucket, xác thực qua Workload Identity của chính Cluster, không cần
  Secret credential riêng) và 1 `ScheduledBackup` chạy hàng ngày.
- [kafka-cluster.yaml](../k8s/data-layer/kafka-cluster.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/k8s/data-layer/kafka-cluster.yaml) — `KafkaNodePool` + `Kafka` KRaft
  1 broker, ghim vào `stateful-pool` qua node affinity/toleration. Cố tình dùng StorageClass
  `standard` dựng sẵn của GKE (reclaim `Delete`), khác với Postgres — Kafka ở đây chỉ chứa
  message saga có thể replay lại, không phải dữ liệu gốc. Giống Postgres, phiên bản Kafka (4.3.1)
  được ghim, nên nâng cấp operator Strimzi không thể âm thầm đổi nó.

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

Các value chính (schema đầy đủ: [values.yaml](../charts/cafe-service/values.yaml) @[`333eba5`](https://github.com/tanhutminh/cafe-microservice-project/blob/333eba5/charts/cafe-service/values.yaml)):

- `appName` — quyết định tên của Deployment/Service/ServiceAccount/ConfigMap *và* label selector
  của pod; riêng `SecretProviderClass` được đặt tên qua `secretProviderClassName`.
- `db.enabled` / `kafka.enabled` / `jwt.privateKey.enabled` / `jwt.publicKey.enabled` — bật/tắt
  env var, key trong ConfigMap, mục `SecretProviderClass` và initContainer `wait-for-db` nào
  được render cho instance service đó; riêng `db.enabled` còn quyết định `strategy.type` của
  rollout.
- `image.tag` có sẵn 1 giá trị mặc định giữ chỗ và được thiết kế để set ở mỗi lần deploy (qua
  `--set-string <alias>.image.tag=<built-image-sha>`, xem Bước 8) — chưa có tag image thật nào
  cho tới khi CI pipeline build ra.

Template ([deployment.yaml](../charts/cafe-service/templates/deployment.yaml) @[`75208c9`](https://github.com/tanhutminh/cafe-microservice-project/blob/75208c9/charts/cafe-service/templates/deployment.yaml)):

- `strategy.type` là `Recreate` cho service dùng DB (`RollingUpdate` cho các service còn lại)
  — tránh việc 1 pod cũ và 1 pod mới (sau khi Flyway đã migrate) chạy song song; đánh đổi chấp
  nhận được cho 1 dự án không nhắm tới zero-downtime deploy.
- initContainer `wait-for-db` (chỉ với service dùng DB) thử kết nối `psql` lặp lại tới 600s,
  dùng `date +%s` — **không phải** `$SECONDS`, vì BusyBox `ash` (shell của image
  `postgres:16-alpine`) âm thầm coi nó là chuỗi rỗng, biến điều kiện timeout thành dead code.
- `startupProbe` (ngân sách 30 × 10s) quyết định khi nào `readinessProbe`/`livenessProbe` mới
  bắt đầu được kiểm tra — chắc chắn hơn việc đoán 1 `initialDelaySeconds` cố định trong lúc JVM
  + Flyway khởi động trên node Spot.
- 2 volume pod riêng biệt: `config` (kiểu `configMap`) và `secrets-store` (kiểu `csi`) — không
  thể lồng chung vào 1 volume `projected`, vì `csi` không phải nguồn hợp lệ của `projected`.
  Volume CSI **bắt buộc phải** thực sự được mount (không chỉ khai báo) — 1 volume CSI không
  được mount sẽ không bao giờ kích hoạt việc đồng bộ `secretObjects` của driver, nên `Secret`
  tương ứng sẽ không bao giờ được tạo ra.

[secretproviderclass.yaml](../charts/cafe-service/templates/secretproviderclass.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/charts/cafe-service/templates/secretproviderclass.yaml) liệt kê
các secret GSM mà instance service đó cần (có điều kiện, theo các cờ `db.enabled` /
`jwt.*.enabled`) và map chúng vào `secretObjects` — cầu nối từ "file được mount ở
`/mnt/secrets-store`" sang "1 `Secret` K8s thật mà resource khác có thể tham chiếu qua
`secretKeyRef`".

### `charts/cafe` — umbrella chart

[Chart.yaml](../charts/cafe/Chart.yaml) @[`ed04e41`](https://github.com/tanhutminh/cafe-microservice-project/blob/ed04e41/charts/cafe/Chart.yaml) khai báo `cafe-service` như 6 dependency Helm được
*alias* (mẫu chuẩn cho nhiều instance service gần giống nhau dùng chung 1 chart).
[values.yaml](../charts/cafe/values.yaml) @[`333eba5`](https://github.com/tanhutminh/cafe-microservice-project/blob/333eba5/charts/cafe/values.yaml) set `global.gcpProjectId` 1 lần duy nhất, cộng thêm 1
block cho mỗi alias với giá trị port/db/kafka/jwt thật của service đó và `image.repository`
riêng của nó.

```bash
helm dependency update charts/cafe
# render and lint locally before touching the real cluster (rendering is the real check);
# lint should report 0 failed - an "icon is recommended" INFO and a "templates/ directory does
# not exist" warning are normal for this umbrella chart
helm lint charts/cafe
helm template charts/cafe > /dev/null
```

---

## Bước 8 — Deploy thật

```bash
TAG=dummy   # replace with the image tag CI produced, once a CI pipeline exists

helm upgrade --install cafe charts/cafe -n cafe \
  --set-string gateway.image.tag="$TAG" \
  --set-string auth-service.image.tag="$TAG" \
  --set-string menu-service.image.tag="$TAG" \
  --set-string order-service.image.tag="$TAG" \
  --set-string inventory-service.image.tag="$TAG" \
  --set-string report-service.image.tag="$TAG"

kubectl get pods -n cafe
kubectl get secret -n cafe
```

Cho tới khi có CI pipeline build image thật, `TAG` chỉ có thể là giá trị giả như ở trên, nên các
app pod sẽ kết thúc ở `ImagePullBackOff`. Các pod dùng DB sẽ đi qua `Init:0/1` (initContainer
`wait-for-db` của chúng) rồi `ErrImagePull` → `ImagePullBackOff`; gateway không có initContainer
nên đi thẳng tới `ImagePullBackOff` (pod cũng có thể `Pending` ngắn khi pool Spot `stateless-pool`
lên từ 0 node). Các Secret `{service}-db-credentials` và `*-jwt-key` phải xuất hiện, còn
`cafe-postgres-1` và pod Kafka phải `Running`.

`-n cafe` là bắt buộc — không có gì trong chart hardcode namespace (mọi template đều dùng
`{{ .Release.Namespace }}`), nên bỏ qua nó sẽ âm thầm deploy mọi thứ, kể cả ServiceAccount của
từng Deployment, vào `default` thay vì `cafe`.

### Xử lý sự cố khi deploy thật lần đầu

Các triệu chứng có thể gặp khi deploy thật lần đầu, kèm nguyên nhân gốc:

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
7. **Mọi app pod đều ở `ImagePullBackOff`** — bình thường: chưa có CI pipeline nào build/push
   image thật (nằm ngoài phạm vi tài liệu này), nên không có gì để kéo về.

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
| Node autoscaling (`--enable-autoscaling`, min 0) | Cluster Autoscaler / Karpenter | Thêm hoặc bớt node theo số pod đang chờ. Autoscaler của GKE có sẵn và cấu hình theo từng node pool; trên EKS bạn thường phải tự cài Cluster Autoscaler hoặc Karpenter. |
| GCP machine type (`e2-medium`) | AWS EC2 instance type (e.g. `t3.medium`) | Cách đặt tên/phân loại kích thước khác nhau giữa 2 cloud; `t3.medium` khớp khá sát hình dạng của `e2-medium` — cả 2 đều 2 vCPU/4GB, đều thuộc nhóm burstable/tối ưu chi phí. |
| GKE node allocatable reservation (1060 mCPU on shared-core E2) | EKS `kube-reserved` (node bootstrap defaults) | Cả 2 đều cắt 1 phần cố định của mỗi node cho thành phần hệ thống. GKE công bố 1 công thức CPU theo bậc dùng chung cho mọi loại máy (6% core đầu tiên, 1% core kế tiếp, 0,5% cho 2 core kế, 0,25% cho phần vượt quá 4 core) và ghi đè bằng mức cố định 1060 mCPU trên các máy E2 shared-core; AMI tối ưu của EKS áp dụng đúng công thức CPU theo bậc đó lúc bootstrap node, không có ngoại lệ nào cho máy shared-core. Chỉ riêng CPU là khớp — phần memory thì mỗi bên tính theo cách khác nhau. Xem phụ lục "các pod hệ thống GKE tự động thêm vào mỗi node". |
| Spot VM | EC2 Spot Instance | Cùng cơ chế: dùng capacity dư thừa với giá rẻ hơn, có thể bị thu hồi với báo trước ngắn. |
| Persistent Disk (`pd-standard`/`pd-balanced`/`pd-ssd`) | EBS (`gp2`/`gp3`/`io1`/`io2`/`st1`/`sc1`) | Các tier lưu trữ block gắn qua mạng; `pd-standard` ≈ `st1`/`sc1` (HDD), `pd-balanced` ≈ `gp3`, `pd-ssd` nằm khoảng giữa `gp3` và `io1`/`io2` (không có tương đương chính xác); `pd-extreme` (không dùng ở đây) là tương đương gần nhất của `io1`/`io2` provisioned-IOPS. |
| PD CSI driver (`pdcsi-node`) + default StorageClass (`standard-rwo`) | EBS CSI driver (EKS add-on) + default StorageClass (commonly `gp2`) | Cấp PersistentVolume từ block storage (dòng Persistent Disk đã nói về các tier đĩa). GKE cài sẵn driver; trên EKS đây là add-on cần cấu hình IAM riêng. |
| Workload Identity Federation | IAM Roles for Service Accounts (IRSA) / EKS Pod Identity | Cả 2 đều cho phép 1 pod nhận danh tính IAM của cloud mà không cần static key. IRSA nối qua 1 OIDC provider đăng ký với cluster; EKS Pod Identity (mới hơn) đơn giản hoá cùng ý tưởng đó. Workload pool (`<project>.svc.id.goog`, dùng trong member `serviceAccount:<pool>[ns/ksa]`) là điểm neo tin cậy, giống IAM OIDC provider của IRSA; Pod Identity không có khái niệm tương ứng. GCP tự tạo pool, 1 lần cho mỗi project. |
| `gke-metadata-server` | EKS Pod Identity Agent | Pod chạy trên mỗi node, cấp credential Workload Identity cho các pod. Chỉ là tương đương gần nhất: IRSA không cần pod theo node như vậy. |
| `--workload-metadata=GKE_METADATA` (node pool) | — (no equivalent) | Công tắc theo từng node pool, thay metadata server GCE thô bằng metadata server của Workload Identity; không có nó, pod trên pool đó rơi về dùng service account của chính node. Bật nó trên pool đã tồn tại có hiệu lực ngay với các workload đang chạy ở đó, khiến chúng không còn dùng được service account của node và có thể gây gián đoạn. EKS không cần công tắc cấp node như vậy — IRSA/Pod Identity hoạt động theo từng pod. |
| Google Service Account (GSA) | IAM Role | Danh tính phía cloud mà 1 KSA được gắn vào. |
| IAM role bindings (`roles/storage.objectAdmin`, `roles/secretmanager.secretAccessor`, `roles/iam.workloadIdentityUser`, …) | IAM policies (identity/resource-based) + trust policies | 1 role của GCP là tập quyền được cấp cho 1 principal trên 1 resource; *role* của AWS là 1 danh tính có thể assume (xem dòng GSA). Đại khái: `roles/storage.objectAdmin` ≈ 1 managed policy, binding ở cấp bucket/secret ≈ resource-based policy, và binding `roles/iam.workloadIdentityUser` đóng vai trò của trust policy của role. |
| KSA annotation `iam.gke.io/gcp-service-account` | KSA annotation `eks.amazonaws.com/role-arn` | Cùng cơ chế gắn kết, khác tên annotation. |
| Google Secret Manager | AWS Secrets Manager | Kho lưu secret được quản lý, quyền truy cập qua IAM, có versioning. |
| Secrets Store CSI Driver + **GCP provider** | Secrets Store CSI Driver + **AWS provider** | Cùng 1 driver Kubernetes SIGs gốc (`secrets-store-csi-driver`); chỉ khác plugin theo từng cloud. |
| Google Cloud Storage (GCS) bucket | S3 bucket | Object storage — ở đây là nơi Barman Cloud Plugin của CNPG lưu WAL/backup của Postgres (plugin này cũng hỗ trợ S3 trực tiếp). |
| Artifact Registry | Elastic Container Registry (ECR) | Registry lưu image container — dự án này chưa dùng tới (dự kiến cho CI pipeline, xem mục "Chưa bao gồm"). |
| Google Managed Prometheus (GMP) | Amazon Managed Service for Prometheus (AMP) | Dịch vụ thu thập metric tương thích Prometheus được quản lý, mặc định bật sẵn trên cluster GKE Standard mới. Các pod `gmp-operator` và `collector` (mỗi node 1 pod) của nó chạy trong `gmp-system`. |
| Cloud Monitoring / Cloud Logging | Amazon CloudWatch (metrics / Logs) | Nơi lưu metric và log được quản lý mà `gke-metrics-agent`, `fluentbit-gke` và `event-exporter-gke` ghi vào. Trên EKS, việc đẩy metric node/pod và log container sang CloudWatch phải bật thêm (Container Insights / add-on CloudWatch Observability). |
| GCP project | AWS account | Ranh giới cô lập tài nguyên, IAM và bật API; phần billing được gom về 1 billing account riêng (dòng kế tiếp). |
| Billing account | AWS Organizations management (payer) account | Phương tiện thanh toán mà các project gắn vào, tách rời khỏi bản thân project: credit, quota và các ưu đãi miễn phí được tính theo billing account chứ không theo project — ưu đãi miễn phí của GKE là 1 khoản credit hàng tháng cho mỗi billing account, chỉ bù được phí cluster zonal/Autopilot (xem dòng Zonal cluster). Bên AWS không có sự tách bạch tương ứng dưới cấp account; thay vào đó consolidated billing gom nhiều account về 1 payer account. |
| `gcloud` CLI | `aws` CLI + `eksctl` | GCP gộp thao tác cluster vào `gcloud container clusters`; các thao tác riêng cho EKS bên AWS thường cần thêm `eksctl` (hoặc Terraform) cùng với CLI `aws` gốc. |
| `gcloud services enable` (API enablement) | — (no per-service enablement) | GCP yêu cầu bật API của từng dịch vụ cho mỗi project; các dịch vụ AWS nhìn chung dùng được mà không cần bước bật riêng (vài tính năng, như Region opt-in, vẫn cần opt-in). |
| `gke-gcloud-auth-plugin` | `aws eks get-token` (via the `aws` CLI) | Plugin exec-credential của kubectl, đổi credential cloud thành token xác thực với cluster. `gcloud container clusters get-credentials` tương ứng với `aws eks update-kubeconfig`. |
| `netd` + GKE's default (non-Dataplane V2) datapath | `aws-node` (Amazon VPC CNI plugin) | DaemonSet networking riêng của từng cloud, chạy trên mỗi node. `netd` thiết lập pod networking của node — sinh CNI spec cho plugin PTP từ PodCIDR của node và quản lý việc chuyển hướng gói tin trên node; GKE chạy nó khi bật Workload Identity Federation for GKE (bật ở đây), intranode visibility hoặc dual-stack. `aws-node` làm nhiều hơn — nó còn cấp cho pod IP thật trong VPC lấy từ ENI. Lưu ý là không có gì trên cluster này thực thi NetworkPolicy: với cluster không dùng Dataplane V2, việc đó cần `--enable-network-policy`, cờ này cài Calico (`calico-node`) và mặc định tắt. Dataplane V2 của GKE (eBPF/Cilium, không dùng ở đây) mới là tương đồng gần của việc chạy Cilium trên EKS. |
| `konnectivity-agent`, `konnectivity-agent-autoscaler` | — (no EKS equivalent) | Tạo tunnel cho traffic từ control plane tới node (`kubectl exec`/`logs`, lời gọi webhook), cần có vì control plane của GKE chạy trong 1 project do Google quản lý. EKS thay vào đó đặt ENI của control plane ngay trong VPC của bạn, nên không có pod tương ứng. |
| `node-local-dns` (NodeLocal DNSCache), `kube-dns-autoscaler` | — (self-managed on EKS) | Các add-on Kubernetes gốc mà GKE cài và quản lý sẵn; trên EKS bạn tự deploy và tự chỉnh kích thước. |
| `kube-dns`, `kube-proxy`, `metrics-server` | CoreDNS, `kube-proxy`, metrics-server (EKS add-ons) | Các pod hệ thống còn lại mà GKE cài sẵn và tự nâng phiên bản giúp bạn. DNS mặc định của cluster GKE là `kube-dns` chứ không phải CoreDNS. EKS cũng cài sẵn CoreDNS và `kube-proxy` theo mặc định, nhưng dưới dạng add-on mà bạn tự nâng phiên bản; `metrics-server` thì cluster EKS không cài mặc định (`eksctl` bản mới thêm nó như 1 add-on của EKS; nếu không, đó là community add-on bạn tự thêm), còn GKE cài sẵn và tự chỉnh kích thước nó. |
| GKE Ingress load balancer (`l7-default-backend`) | AWS Load Balancer Controller (ALB) | Tạo HTTP(S) load balancer từ 1 Ingress. GKE tự chạy controller giúp bạn; trên EKS bạn tự cài. `l7-default-backend` (backend trả 404) không có pod tương đương bên ALB. |

**Ghi chú**: tài khoản GCP của dự án này đang ở dạng Free Trial, chặn hết mọi yêu cầu tăng quota
(bên AWS, Service Quotas cấp account, cho phép xin tăng qua support case). Cách né quota-cạn dùng
trong tài liệu này (vd dùng `pd-standard` thay vì `pd-balanced` cho `stateless-pool`, xem Bước 1)
là đặc thù của giới hạn Free Trial đó, không phải khác biệt chung giữa GCP và AWS.

---

## Chưa bao gồm trong tài liệu này (việc riêng, làm sau)

- CI pipeline (build + push image lên Artifact Registry, Workload Identity Federation để CI xác
  thực với GCP) — thiếu bước này thì `image.repository:image.tag` không bao giờ trỏ tới image
  thật.
- Tự động hoá CD/teardown (bật/tắt `stateful-pool` quanh mỗi lần deploy, tắt Postgres/Kafka có
  thứ tự, không đột ngột).
- Kiểm tra `k8s/data-layer/*.yaml` bằng kubeconform/CI.

</details>
