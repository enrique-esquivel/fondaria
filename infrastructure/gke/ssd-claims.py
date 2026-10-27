"""Sums the disk a rendered GKE overlay's persistent volumes claim, in GB.

Reads `kubectl kustomize infrastructure/k8s/overlays/gke[-small]` on stdin and
prints two numbers: GB on SSD (the overlay's pd-ssd `tigerbeetle-nvme` class,
counted against SSD_TOTAL_GB) and GB on GKE's pd-standard `standard` class
(counted against DISKS_TOTAL_GB). up.sh must find both free before it creates
the cluster.
"""

import math
import re
import sys

import yaml

UNITS = {"Ki": 2**-20, "Mi": 2**-10, "Gi": 1, "Ti": 2**10, "M": 0.931 / 1000, "G": 0.931, "T": 931}


def gib(quantity):
    number, unit = re.fullmatch(r"([0-9.]+)([A-Za-z]*)", str(quantity)).groups()
    return float(number) * UNITS[unit] if unit else float(number) / 2**30


SSD_CLASSES = {"tigerbeetle-nvme", "premium-rwo", "standard-rwo"}
totals = {"ssd": 0.0, "std": 0.0}


def add(storage_class, quantity, copies):
    totals["ssd" if storage_class in SSD_CLASSES else "std"] += gib(quantity) * copies


for doc in yaml.safe_load_all(sys.stdin):
    if not doc:
        continue
    kind, spec = doc.get("kind"), doc.get("spec") or {}
    if kind == "StatefulSet":
        for claim in spec.get("volumeClaimTemplates", []):
            add(claim["spec"].get("storageClassName"), claim["spec"]["resources"]["requests"]["storage"],
                spec.get("replicas", 1))
    elif kind == "Cluster":  # CloudNativePG: one data and one WAL volume per instance
        for key in ("storage", "walStorage"):
            if key in spec:
                add(spec[key].get("storageClass"), spec[key]["size"], spec.get("instances", 1))
    elif kind == "RabbitmqCluster":
        persistence = spec.get("persistence") or {}
        add(persistence.get("storageClassName"), persistence.get("storage", "10Gi"), spec.get("replicas", 1))

# Disks are billed and counted in GB; claims are in GiB.
print(math.ceil(totals["ssd"] * 1.074), math.ceil(totals["std"] * 1.074))
