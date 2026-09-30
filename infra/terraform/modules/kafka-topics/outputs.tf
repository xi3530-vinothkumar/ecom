output "topic_names" {
  value = sort(keys(kafka_topic.this))
}
