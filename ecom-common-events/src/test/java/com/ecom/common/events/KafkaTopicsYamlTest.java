package com.ecom.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KafkaTopicsYamlTest {

    @Test
    void yamlMatchesEventTopicsConstants() {
        var names = KafkaTopicDefinitions.load().topics().stream()
                .map(KafkaTopicDefinitions.TopicDefinition::name)
                .toList();
        assertThat(names).containsExactlyInAnyOrderElementsOf(EventTopics.ALL);
    }

    @Test
    void topicNamesFollowConvention() {
        assertThat(EventTopics.ALL).allMatch(t -> t.matches("ecom\\.[a-z]+\\.[a-z-]+\\.v\\d+"));
    }
}
