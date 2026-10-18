package com.lcz.aiagentstudy.tools;

public interface FileConstant {
    /**
     * 文件保存目录
     */
    // 获取 JVM 的系统属性 user.dir，它表示当前 Java 进程的工作目录，也就是启动这个程序时所在的目录。
    // 例如你在 D:\code\Java\ai-agent-study 下运行 jar，这个值就是 D:\code\Java\ai-agent-study。
    String FILE_SAVE_DIR = System.getProperty("user.dir") + "/tmp";
}
