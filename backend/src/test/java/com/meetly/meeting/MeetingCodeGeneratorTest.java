package com.meetly.meeting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingCodeGeneratorTest {
    @Test
    void formatIsThreeFourThreeLowercase() {
        String code = new MeetingCodeGenerator().newCode();
        assertThat(code).matches("[a-z]{3}-[a-z]{4}-[a-z]{3}");
    }

    @Test
    void codesAreRandom() {
        MeetingCodeGenerator gen = new MeetingCodeGenerator();
        assertThat(gen.newCode()).isNotEqualTo(gen.newCode());
    }
}
