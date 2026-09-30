# Helm-installed platform add-ons, each with its own IRSA role.

locals {
  addon_sa = {
    lbc              = { namespace = "kube-system", name = "aws-load-balancer-controller" }
    external_secrets = { namespace = "external-secrets", name = "external-secrets" }
    autoscaler       = { namespace = "kube-system", name = "cluster-autoscaler" }
  }
}

data "aws_iam_policy_document" "addon_trust" {
  for_each = local.addon_sa
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.this.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_provider_host}:sub"
      values   = ["system:serviceaccount:${each.value.namespace}:${each.value.name}"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_provider_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

# ---------------------------------------------------------------- AWS Load Balancer Controller
resource "aws_iam_role" "lbc" {
  name               = "${var.cluster_name}-aws-load-balancer-controller"
  assume_role_policy = data.aws_iam_policy_document.addon_trust["lbc"].json
}

resource "aws_iam_role_policy" "lbc" {
  name   = "aws-load-balancer-controller"
  role   = aws_iam_role.lbc.id
  policy = file("${path.module}/policies/aws-load-balancer-controller.json")
}

resource "helm_release" "lbc" {
  name       = "aws-load-balancer-controller"
  namespace  = "kube-system"
  repository = "https://aws.github.io/eks-charts"
  chart      = "aws-load-balancer-controller"
  version    = var.chart_versions.aws_load_balancer_controller

  values = [yamlencode({
    clusterName  = aws_eks_cluster.this.name
    region       = local.region
    vpcId        = var.vpc_id
    replicaCount = 2
    serviceAccount = {
      name        = local.addon_sa.lbc.name
      annotations = { "eks.amazonaws.com/role-arn" = aws_iam_role.lbc.arn }
    }
  })]

  depends_on = [aws_eks_node_group.default, aws_eks_addon.vpc_cni]
}

# ---------------------------------------------------------------- External Secrets Operator
resource "aws_iam_role" "external_secrets" {
  # Name is part of the contract with infra/k8s (ClusterSecretStore "aws-secrets-manager" uses this SA's IRSA).
  name               = "ecom-${var.env}-external-secrets"
  assume_role_policy = data.aws_iam_policy_document.addon_trust["external_secrets"].json
}

resource "aws_iam_role_policy" "external_secrets" {
  name = "read-ecom-secrets"
  role = aws_iam_role.external_secrets.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret",
        "secretsmanager:ListSecretVersionIds",
      ]
      Resource = "arn:${local.partition}:secretsmanager:${local.region}:${local.account_id}:secret:ecom/${var.env}/*"
    }]
  })
}

resource "helm_release" "external_secrets" {
  name             = "external-secrets"
  namespace        = local.addon_sa.external_secrets.namespace
  create_namespace = true
  repository       = "https://charts.external-secrets.io"
  chart            = "external-secrets"
  version          = var.chart_versions.external_secrets

  values = [yamlencode({
    installCRDs = true
    serviceAccount = {
      name        = local.addon_sa.external_secrets.name
      annotations = { "eks.amazonaws.com/role-arn" = aws_iam_role.external_secrets.arn }
    }
  })]

  depends_on = [aws_eks_node_group.default]
}

# ---------------------------------------------------------------- metrics-server (HPA)
resource "helm_release" "metrics_server" {
  name       = "metrics-server"
  namespace  = "kube-system"
  repository = "https://kubernetes-sigs.github.io/metrics-server/"
  chart      = "metrics-server"
  version    = var.chart_versions.metrics_server

  depends_on = [aws_eks_node_group.default]
}

# ---------------------------------------------------------------- cluster-autoscaler
resource "aws_iam_role" "autoscaler" {
  name               = "${var.cluster_name}-cluster-autoscaler"
  assume_role_policy = data.aws_iam_policy_document.addon_trust["autoscaler"].json
}

resource "aws_iam_role_policy" "autoscaler" {
  name = "cluster-autoscaler"
  role = aws_iam_role.autoscaler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "autoscaling:DescribeAutoScalingGroups",
          "autoscaling:DescribeAutoScalingInstances",
          "autoscaling:DescribeLaunchConfigurations",
          "autoscaling:DescribeScalingActivities",
          "autoscaling:DescribeTags",
          "ec2:DescribeImages",
          "ec2:DescribeInstanceTypes",
          "ec2:DescribeLaunchTemplateVersions",
          "ec2:GetInstanceTypesFromInstanceRequirements",
          "eks:DescribeNodegroup",
        ]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = ["autoscaling:SetDesiredCapacity", "autoscaling:TerminateInstanceInAutoScalingGroup"]
        Resource = "*"
        Condition = {
          StringEquals = { "aws:ResourceTag/k8s.io/cluster-autoscaler/${var.cluster_name}" = "owned" }
        }
      },
    ]
  })
}

resource "helm_release" "cluster_autoscaler" {
  name       = "cluster-autoscaler"
  namespace  = "kube-system"
  repository = "https://kubernetes.github.io/autoscaler"
  chart      = "cluster-autoscaler"
  version    = var.chart_versions.cluster_autoscaler

  values = [yamlencode({
    awsRegion     = local.region
    autoDiscovery = { clusterName = aws_eks_cluster.this.name }
    extraArgs = {
      "balance-similar-node-groups"   = true
      "skip-nodes-with-local-storage" = false
      "expander"                      = "least-waste"
    }
    rbac = {
      serviceAccount = {
        name        = local.addon_sa.autoscaler.name
        annotations = { "eks.amazonaws.com/role-arn" = aws_iam_role.autoscaler.arn }
      }
    }
  })]

  depends_on = [aws_eks_node_group.default]
}
