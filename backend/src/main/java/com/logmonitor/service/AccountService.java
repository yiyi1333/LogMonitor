package com.logmonitor.service;

import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.model.ApiModels.PageResult;
import com.logmonitor.model.ApiModels.UserSummary;
import com.logmonitor.model.UserAccount;
import com.logmonitor.security.UserRole;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,32}$");
    private final LogMonitorMapper mapper;
    private final PasswordEncoder encoder;

    public AccountService(LogMonitorMapper mapper, PasswordEncoder encoder) {
        this.mapper = mapper;
        this.encoder = encoder;
    }

    public String canonicalUsername(String username) {
        String value = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(value).matches()) {
            throw new IllegalArgumentException("用户名须为 3-32 位字母、数字、点、下划线或连字符");
        }
        return value;
    }

    public void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码须为 8-64 个字符且不超过 72 个 UTF-8 字节");
        }
    }

    @Transactional
    public UserSummary createRegularUser(String username, String initialPassword) {
        String canonical = canonicalUsername(username);
        validatePassword(initialPassword);
        if (mapper.userCount(canonical) > 0) throw new DuplicateUsernameException();
        try {
            mapper.insertUser(canonical, encoder.encode(initialPassword), UserRole.USER.name(), true);
        } catch (DuplicateKeyException exception) {
            throw new DuplicateUsernameException();
        }
        UserAccount account = mapper.userAccount(canonical);
        return summary(account);
    }

    public PageResult<UserSummary> regularUsers(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("分页参数无效");
        return new PageResult<>(mapper.users(pageSize, (page - 1) * pageSize), mapper.regularUserCount(), page, pageSize);
    }

    @Transactional
    public UserSummary setRegularUserEnabled(long id, boolean enabled) {
        UserAccount account = regularUser(id);
        if (account.enabled() != enabled) {
            mapper.updateRegularUserStatus(id, enabled);
            account = regularUser(id);
        }
        return summary(account);
    }

    @Transactional
    public void deleteRegularUser(long id) {
        if (mapper.deleteRegularUser(id) == 0) throw new UserNotFoundException();
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        UserAccount account = mapper.userAccount(username);
        if (account == null) throw new IllegalArgumentException("账号不存在");
        if (!encoder.matches(currentPassword, account.passwordHash())) {
            throw new IllegalArgumentException("当前密码错误");
        }
        validatePassword(newPassword);
        if (encoder.matches(newPassword, account.passwordHash())) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        mapper.updateUserPassword(username, encoder.encode(newPassword));
    }

    @Transactional
    public void ensureRootUser(String username, String initialPassword) {
        String canonical = canonicalUsername(username);
        UserAccount existing = mapper.userAccount(canonical);
        if (existing == null) {
            validatePassword(initialPassword);
            mapper.insertUser(canonical, encoder.encode(initialPassword), UserRole.ROOT.name(), false);
        } else {
            mapper.promoteRootUser(canonical);
        }
    }

    private UserSummary summary(UserAccount account) {
        return new UserSummary(account.id(), account.username(), account.role().name(), account.enabled(),
                account.mustChangePassword(), account.createdAt());
    }

    private UserAccount regularUser(long id) {
        UserAccount account = mapper.userAccountById(id);
        if (account == null || account.role() != UserRole.USER) throw new UserNotFoundException();
        return account;
    }
}
