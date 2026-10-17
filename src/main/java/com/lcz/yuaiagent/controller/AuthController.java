package com.lcz.yuaiagent.controller;

import com.lcz.yuaiagent.constant.SessionKeys;
import com.lcz.yuaiagent.service.AuthService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // 属于dto类 ，只服务 /auth/login 一个入口，所以就近定义，不单独建文件
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpSession session){
        Optional<UUID> userId = authService.login(req.username(), req.password());

        if(userId.isEmpty()){
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message","用户名或密码错误"));
        }
        session.setAttribute(SessionKeys.USER_ID, userId.get());
        return ResponseEntity.ok(Map.of("userId",userId.get().toString()));
    }

}
