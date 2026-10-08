terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

variable "floci_us_endpoint" {
  description = "Floci AWS-compatible management endpoint for us-east-1."
  type        = string
  default     = "http://localhost:4566"
}

variable "floci_eu_endpoint" {
  description = "Floci AWS-compatible management endpoint for eu-west-1."
  type        = string
  default     = "http://localhost:4567"
}

locals {
  provider_defaults = {
    access_key = "test"
    secret_key = "test"
  }
  common_tags = {
    Environment = "floci"
    Project     = "spring-boot-multi-region-ha"
    Case        = "elasticache-global"
  }
}

provider "aws" {
  alias      = "us"
  region     = "us-east-1"
  access_key = local.provider_defaults.access_key
  secret_key = local.provider_defaults.secret_key

  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  endpoints {
    elasticache = var.floci_us_endpoint
  }
}

provider "aws" {
  alias      = "eu"
  region     = "eu-west-1"
  access_key = local.provider_defaults.access_key
  secret_key = local.provider_defaults.secret_key

  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  endpoints {
    elasticache = var.floci_eu_endpoint
  }
}

# Floci backs ElastiCache with a real Valkey container (default image
# valkey/valkey:8). These clusters prove the AWS control-plane path; the
# runnable primary/replica data plane for acceptance lives in
# cases/elasticache-global/docker-compose.yml.
resource "aws_elasticache_cluster" "valkey_us" {
  provider = aws.us

  cluster_id           = "valkey-us"
  engine               = "valkey"
  engine_version       = "8.0"
  node_type            = "cache.t3.micro"
  num_cache_nodes      = 1
  port                 = 6379
  parameter_group_name = "default.valkey8"
  tags                 = merge(local.common_tags, { Region = "us-east-1", Role = "primary" })
}

resource "aws_elasticache_cluster" "valkey_eu" {
  provider = aws.eu

  cluster_id           = "valkey-eu"
  engine               = "valkey"
  engine_version       = "8.0"
  node_type            = "cache.t3.micro"
  num_cache_nodes      = 1
  port                 = 6379
  parameter_group_name = "default.valkey8"
  tags                 = merge(local.common_tags, { Region = "eu-west-1", Role = "replica" })
}

output "valkey_us_cluster_id" {
  value = aws_elasticache_cluster.valkey_us.id
}

output "valkey_eu_cluster_id" {
  value = aws_elasticache_cluster.valkey_eu.id
}
