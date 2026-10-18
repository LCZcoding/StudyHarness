package com.lcz.aiagentstudy.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FileBasedChatMemory implements ChatMemoryRepository {

    private final String BASE_DIR;

    private static final Kryo KRYO = new Kryo();

    static {
        KRYO.setRegistrationRequired(false);//不需要一个一个手动注册
        //设置实例化策略
        KRYO.setInstantiatorStrategy(new StdInstantiatorStrategy());
    }

    /**
     * 构造函数
     * @param dir 基础目录
     */
    public FileBasedChatMemory(String dir) {
        this.BASE_DIR = dir;
        File baseDir = new File(dir);
        if(!baseDir.exists()){
            baseDir.mkdirs();
        }

    }

    // 1. 替换 add 方法 → 改为 saveAll
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        File file = getConversationFile(conversationId);
        try (Output output = new Output(new FileOutputStream(file))) {
            KRYO.writeObject(output, messages);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 2. 替换 get 方法 → 改为 findByConversationId
    @Override
    public List<Message> findByConversationId(String conversationId) {
        return getOrCreateConversation(conversationId);
    }

    // 3. 替换 clear 方法 → 改为 deleteByConversationId
    @Override
    public void deleteByConversationId(String conversationId) {
        File file = getConversationFile(conversationId);
        if (file.exists()) {
            file.delete();
        }
    }

    // 4. 新增 findConversationIds 方法（接口要求的）
    @Override
    public List<String> findConversationIds() {
        File baseDir = new File(BASE_DIR);
        if (!baseDir.exists()) {
            return List.of();
        }
        // 过滤出 .kryo 文件，提取 conversationId（文件名去掉 .kryo 后缀）
        return Arrays.stream(baseDir.listFiles((dir, name) -> name.endsWith(".kryo")))
                .map(File::getName)
                .map(name -> name.replace(".kryo", ""))
                .toList();
    }
    /**
     * 根据对话ID保存对话文件
     * @param conversationId 对话ID
     * @param messages 对话消息的列表
     */
    private void saveConversation(String conversationId, List<Message> messages){
        File file = getConversationFile(conversationId);
        try(Output output = new Output(new FileOutputStream(file))){
            KRYO.writeObject(output, messages);
        } catch (IOException e){
            e.printStackTrace();
        }
    }

    /**
     * 获取或创建对话消息的列表
     * @param conversationId 对话ID
     * @return 对话消息的列表
     */
    private List<Message> getOrCreateConversation(String conversationId){
        File file = getConversationFile(conversationId);
        List<Message> messages = new ArrayList<>();
        if(file.exists()){
            try(Input input = new Input(new FileInputStream(file))){
                messages = KRYO.readObject(input, ArrayList.class);
            } catch (IOException e){
                e.printStackTrace();
            }
        }
        return messages;
    }

    /**
     * 根据对话ID获取或创建对话文件
     * @param conversationId 对话ID
     * @return 对话文件
     */
    private File getConversationFile(String conversationId){
        return new File(BASE_DIR, conversationId + ".kryo");
    }
}
