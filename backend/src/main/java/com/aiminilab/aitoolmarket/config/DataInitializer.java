package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingMapper;
import com.aiminilab.aitoolmarket.common.enums.UserStatus;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolCategoryMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final ToolCategoryMapper toolCategoryMapper;
    private final SystemSettingMapper systemSettingMapper;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserMapper userMapper, ToolCategoryMapper toolCategoryMapper,
                           SystemSettingMapper systemSettingMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.toolCategoryMapper = toolCategoryMapper;
        this.systemSettingMapper = systemSettingMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        createUserIfAbsent("admin", "123456", "Admin", UserType.ADMIN);
        createUserIfAbsent("user1", "123456", "User One", UserType.USER);
        toolCategoryMapper.ensureDefaultCategory();
        systemSettingMapper.ensureTable();
    }

    private void createUserIfAbsent(String username, String password, String nickname, UserType userType) {
        if (userMapper.findByUsername(username).isPresent()) {
            return;
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setUserType(userType.name());
        user.setStatus(UserStatus.ACTIVE.name());
        userMapper.insertAndReturnId(user);
    }
}
