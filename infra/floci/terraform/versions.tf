terraform {
  required_version = ">= 1.8.0"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # Floci 2.0.1 supports the dbi-resource-id refresh filter used by AWS
      # provider 6.x. Keep the upper bound aligned with the locked release.
      version = ">= 5.0, < 6.63.1"
    }
  }
}
