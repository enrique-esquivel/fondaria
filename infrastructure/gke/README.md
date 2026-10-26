# GKE load-test environment

A real multi-node, three-zone Kubernetes cluster for load tests, created in a brand-new Google
Cloud project and deleted with that project afterwards, or built inside a project you created and
keep (`GKE_PROJECT`, below). Why and how it is built: ADR-0036 in `docs/DESIGN-DECISIONS.md`.

Default size: 9 × `n2-standard-4` (36 vCPU, 144 GB), costing about **USD 2 an hour**. It deletes
itself **5 hours** after `make gke-up` starts, even if you do nothing.

## One-time setup (manual)

1. **Protect your Google account.** At myaccount.google.com → Security, add a passkey or security
   key under 2-Step Verification. Anyone who takes over the account can bypass every safeguard
   below.
2. **Create a paid billing account** at console.cloud.google.com/billing. If you are on the free
   trial, click **Upgrade** (the trial caps you at 8 vCPUs; the USD 300 credit carries over).
3. **Install the tools:**

   ```bash
   brew install --cask google-cloud-sdk
   ```

   ```bash
   gcloud components install gke-gcloud-auth-plugin
   ```

   `kubectl`, `linkerd`, `skaffold`, `openssl`, `python3` and a Temurin 21 JDK are already
   required for the kind cluster.
4. **Find your billing account id** (format `XXXXXX-XXXXXX-XXXXXX`):

   ```bash
   gcloud auth login
   ```

   ```bash
   gcloud billing accounts list
   ```

## Each run

All commands run from the repository root.

1. **Sign in** (`down` revoked the previous login):

   ```bash
   gcloud auth login
   ```

2. **Create the environment** (about 20 minutes):

   ```bash
   GKE_BILLING_ACCOUNT=XXXXXX-XXXXXX-XXXXXX make gke-up
   ```

   On a **new billing account**, start with the small profile: Google's default quota is 12 vCPUs
   in all regions and 250 GB of SSD per region, and it auto-denies increases until the account
   has some payment history. `GKE_PROFILE=small` fits that quota (3 nodes, three copies of every
   datastore, the same security), so it tests behaviour across machines but not capacity:

   ```bash
   GKE_PROFILE=small GKE_BILLING_ACCOUNT=XXXXXX-XXXXXX-XXXXXX make gke-up
   ```

   **To use a project you created and want to keep**, add its id. Nothing then deletes the
   project: the timer unlinks its billing instead, and `gke-down` deletes what `gke-up` built in
   it and unlinks billing too (the next `gke-up` links it again):

   ```bash
   GKE_PROJECT=your-project-id GKE_PROFILE=small GKE_BILLING_ACCOUNT=XXXXXX-XXXXXX-XXXXXX make gke-up
   ```

   `gke-up` picks its own placement: it tries the regions in `GKE_REGIONS` and the machine
   families in `GKE_MACHINE_FAMILIES` in order, checks every quota that applies (global CPUs,
   regional CPUs, the family's CPUs, SSD, standard disk), then creates and deletes one throwaway
   VM per zone, because Google only reports a zone out of capacity when a VM is actually
   created. When no region fits, it prints exactly which quota is short. Request it at the
   printed console link (filling the form there gets a human review; the API route is
   auto-denied on new accounts), then run the same command again. It resumes where it stopped.

3. **Deploy the platform** (builds amd64 images and pushes them to the project's registry):

   ```bash
   make gke-deploy
   ```

4. **Check placement and encryption.** Every node must be `Ready` across three zones, and pods
   `Running`:

   ```bash
   make gke-status
   ```

   Then confirm WireGuard is active (expect `Encryption: Wireguard`):

   ```bash
   kubectl -n kube-system exec ds/anetd -c cilium-agent -- cilium status | grep Encryption
   ```

5. **Open the tunnels** to Grafana (`http://localhost:30001`, password printed) and the APIs:

   ```bash
   make gke-tunnel
   ```

6. **Run the load**, in a shell pointed at the cluster:

   ```bash
   eval "$(infrastructure/gke/tunnel.sh --env)"
   ```

   ```bash
   make demo-seed
   ```

   ```bash
   make demo-onboard
   ```

7. **Screenshots:** with the tunnel open, ask Claude to capture the Grafana dashboards from its
   built-in browser, or take them yourself at `http://localhost:30001`.
8. **Destroy everything:**

   ```bash
   make gke-down
   ```

   It deletes the project, removes the kubeconfig entry and signs this machine out of gcloud
   (`ARGS=--keep-login` keeps the login). Afterwards, check the billing page shows no active
   project for this run.

## What protects you

- **A new project per run.** Only the APIs the cluster needs are enabled: no Vertex AI, no
  Gemini API, and no API keys are ever created.
- **A dead man's switch.** Cloud Scheduler deletes the whole project at the time limit, or, for a
  kept project, unlinks its billing, which stops every charge. It runs as an account that can do
  nothing else.
- **A budget alert** emails you (it does not stop spending) once the billing account passes
  25/50/90/100 % of the budget: USD 25 or MXN 500 by default, in your billing account's currency
  (`GKE_BUDGET_USD`, `GKE_BUDGET_MXN`, or `GKE_BUDGET_AMOUNT` for any other currency). It stays after the project is deleted, because
  it watches every project on the account.
- **Nothing is reachable from the internet.** Nodes have no public IPs, there is no SSH rule and
  no load balancer. The control plane only accepts your current IP, and still requires your
  Google sign-in.
- **Pods hold no Google credential.** The nodes' account can only pull images, and Workload
  Identity hides even that from pods.
- **Random credentials** are generated per machine into `infrastructure/k8s/overlays/gke/generated/`
  (gitignored); none of the local demo passwords are used.

## Settings

Everything is in `config.env`; override any value from the environment, e.g. `GKE_TTL_HOURS=3`.
The current run's project id, profile, placement and kube context are in `.state` (gitignored),
so later runs reuse them. Every `gke-up` re-arms the self-delete timer from that run's start.

**Demo state is per cluster.** `demo/out/` records the funds, organizations and NAV history
`demo-seed` created on one cluster. Move it aside before seeding a new cluster (kind or GKE):
reusing another cluster's file makes the seed reference funds that do not exist there.

While the environment exists, `gke-up` makes it kubectl's **current context**: commands without
`--context` go to GKE, not kind, until `make gke-down` switches back.
