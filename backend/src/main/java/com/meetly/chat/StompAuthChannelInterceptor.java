package com.meetly.chat;

import com.meetly.auth.GuestUser;
import com.meetly.auth.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {
    private static final Pattern CHAT_TOPIC =
            Pattern.compile("^/topic/meetings/([0-9a-fA-F-]{36})/chat$");

    private final JwtService jwtService;
    private final ChatAccessGuard accessGuard;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                throw new IllegalArgumentException("Missing Authorization header on CONNECT");
            }
            Object principal = jwtService.parsePrincipal(header.substring(7));
            String role = principal instanceof GuestUser ? "ROLE_GUEST" : "ROLE_USER";
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority(role))));
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            Matcher m = destination != null ? CHAT_TOPIC.matcher(destination) : null;
            if (m == null || !m.matches()) {
                throw new IllegalArgumentException("Invalid destination: " + destination);
            }
            var auth = (UsernamePasswordAuthenticationToken) accessor.getUser();
            if (auth == null) throw new IllegalArgumentException("Not authenticated on SUBSCRIBE");
            accessGuard.check(auth.getPrincipal(), UUID.fromString(m.group(1)));
        }
        return message;
    }
}
