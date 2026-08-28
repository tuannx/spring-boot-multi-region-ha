terraform {
  required_version = ">= 1.8.0"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # Floci 1.5.34 does not yet support the dbi-resource-id refresh filter
      # used by AWS provider 6.x. Track the upstream fix at floci-io/floci#1951.
      version = ">= 5.0, < 6.62"
    }
  }
}
