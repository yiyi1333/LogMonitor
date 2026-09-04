package com.logmonitor.controller;

import com.logmonitor.model.ApiModels.PageResult;
import com.logmonitor.model.ApiModels.UserSummary;
import com.logmonitor.security.AppPermission;
import com.logmonitor.security.RequirePermission;
import com.logmonitor.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequirePermission(AppPermission.USER_MANAGE)
public class UserController {
    private final AccountService accounts;

    public UserController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public PageResult<UserSummary> users(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int pageSize) {
        return accounts.regularUsers(page, pageSize);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserSummary create(@Valid @RequestBody CreateUserRequest body) {
        return accounts.createRegularUser(body.username(), body.initialPassword());
    }

    @PatchMapping("/{id}/status")
    public UserSummary status(@PathVariable long id, @Valid @RequestBody UpdateStatusRequest body) {
        return accounts.setRegularUserEnabled(id, body.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        accounts.deleteRegularUser(id);
    }

    public record CreateUserRequest(@NotBlank String username, @NotBlank String initialPassword) {}
    public record UpdateStatusRequest(@NotNull Boolean enabled) {}
}
