#!/bin/sh
# Deletes the local demo cluster and everything in it. There is nothing to
# preserve: the cluster is disposable by design, and `up.sh` recreates it.
set -eu
kind delete cluster --name fondaria
