output "bucket_name" {
  value = aws_s3_bucket.images.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.images.arn
}

output "cloudfront_domain_name" {
  description = "Use https://<this> (or an alias) as IMAGES_CDN_BASE_URL."
  value       = aws_cloudfront_distribution.images.domain_name
}
