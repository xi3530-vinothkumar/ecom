terraform {
  required_version = ">= 1.5.0"

  # Partial config: terraform init -backend-config=backend.hcl (see backend.hcl.example)
  backend "s3" {}

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.16"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
    postgresql = {
      source  = "cyrilgdn/postgresql"
      version = "~> 1.25"
    }
    kafka = {
      source  = "Mongey/kafka"
      version = "~> 0.13"
    }
    ec = {
      source  = "elastic/ec"
      version = "~> 0.12"
    }
  }
}
