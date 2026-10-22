package main

# Deny containers that do not set readOnlyRootFilesystem: true
deny[msg] {
  input.kind == "Deployment"
  container := input.spec.template.spec.containers[_]
  not container.securityContext.readOnlyRootFilesystem == true
  msg := sprintf("Container '%v' in Deployment '%v' must have securityContext.readOnlyRootFilesystem: true", [container.name, input.metadata.name])
}

# Deny containers that allow privilege escalation
deny[msg] {
  input.kind == "Deployment"
  container := input.spec.template.spec.containers[_]
  not container.securityContext.allowPrivilegeEscalation == false
  msg := sprintf("Container '%v' in Deployment '%v' must have securityContext.allowPrivilegeEscalation: false", [container.name, input.metadata.name])
}

# Deny containers that do not drop ALL capabilities
deny[msg] {
  input.kind == "Deployment"
  container := input.spec.template.spec.containers[_]
  not container.securityContext.capabilities.drop[_] == "ALL"
  msg := sprintf("Container '%v' in Deployment '%v' must drop ALL capabilities", [container.name, input.metadata.name])
}

# Deny pods that run as root
deny[msg] {
  input.kind == "Deployment"
  pod := input.spec.template.spec
  not pod.securityContext.runAsNonRoot == true
  msg := sprintf("Pod '%v' must have securityContext.runAsNonRoot: true", [input.metadata.name])
}

# Deny pods without RuntimeDefault seccomp profile. The exceptions, by name:
# every workload that links the TigerBeetle client (replica, connector, or the
# Java client library) needs io_uring, which the runtime's default profile
# blocks (see base/tigerbeetle-cdc/deployment.yaml).
io_uring_workloads := {"tigerbeetle-cdc", "contribution-api", "balance-api", "valuation-api", "allotment-job", "eod-job"}

deny[msg] {
  input.kind == "Deployment"
  not io_uring_workloads[input.metadata.name]
  pod := input.spec.template.spec
  not pod.securityContext.seccompProfile.type == "RuntimeDefault"
  msg := sprintf("Pod '%v' must have securityContext.seccompProfile.type: RuntimeDefault", [input.metadata.name])
}

deny[msg] {
  input.kind == "Deployment"
  io_uring_workloads[input.metadata.name]
  pod := input.spec.template.spec
  not pod.securityContext.seccompProfile.type == "Unconfined"
  msg := sprintf("Pod '%v' is an io_uring workload and must declare seccompProfile.type: Unconfined explicitly", [input.metadata.name])
}

# Deny containers without explicit resource requests and limits
deny[msg] {
  input.kind == "Deployment"
  container := input.spec.template.spec.containers[_]
  not container.resources.requests.cpu
  msg := sprintf("Container '%v' in Deployment '%v' must specify cpu requests", [container.name, input.metadata.name])
}

deny[msg] {
  input.kind == "Deployment"
  container := input.spec.template.spec.containers[_]
  not container.resources.requests.memory
  msg := sprintf("Container '%v' in Deployment '%v' must specify memory requests", [container.name, input.metadata.name])
}

deny[msg] {
  input.kind == "Deployment"
  container := input.spec.template.spec.containers[_]
  not container.resources.limits.cpu
  msg := sprintf("Container '%v' in Deployment '%v' must specify cpu limits", [container.name, input.metadata.name])
}

deny[msg] {
  input.kind == "Deployment"
  container := input.spec.template.spec.containers[_]
  not container.resources.limits.memory
  msg := sprintf("Container '%v' in Deployment '%v' must specify memory limits", [container.name, input.metadata.name])
}

# Deny images that are not pinned by digest (@sha256:)
deny[msg] {
  input.kind == "Deployment"
  container := input.spec.template.spec.containers[_]
  not contains(container.image, "@sha256:")
  msg := sprintf("Container '%v' in Deployment '%v' must reference image by digest (@sha256:), got '%v'", [container.name, input.metadata.name, container.image])
}
