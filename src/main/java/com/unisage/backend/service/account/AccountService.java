package com.unisage.backend.service.account;

import com.unisage.backend.dto.request.AccountRequest;
import com.unisage.backend.dto.response.AccountResponse;
import com.unisage.backend.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.UUID;

public interface AccountService {
    AccountResponse createAccount(AccountRequest request);
    AccountResponse updateAccount(UUID id, AccountRequest request);
    void deleteAccount(UUID id);
    void recoverAccount(UUID id);
    void deleteResources(List<UUID> ids);
    void recoverResources(List<UUID> ids);
    PageResponse<List<AccountResponse>> getAllAccounts(Pageable pageable);
    AccountResponse getAccountDetails(UUID id);
    void changePassword(UUID id, String newPassword);
}
