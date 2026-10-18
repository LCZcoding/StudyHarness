package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.UserRow;
import com.lcz.aiagentstudy.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;


    public Optional<UUID> login(String username, String rawPassword){
        return userRepository
                .findByUsername(username)
                .filter(u -> passwordEncoder.matches(rawPassword, u.passwordHash()))// 过滤密码不对的
                .map(UserRow::userId);// userRow转换成userId（都还是Optional）
    }
}
