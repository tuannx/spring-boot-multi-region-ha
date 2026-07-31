variable "database_password" {
  description = "Ephemeral local password for both Floci RDS instances."
  type        = string
  sensitive   = true
  default     = "AppPass123!"
}

locals {
  common_tags = {
    Environment = "floci"
    Project     = "spring-boot-multi-region-ha"
  }
}

resource "aws_db_instance" "us" {
  provider = aws.us

  identifier          = "postgres-us"
  engine              = "postgres"
  engine_version      = "16"
  instance_class      = "db.t3.micro"
  allocated_storage   = 20
  db_name             = "appdb"
  username            = "appuser"
  password            = var.database_password
  skip_final_snapshot = true
  tags                = merge(local.common_tags, { Region = "us-east-1" })
}

resource "aws_db_instance" "eu" {
  provider = aws.eu

  identifier          = "postgres-eu"
  engine              = "postgres"
  engine_version      = "16"
  instance_class      = "db.t3.micro"
  allocated_storage   = 20
  db_name             = "appdb"
  username            = "appuser"
  password            = var.database_password
  skip_final_snapshot = true
  tags                = merge(local.common_tags, { Region = "eu-west-1" })
}
