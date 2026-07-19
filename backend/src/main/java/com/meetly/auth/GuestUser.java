package com.meetly.auth;

import java.util.UUID;

public record GuestUser(String identity, String displayName, UUID meetingId) {}
