output "cluster_arn" {
  value = aws_msk_cluster.this.arn
}

output "cluster_name" {
  value = aws_msk_cluster.this.cluster_name
}

output "bootstrap_brokers_sasl_iam" {
  description = "Comma-separated host:9098 list for KAFKA_BOOTSTRAP_SERVERS."
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_iam
}
