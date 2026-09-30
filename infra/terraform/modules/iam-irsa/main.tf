# One IAM role per service ServiceAccount (namespace "ecom"), least privilege:
#  - Kafka users: MSK IAM connect + read/write on topics ecom.* + their own consumer groups <svc>-service-*
#  - catalog: S3 Get/Put on the images bucket (pre-signed uploads)
#  - gateway: no AWS permissions (role exists so the SA annotation is uniform)
# Secrets are NOT read by service roles: External Secrets Operator has its own role (modules/eks).

data "aws_partition" "current" {}

locals {
  partition = data.aws_partition.current.partition

  service_accounts = {
    "gateway"              = { kafka = false }
    "user-service"         = { kafka = true }
    "catalog-service"      = { kafka = true }
    "inventory-service"    = { kafka = true }
    "cart-service"         = { kafka = true }
    "order-service"        = { kafka = true }
    "payment-service"      = { kafka = true }
    "notification-service" = { kafka = true }
    "search-service"       = { kafka = true }
  }

  # arn:aws:kafka:<region>:<acct>:cluster/<name>/<uuid>  ->  topic/<name>/<uuid>/..., group/<name>/<uuid>/...
  msk_topic_prefix = replace(var.msk_cluster_arn, ":cluster/", ":topic/")
  msk_group_prefix = replace(var.msk_cluster_arn, ":cluster/", ":group/")
}

data "aws_iam_policy_document" "trust" {
  for_each = local.service_accounts
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_host}:sub"
      values   = ["system:serviceaccount:${var.namespace}:${each.key}"]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "sa" {
  for_each             = local.service_accounts
  name                 = "ecom-${var.env}-${each.key}"
  assume_role_policy   = data.aws_iam_policy_document.trust[each.key].json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "kafka" {
  for_each = { for k, v in local.service_accounts : k => v if v.kafka }

  statement {
    sid       = "Connect"
    actions   = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster", "kafka-cluster:WriteDataIdempotently"]
    resources = [var.msk_cluster_arn]
  }

  statement {
    sid = "Topics"
    actions = [
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:DescribeTopicDynamicConfiguration",
      "kafka-cluster:ReadData",
      "kafka-cluster:WriteData",
    ]
    resources = ["${local.msk_topic_prefix}/ecom.*"]
  }

  statement {
    sid       = "OwnConsumerGroups"
    actions   = ["kafka-cluster:AlterGroup", "kafka-cluster:DescribeGroup"]
    resources = ["${local.msk_group_prefix}/${each.key}-*"]
  }
}

resource "aws_iam_role_policy" "kafka" {
  for_each = data.aws_iam_policy_document.kafka
  name     = "msk-iam"
  role     = aws_iam_role.sa[each.key].id
  policy   = each.value.json
}

resource "aws_iam_role_policy" "catalog_images" {
  name = "images-bucket"
  role = aws_iam_role.sa["catalog-service"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
      Resource = "${var.images_bucket_arn}/*"
    }]
  })
}

