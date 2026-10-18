package com.lcz.aiagentstudy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
public class Password {
    // 将密码变成hash
    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    public void password(){
        String rawPassword = "testhash";
        String passwordHash = passwordEncoder.encode(rawPassword);
        System.out.println(passwordHash);
    }
}
