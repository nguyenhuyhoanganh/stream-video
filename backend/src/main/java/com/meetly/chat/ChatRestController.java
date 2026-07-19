package com.meetly.chat;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.chat.ChatDtos.ChatMessageDto;
import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/messages")
@RequiredArgsConstructor
public class ChatRestController {
    private final ChatMessageRepository chatMessages;
    private final ChatService chatService;
    private final ChatAccessGuard accessGuard;

    @GetMapping
    public List<ChatMessageDto> history(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID meetingId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant after,
            @RequestParam(defaultValue = "50") int limit) {
        accessGuard.check(principal, meetingId);   // cùng luật với SUBSCRIBE/gửi tin
        List<ChatMessage> page;
        if (after != null) {
            page = chatMessages.findByMeetingIdAndCreatedAtAfterOrderByCreatedAtAsc(meetingId, after);
        } else {
            page = chatMessages.findByMeetingIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                    meetingId, before != null ? before : Instant.now(),
                    PageRequest.of(0, Math.min(limit, 200)));
        }
        return page.stream()
                .filter(m -> m.getDeletedAt() == null)
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .map(ChatMessageDto::from)
                .toList();
    }

    @DeleteMapping("/{msgId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Object principal,
                                       @PathVariable UUID meetingId, @PathVariable UUID msgId) {
        if (!(principal instanceof AuthenticatedUser user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_MEETING_HOST,
                    "Chỉ host mới được xóa tin nhắn");
        }
        chatService.deleteMessage(meetingId, msgId, user.id());
        return ResponseEntity.noContent().build();
    }
}
