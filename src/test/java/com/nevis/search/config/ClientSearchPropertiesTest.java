package com.nevis.search.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientSearchPropertiesTest {

    @Test
    void acceptsThresholdInsideOpenClosedUnitInterval() {
        assertThat(new ClientSearchProperties(0.50).trigramThreshold()).isEqualTo(0.50);
        assertThat(new ClientSearchProperties(1.0).trigramThreshold()).isEqualTo(1.0);
    }

    @Test
    void rejectsThresholdOutsideOpenClosedUnitInterval() {
        assertThatThrownBy(() -> new ClientSearchProperties(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientSearchProperties(-0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientSearchProperties(1.01))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
