package com.unisage.backend.service.account;

import com.unisage.backend.dto.request.AccountRequest;
import com.unisage.backend.dto.response.AccountResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.Account;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.AccountRepository;
import com.unisage.backend.service.account.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public AccountResponse createAccount(AccountRequest request) {
        if (accountRepository.findByEmail(request.email()).isPresent())
            throw new AppException(ErrorCode.EMAIL_EXISTED);
        if (request.code() != null && accountRepository.findByCode(request.code()).isPresent())
            throw new AppException(ErrorCode.USER_CODE_EXISTED);

        Account account = Account.builder()
                .email(request.email())
                .code(request.code())
                .passwordHash(passwordEncoder.encode(request.password() != null ? request.password() : "123456"))
                .isActive(request.isActive() != null ? request.isActive() : true)
                .build();
        return toResponse(accountRepository.save(account));
    }

    @Override
    @Transactional
    public AccountResponse updateAccount(UUID id, AccountRequest request) {
        Account account = accountRepository.findActiveById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));

        if (request.email() != null && !request.email().equals(account.getEmail())) {
            if (accountRepository.findByEmail(request.email()).isPresent())
                throw new AppException(ErrorCode.EMAIL_EXISTED);
            account.setEmail(request.email());
        }
        if (request.code() != null && !request.code().equals(account.getCode())) {
            if (accountRepository.findByCode(request.code()).isPresent())
                throw new AppException(ErrorCode.USER_CODE_EXISTED);
            account.setCode(request.code());
        }
        if (request.password() != null && !request.password().isEmpty()) {
            account.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        if (request.isActive() != null) {
            account.setIsActive(request.isActive());
        }

        return toResponse(accountRepository.save(account));
    }

    @Override
    @Transactional
    public void deleteAccount(UUID id) {
        Account account = accountRepository.findActiveById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));
        account.setIsActive(false);
        accountRepository.save(account);
    }

    @Override
    @Transactional
    public void recoverAccount(UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));
        account.setIsActive(true);
        accountRepository.save(account);
    }

    @Override
    @Transactional
    public void deleteResources(List<UUID> ids) {
        List<Account> accounts = accountRepository.findAllById(ids);
        accounts.forEach(account -> account.setIsActive(false));
        accountRepository.saveAll(accounts);
    }

    @Override
    @Transactional
    public void recoverResources(List<UUID> ids) {
        List<Account> accounts = accountRepository.findAllById(ids);
        accounts.forEach(account -> account.setIsActive(true));
        accountRepository.saveAll(accounts);
    }

    @Override
    public PageResponse<List<AccountResponse>> getAllAccounts(Pageable pageable) {
        Page<Account> page = accountRepository.findAll(pageable);
        return PageResponse.fromPage(page, this::toResponse);
    }

    @Override
    public AccountResponse getAccountDetails(UUID id) {
        Account account = accountRepository.findActiveById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));
        return toResponse(account);
    }

    @Override
    @Transactional
    public void changePassword(UUID id, String newPassword) {
        Account account = accountRepository.findActiveById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        accountRepository.save(account);
    }

    private AccountResponse toResponse(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .email(account.getEmail())
                .code(account.getCode())
                .isActive(account.getIsActive())
                .lastLogin(account.getLastLogin())
                .createdAt(account.getCreatedAt())
                .createdBy(account.getCreatedBy())
                .updatedAt(account.getUpdatedAt())
                .updatedBy(account.getUpdatedBy())
                .build();
    }
}
