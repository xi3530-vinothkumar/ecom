output "deployment_id" {
  value = ec_deployment.this.id
}

output "elasticsearch_url" {
  description = "ELASTICSEARCH_URL for the search-service (PrivateLink hostname, HTTPS 443)."
  value       = "https://${ec_deployment.this.alias}.es.${local.privatelink_domain}"
}
