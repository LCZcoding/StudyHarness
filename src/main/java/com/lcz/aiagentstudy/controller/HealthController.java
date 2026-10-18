package com.lcz.aiagentstudy.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController//“加强版”控制器。它 = @Controller + @ResponseBody。默认情况下，它返回的是数据（JSON/XML）。
@RequestMapping("/health")
public class HealthController {

    @GetMapping
    public String healthCheck(){
        return "ok";
    }
}
