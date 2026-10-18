package com.lcz.aiagentstudy.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DateTimeToolTest {

    @Test
    void getCurrentTime() {
        DateTimeTool dateTimeTool = new DateTimeTool();
        String currentTime = dateTimeTool.getCurrentTime();
        System.out.println(currentTime);
    }
}