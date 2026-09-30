# All topics + their .dlt twins from the canonical YAML
# (ecom-common-events/src/main/resources/kafka-topics.yaml — also read by the starter and KafkaTopicsYamlTest).

terraform {
  required_providers {
    kafka = {
      source = "Mongey/kafka"
    }
  }
}

locals {
  spec            = yamldecode(file(var.topics_file))
  default_parts   = try(local.spec.defaults.partitions, 6)
  default_config  = try(local.spec.defaults.config, {})
  enforced_config = { "min.insync.replicas" = tostring(var.min_insync_replicas) }

  main_topics = {
    for t in local.spec.topics : t.name => {
      partitions = try(t.partitions, local.default_parts)
      config     = merge(local.default_config, try(t.config, {}), local.enforced_config)
    }
  }

  dlt_topics = {
    for name, t in local.main_topics : "${name}.dlt" => {
      partitions = t.partitions # same partition count: DLT recoverer keeps the source partition
      config     = merge(t.config, { "retention.ms" = tostring(var.dlt_retention_ms) })
    }
  }

  topics = merge(local.main_topics, local.dlt_topics)
}

resource "kafka_topic" "this" {
  for_each           = local.topics
  name               = each.key
  partitions         = each.value.partitions
  replication_factor = var.replication_factor
  config             = { for k, v in each.value.config : k => tostring(v) }
}
