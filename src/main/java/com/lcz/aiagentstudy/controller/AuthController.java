package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.constant.SessionKeys;
import com.lcz.aiagentstudy.exception.UnauthorizedException;
import com.lcz.aiagentstudy.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
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
            throw new UnauthorizedException("用户名或密码错误");
//            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
//                    .body(Map.of("message","用户名或密码错误"));
        }
        session.setAttribute(SessionKeys.USER_ID, userId.get());
        return ResponseEntity.ok(Map.of("userId",userId.get().toString()));
    }

    // 登出：204 幂等 —— 登出本就没有失败态，重复调用/未登录调用都不该报错，一律 204 无 body。
    // 收 HttpServletRequest 而不是 HttpSession：不能用参数式 HttpSession，否则未登录的登出请求
    // 也会被 getSession(true) 建出一个空会话，反而"登出"出一个新 Cookie。
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request){
        HttpSession session = request.getSession(false);// 不自动创建session的方式下尝试获得session
        if (session != null) {
            session.invalidate();  // 会话销毁：userId 属性随之消失，JSESSIONID 随之作废
        }
        return ResponseEntity.noContent().build();  // 204
    }

}
