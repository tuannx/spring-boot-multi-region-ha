output "database_us_port" {
  value = aws_db_instance.us.port
}

output "database_eu_port" {
  value = aws_db_instance.eu.port
}

output "rds_identifiers" {
  value = {
    us = aws_db_instance.us.identifier
    eu = aws_db_instance.eu.identifier
  }
}
