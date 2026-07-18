package com.meetly.meeting;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class MeetingCodeGenerator {
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String newCode() {
        return segment(3) + "-" + segment(4) + "-" + segment(3);
    }

    private String segment(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        return sb.toString();
    }
}
