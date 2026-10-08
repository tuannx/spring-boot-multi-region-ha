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
# valkey/valkey:8). These replication groups prove the AWS control-plane
# path; the runnable primary/replica data plane for acceptance lives in
# cases/elasticache-global/docker-compose.yml.
#
# aws_elasticache_replication_group is the Valkey resource: the AWS
# provider validates aws_elasticache_cluster.engine as memcached/redis
# only (first CI run of this module failed on exactly that).
resource "aws_elasticache_replication_group" "valkey_us" {
  provider = aws.us

  replication_group_id       = "valkey-us"
  description                = "Valkey primary region (Floci control-plane proof)"
  engine                     = "valkey"
  engine_version             = "8.0"
  node_type                  = "cache.t3.micro"
  num_cache_clusters         = 1
  port                       = 6379
  parameter_group_name       = "default.valkey8"
  automatic_failover_enabled = false
  tags                       = merge(local.common_tags, { Region = "us-east-1", Role = "primary" })
}

resource "aws_elasticache_replication_group" "valkey_eu" {
  provider = aws.eu

  replication_group_id       = "valkey-eu"
  description                = "Valkey replica region (Floci control-plane proof)"
  engine                     = "valkey"
  engine_version             = "8.0"
  node_type                  = "cache.t3.micro"
  num_cache_clusters         = 1
  port                       = 6379
  parameter_group_name       = "default.valkey8"
  automatic_failover_enabled = false
  tags                       = merge(local.common_tags, { Region = "eu-west-1", Role = "replica" })
}

output "valkey_us_replication_group_id" {
  value = aws_elasticache_replication_group.valkey_us.id
}

output "valkey_eu_replication_group_id" {
  value = aws_elasticache_replication_group.valkey_eu.id
}
