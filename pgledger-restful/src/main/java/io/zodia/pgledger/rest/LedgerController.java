package io.zodia.pgledger.rest;

import io.zodia.pgledger.PgLedger;
import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceQuery;
import io.zodia.pgledger.api.LedgerApi.CashMovement;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.DeleteAccount;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.PostingBatch;
import io.zodia.pgledger.store.LedgerJson;
import io.zodia.pgledger.store.read.LedgerReadService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class LedgerController {
    private static final int MAX_BODY = 1 << 20;

    private final PgLedger ledger;
    private final LedgerReadService reads;

    LedgerController(PgLedger ledger, LedgerReadService reads) {
        this.ledger = ledger;
        this.reads = reads;
    }

    @GetMapping("/health")
    ResponseEntity<byte[]> health() {
        return HttpResponses.of(200, null, HttpResponses.UP);
    }

    @PostMapping("/api/v1/balance-types")
    ResponseEntity<byte[]> createBalanceType(@RequestBody(required = false) byte[] body) {
        CreateBalanceType command = read(body, CreateBalanceType.class);
        return HttpResponses.of(200, PgLedgerServer.WRITER,
                LedgerJson.writeBytes(WriteRoutes.onWriter(() -> ledger.createBalanceType(command))));
    }

    @GetMapping("/api/v1/balance-types")
    ResponseEntity<byte[]> balanceTypes() {
        return HttpResponses.of(200, PgLedgerServer.READER, LedgerJson.writeBytes(ledger.balanceTypes()));
    }

    @PostMapping("/api/v1/accounts")
    ResponseEntity<byte[]> createAccount(@RequestBody(required = false) byte[] body) {
        CreateAccount command = read(body, CreateAccount.class);
        return HttpResponses.of(200, PgLedgerServer.WRITER,
                LedgerJson.writeBytes(WriteRoutes.onWriter(() -> ledger.createAccount(command))));
    }

    @GetMapping("/api/v1/accounts/{accountId}/balances")
    ResponseEntity<byte[]> balances(@PathVariable("accountId") String accountId) {
        return HttpResponses.of(200, PgLedgerServer.READER, LedgerJson.writeBytes(reads.balances(accountId)));
    }

    @PostMapping("/api/v1/postings")
    ResponseEntity<byte[]> post(@RequestBody(required = false) byte[] body) {
        JsonNode root = tree(body);
        if (root.has("legs")) {
            PostingBatch batch = LedgerJson.convert(root, PostingBatch.class);
            return HttpResponses.of(200, PgLedgerServer.WRITER,
                    LedgerJson.writeBytes(WriteRoutes.onWriter(() -> ledger.post(batch))));
        }
        Posting posting = LedgerJson.convert(root, Posting.class);
        return HttpResponses.of(200, PgLedgerServer.WRITER,
                LedgerJson.writeBytes(WriteRoutes.onWriter(() -> ledger.post(posting))));
    }

    @PostMapping("/api/v1/deposits")
    ResponseEntity<byte[]> deposit(@RequestBody(required = false) byte[] body) {
        CashMovement movement = read(body, CashMovement.class);
        return HttpResponses.of(200, PgLedgerServer.WRITER,
                LedgerJson.writeBytes(WriteRoutes.onWriter(() -> ledger.deposit(movement))));
    }

    @PostMapping("/api/v1/withdrawals")
    ResponseEntity<byte[]> withdraw(@RequestBody(required = false) byte[] body) {
        CashMovement movement = read(body, CashMovement.class);
        return HttpResponses.of(200, PgLedgerServer.WRITER,
                LedgerJson.writeBytes(WriteRoutes.onWriter(() -> ledger.withdraw(movement))));
    }

    @PostMapping("/api/v1/accounts/delete")
    ResponseEntity<byte[]> deleteAccount(@RequestBody(required = false) byte[] body) {
        DeleteAccount command = read(body, DeleteAccount.class);
        return HttpResponses.of(200, PgLedgerServer.WRITER, LedgerJson.writeBytes(WriteRoutes.onWriter(
                () -> ledger.deleteAccount(command.accountId(), command.balanceType(), command.currency()))));
    }

    @GetMapping("/api/v1/balances")
    ResponseEntity<byte[]> balance(
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "balanceType", required = false) String balanceType,
            @RequestParam(name = "currency", required = false) String currency) {
        Account account = reads.balance(required(accountId), required(balanceType), required(currency));
        if (account == null) {
            return HttpResponses.of(404, PgLedgerServer.READER, HttpResponses.NOT_FOUND);
        }
        return HttpResponses.of(200, PgLedgerServer.READER, LedgerJson.writeBytes(account));
    }

    @PostMapping("/api/v1/balances/query")
    ResponseEntity<byte[]> balancesQuery(@RequestBody(required = false) byte[] body) {
        BalanceQuery query = read(body, BalanceQuery.class);
        if (query.accountIds() == null || query.accountIds().isEmpty()) {
            throw new BadRequestException();
        }
        return HttpResponses.of(200, PgLedgerServer.READER, LedgerJson.writeBytes(reads.balancesQuery(query)));
    }

    @GetMapping("/api/v1/journals")
    ResponseEntity<byte[]> journals(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size) {
        int pageValue = queryInt(page, 0, 0, Integer.MAX_VALUE);
        int sizeValue = queryInt(size, PgLedger.DEFAULT_PAGE_SIZE, 1, PgLedger.MAX_PAGE_SIZE);
        return HttpResponses.of(200, PgLedgerServer.READER, LedgerJson.writeBytes(ledger.journals(pageValue, sizeValue)));
    }

    private static int queryInt(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new BadRequestException();
        }
        if (value < min || value > max) {
            throw new BadRequestException();
        }
        return value;
    }

    private static String required(String value) {
        if (value == null || value.isEmpty()) {
            throw new BadRequestException();
        }
        return value;
    }

    private static <T> T read(byte[] json, Class<T> type) {
        if (json == null || json.length == 0 || json.length > MAX_BODY) {
            throw new BadRequestException();
        }
        try {
            T value = LedgerJson.read(json, type);
            if (value == null) {
                throw new BadRequestException();
            }
            return value;
        } catch (LedgerJson.JsonReadException e) {
            throw new BadRequestException();
        }
    }

    private static JsonNode tree(byte[] json) {
        if (json == null || json.length == 0 || json.length > MAX_BODY) {
            throw new BadRequestException();
        }
        try {
            JsonNode node = LedgerJson.tree(json);
            if (node == null || node.isNull()) {
                throw new BadRequestException();
            }
            return node;
        } catch (LedgerJson.JsonReadException e) {
            throw new BadRequestException();
        }
    }
}
