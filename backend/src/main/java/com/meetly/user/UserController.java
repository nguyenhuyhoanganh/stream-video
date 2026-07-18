package com.meetly.user;

import com.meetly.auth.AuthDtos.UserDto;
import com.meetly.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserRepository users;

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthenticatedUser principal) {
        User u = users.findById(principal.id()).orElseThrow();
        return new UserDto(u.getId(), u.getEmail(), u.getFullName());
    }
}
