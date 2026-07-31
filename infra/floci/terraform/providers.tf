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
    mq  = var.floci_us_endpoint
    rds = var.floci_us_endpoint
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
    mq  = var.floci_eu_endpoint
    rds = var.floci_eu_endpoint
  }
}
