# GitHub Actions OIDC federation (no long-lived AWS keys):
#  - ecom-<env>-github-deploy          : docker-publish.yml / deploy.yml (ECR push + EKS deploy)
#  - ecom-<env>-github-terraform-plan  : infra.yml plan on PRs (ReadOnlyAccess + state backend)
#  - ecom-<env>-github-terraform       : infra.yml apply on main behind an Environment approval (AdministratorAccess)

data "aws_partition" "current" {}

locals {
  partition    = data.aws_partition.current.partition
  host         = "token.actions.githubusercontent.com"
  provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn

  main_and_envs = concat(
    ["repo:${var.github_repo}:ref:refs/heads/main"],
    [for e in var.github_environments : "repo:${var.github_repo}:environment:${e}"],
  )
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.create_oidc_provider ? 1 : 0
  url            = "https://${local.host}"
  client_id_list = ["sts.amazonaws.com"]
  # GitHub's thumbprints; AWS no longer validates them for this issuer but the field is required.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1", "1c58a3a8518e8759bf075b76b750d4f2df264fcd"]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 0 : 1
  url   = "https://${local.host}"
}

data "aws_iam_policy_document" "trust" {
  for_each = {
    deploy         = local.main_and_envs
    terraform      = local.main_and_envs
    terraform-plan = ["repo:${var.github_repo}:pull_request", "repo:${var.github_repo}:ref:refs/heads/main"]
  }
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.host}:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "${local.host}:sub"
      values   = each.value
    }
  }
}

# ---------------------------------------------------------------- deploy role
resource "aws_iam_role" "deploy" {
  name               = "ecom-${var.env}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.trust["deploy"].json
}

resource "aws_iam_role_policy" "deploy" {
  name = "ecr-push-eks-describe"
  role = aws_iam_role.deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:CompleteLayerUpload",
          "ecr:DescribeImages",
          "ecr:DescribeRepositories",
          "ecr:GetDownloadUrlForLayer",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart",
        ]
        Resource = var.ecr_repository_arns
      },
      {
        Effect   = "Allow"
        Action   = ["eks:DescribeCluster"]
        Resource = var.eks_cluster_arn
      },
    ]
  })
}

# kubectl apply -k creates cluster-scoped objects (Namespaces, ClusterSecretStore), so namespace-scoped
# edit is not enough; cluster admin via access entry. Tighten with a custom RBAC group post-MVP.
resource "aws_eks_access_entry" "deploy" {
  cluster_name  = var.eks_cluster_name
  principal_arn = aws_iam_role.deploy.arn
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "deploy" {
  cluster_name  = var.eks_cluster_name
  principal_arn = aws_iam_role.deploy.arn
  policy_arn    = "arn:${local.partition}:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"
  access_scope {
    type = "cluster"
  }
  depends_on = [aws_eks_access_entry.deploy]
}

# ---------------------------------------------------------------- terraform roles
resource "aws_iam_role" "terraform" {
  name               = "ecom-${var.env}-github-terraform"
  assume_role_policy = data.aws_iam_policy_document.trust["terraform"].json
}

resource "aws_iam_role_policy_attachment" "terraform" {
  role       = aws_iam_role.terraform.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/AdministratorAccess"
}

resource "aws_iam_role" "terraform_plan" {
  name               = "ecom-${var.env}-github-terraform-plan"
  assume_role_policy = data.aws_iam_policy_document.trust["terraform-plan"].json
}

resource "aws_iam_role_policy_attachment" "terraform_plan" {
  role       = aws_iam_role.terraform_plan.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/ReadOnlyAccess"
}

resource "aws_iam_role_policy" "terraform_plan_state" {
  name = "state-backend"
  role = aws_iam_role.terraform_plan.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "arn:${local.partition}:s3:::${var.state_bucket_name}/ecom/${var.env}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = "arn:${local.partition}:s3:::${var.state_bucket_name}"
      },
      {
        Effect   = "Allow"
        Action   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem"]
        Resource = "arn:${local.partition}:dynamodb:*:*:table/${var.lock_table_name}"
      },
    ]
  })
}
